# Digital Presentation Service (DPS)

**Date:** 2026-08-02
**Port:** `:8090`
**What it is:** the authentication and authorization entry point for every micro-frontend origin,
holding the browser session server-side.

---

## Why it exists

The presentation gateway previously lived in React. It worked — one login served six origins via a
shared React context — but it had two limits that only a server-side component can remove.

| Problem | React gateway | DPS |
|---|---|---|
| **Token exposure** | Access token in `localStorage`; any XSS payload could read it | Session is an httpOnly cookie. **JavaScript cannot read it at all** |
| **Cross-origin session** | Depended on React context reaching each remote | Browser sends the cookie to the DPS from any allowed origin, React or not |
| **Refresh coordination** | Six remotes racing to rotate one refresh token, collapsed in JS | Happens once, server-side, where the tokens live |
| **Login-time cache** | Nowhere sensible to put it | Assembled at login and carried on the session |

The last row is the one you asked for specifically. A cache in the React layer dies on refresh and
cannot be shared across origins; a cache in one context service can only warm its own data. The DPS
sits at the login boundary, holds the session, and knows the brand — the only component with both
the trigger and the scope.

---

## Architecture

```
  browser ──── httpOnly cookie ────► DPS :8090
                                       │
                        ┌──────────────┼──────────────┐
                        │              │              │
                   SessionStore   LoginCache     IdentityClient
                   (Caffeine;      Warmers        (brokers to BFF)
                    Redis-ready)                        │
                                                        ▼
                                                   BFF :8081
                                                        │
                                          ┌─────────────┴─────────────┐
                                     Identity :8445   ... 6 more services
```

The DPS deliberately brokers through the **BFF** rather than calling Identity directly. The BFF
already owns token issuance, brand-access resolution and the permission matrix. Reimplementing any
of that here would create two authorization implementations that can disagree — the exact failure
that once left `FINANCE` users unable to log in, because one rule was written twice and the copies
drifted.

---

## The endpoints

| Endpoint | Purpose |
|---|---|
| `POST /dps/auth/login` | Authenticate; sets the session cookie |
| `POST /dps/auth/signup` | Register; sets the session cookie |
| `POST /dps/auth/logout` | End the session; clears the cookie |
| `GET /dps/session` | Who am I? **200 with `authenticated:false`** when anonymous |
| `GET /dps/brands` | Brands this session may reach |
| `POST /dps/brands/switch` | Re-scope the session to another brand |
| `GET /dps/authorize?permission=` | Does this session hold a permission? |
| `GET /dps/cache` | Data warmed at login |
| `ANY /dps/api/**` | Proxy to the platform, with credentials attached server-side |

`/dps/session` returning **200, not 401**, for an anonymous caller is deliberate. Not being logged in
is a normal first-visit state; a 401 there would fill the console with errors and tempt callers into
treating a routine case as a failure.

---

## What the browser can see

```json
{
  "authenticated": true,
  "userId": "...", "email": "...", "userName": "...",
  "accountId": "...", "brandId": "...", "brandName": "...",
  "role": "ADMIN",
  "permissions": ["creator:write", "..."],
  "availableBrands": [...],
  "warmCache": {}
}
```

There is **no field capable of holding a token**. `SessionView` is a record with a fixed shape, so
leaking one would require changing the type — not merely forgetting to strip a value.

Verified: `login response contains NO token → 0 occurrences of accessToken/refreshToken/eyJ`.

---

## Cookies, CORS and CSRF

These three are coupled, and getting one wrong silently breaks the others.

**CORS must name every origin.** A cookie is only sent cross-origin when
`Access-Control-Allow-Credentials: true`, and the spec forbids pairing that with `*`. Enumerating the
micro-frontend origins is therefore a *constraint*, not a precaution — though it is also the safer
design. Verified: the DPS echoes `http://localhost:5177` and refuses `http://evil.example`.

**CSRF protection becomes necessary.** Bearer tokens were immune by construction — a browser never
attaches one automatically. Cookies *are* attached automatically, so a malicious page could
otherwise make authenticated requests on the user's behalf. The double-submit pattern restores that
guarantee: `XSRF-TOKEN` is deliberately readable by JavaScript, because only same-origin script can
read it and echo it back.

**Two cookies, two purposes:**

```
INFLUENCRM_SESSION=...  HttpOnly; SameSite=Lax; Max-Age=28800   ← the session, unreadable by JS
XSRF-TOKEN=...          Path=/                                   ← readable by design, for CSRF
```

---

## The login-time cache

The extension point you asked for. Register a bean:

```java
@Component
public class BrandReferenceWarmer implements LoginCacheWarmer {
    public String key() { return "brandReference"; }

    public Map<String, Object> warm(UiSession session) {
        // Runs once at login, scoped to session.brandId()
        return Map.of("campaignTypes", ..., "platforms", ...);
    }
}
```

`LoginCacheService` discovers and runs all of them, and remotes read the result from `/dps/cache`.

Three rules the implementation enforces:

1. **A warmer must never fail the login.** Exceptions are caught and logged; the login succeeds and
   the remote fetches normally. Degraded is acceptable; unable to sign in is not.
2. **Warm only what the first screen needs.** Warming is on the login path, so a slow warmer moves
   latency into sign-in, where users notice it more. Anything over 1s is logged as a warning.
3. **The cache is dropped on brand switch.** It was built for the previous brand, and serving one
   brand's cached data under another's name is the cross-tenant leak Phase 2 spent its whole budget
   eliminating.

**Nothing is registered yet** — the service logs that on startup, so the state is visible rather than
mysterious.

---

## Scaling: Redis sessions

**Implemented.** `RedisSessionStore` shares sessions across every DPS instance, so a load balancer
may move a user freely and a restart no longer signs anyone out.

```properties
dps.session-store=redis          # anything else uses in-memory Caffeine
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

The choice is **explicit, not inferred from whether Redis happens to be reachable**. Silently
degrading to in-memory in a multi-instance deployment would appear to work and then log users out at
random — the worst available failure mode, because it presents as a mysterious application bug
rather than a missing dependency. With `session-store=redis` and Redis unreachable, the service
refuses to start.

### Two keys per session

```
dps:session:{sessionId}   → the serialised session   (TTL = session lifetime)
dps:user:{userId}         → SET of that user's session ids
```

The reverse index exists so "log this user out everywhere" is a set lookup rather than a keyspace
scan. `KEYS` over a production keyspace is the classic way to stall Redis, and revoking a
compromised account is precisely when you cannot afford that.

Redis TTL provides sliding expiry: every read rewrites the key with a fresh TTL, so activity extends
the session and idleness ends it.

### Verified

A session was created, the DPS was **restarted**, and the same cookie still resolved — 31
permissions intact, API proxy still returning 200. With the in-memory store that sequence logs the
user out.

---

## Running it

```bash
cd InfluencerPresentationService && mvn spring-boot:run   # :8090
```

Requires the BFF on `:8081`. Point the React shell at it with `VITE_DPS_URL` (defaults to
`http://localhost:8090`).

---

## Before production

| Item | Why |
|---|---|
| **`dps.cookie-secure=true`** | A Secure cookie is never sent over plain HTTP — off locally only |
| **`dps.cookie-same-site=None`** | Cross-origin remotes need it; the spec requires `Secure` alongside |
| **Redis-backed `SessionStore`** | Required before a second instance exists |
| **Narrow `dps.allowed-origins`** | Real origins only, never a development list |
| **Rotate `dps.service-token`** | Shares the committed development default today |

---

## What this is not

- **Not an API gateway.** The BFF fills that role. This is the *presentation* layer: it owns the
  browser session and fronts authentication.
- **Not a second authorization model.** It reads permissions from the token the BFF minted and
  re-checks nothing. Server-side authorization remains authoritative and unchanged.
- **Not a replacement for the BFF.** It is a client of it.
