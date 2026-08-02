# Presentation Gateway

**Date:** 2026-08-02
**What it is:** the single origin a user visits, which authenticates them once and assembles a UI
from micro-frontends served by six other origins.

---

## The problem it solves

Micro-frontends are served from different origins. `localStorage`, `sessionStorage` and cookies are
all origin-scoped, so a token written by the shell at `:5173` is **invisible** to a remote at
`:5174`.

Left alone, that forces one of two bad outcomes:

1. **Each remote authenticates separately** — six logins for one application, and the token now
   exists in six places instead of one.
2. **The token is broadcast to every remote** — one careless or compromised remote leaks a
   credential that works everywhere.

The gateway takes a third path: the shell is the **sole holder** of the credential, and remotes
receive a narrow, revocable *capability* instead of the token.

---

## How it works

```
                    ┌──────────────────────────────────────┐
   user ──────────► │  Presentation Gateway  :5173         │
                    │                                      │
                    │  • authenticates (login/signup)      │
                    │  • holds the session (one place)     │
                    │  • resolves brand + permissions      │
                    │  • routes across origins             │
                    └───────────────┬──────────────────────┘
                                    │  React context (shared singleton)
                                    │  → session facts + authorizedFetch
        ┌───────────┬───────────┬───┴───────┬───────────┬───────────┐
        ▼           ▼           ▼           ▼           ▼           ▼
   mf_workflow  mf_campaigns mf_creators mf_commerce mf_finance  mf_content
      :5174        :5175       :5176       :5177       :5178       :5179

   Every remote calls gateway.fetch(...) → BFF :8081 → 7 context services
```

**One login. Six origins. Zero tokens outside the gateway.**

### Why React context works across origins

React is declared a **federation singleton** in every remote and in the host. All remotes therefore
share the host's React instance, and with it the host's context tree. A remote loaded from `:5176`
calls `useGateway()` and reads the same object the shell created — even though its own origin has no
storage access to the session.

That singleton is not optional. Two copies of React in one page break hooks, and the same setting is
what makes cross-origin session sharing possible at all.

---

## The contract handed to a remote

```js
const { brandId, role, permissions, fetch, can, switchBrand } = useGateway()
```

| Exposed | Why |
|---|---|
| `brandId`, `accountId`, `role`, `permissions` | Facts the remote needs to render correctly |
| `fetch(path, options)` | The **only** sanctioned route to the API |
| `can(permission)` | Decides what to show — a UX affordance, not a control |
| `switchBrand(brandId)` | Gateway-owned: the server re-mints the token per brand |

| Deliberately **not** exposed | Why |
|---|---|
| `accessToken` | A remote cannot leak a credential it never held |
| `refreshToken` | Rotation is a gateway concern; concurrent refreshes must be collapsed |
| Raw `fetch` | Every call must carry `Authorization` and `X-Brand-Id`; leaving that to call sites guarantees one eventually forgets |

`authorizedFetch` stamps the bearer token and the `X-Brand-Id` tenancy header, and retries once
through refresh on a 401 — so an access token expiring mid-session is invisible to the remote.

---

## Design decisions worth knowing

### Authorization is *not* enforced here

`can()` decides what to render. The server re-checks every call. A remote is JavaScript in the
user's browser: treating this as the security boundary would be a mistake. Hiding a control the user
cannot use avoids a dead end — it is not what stops them acting.

### Concurrent refreshes are collapsed

Several remotes can hit a 401 simultaneously. Without collapsing, each would rotate the refresh
token, and all but one would end up holding a token the server has already invalidated — logging the
user out at random. `PresentationGateway` keeps a single in-flight refresh that all callers await.

### Remotes are opt-in and fall back at runtime

```bash
npm run dev                          # bundled pages, no federation
VITE_USE_REMOTES=true npm run dev    # load from remotes
```

A remote that is down, mid-deploy, or misconfigured **degrades to the bundled page** and logs a
warning, rather than rendering a blank route. This is what makes federation adoptable one context at
a time instead of as a big-bang cutover — and it means a remote outage is a degraded experience, not
an outage.

### Origins live in a registry, not in imports

[`originRegistry.js`](../InfluencerUI/src/shell/gateway/originRegistry.js) maps each context to its
origin, scope name and exposed modules. The Vite config builds its `remotes` block from that same
registry, so the two cannot drift. Moving a remote to a CDN or a preview environment is an
environment variable, not a code change.

---

## Running it

```bash
# Backend: gateway BFF + 7 context services
cd InfluencerWebExperience && mvn spring-boot:run -Dspring-boot.run.profiles=local   # :8081
# ...plus :8443-:8450 (see TEST-REPORT.md)

# Micro-frontends
cd InfluencerWorkflowUI  && npm run dev   # :5174
cd InfluencerCampaignsUI && npm run dev   # :5175
cd InfluencerCreatorsUI  && npm run dev   # :5176
cd InfluencerCommerceUI  && npm run dev   # :5177
cd InfluencerFinanceUI   && npm run dev   # :5178
cd InfluencerContentUI   && npm run dev   # :5179

# The gateway, consuming all six
cd InfluencerUI && VITE_USE_REMOTES=true npm run dev   # :5173  ← visit this
```

Each remote also runs standalone (`npm run dev` and open its port directly) with stubbed session
state — a team can develop their slice without the platform.

---

## Production considerations

Local development uses `Access-Control-Allow-Origin: *` on the remotes. That is fine for `localhost`
and **not** fine in production. Before deploying:

| Item | Why |
|---|---|
| **Restrict CORS** to the gateway origin | A wildcard lets any page load your remotes |
| **Add a CSP** with `script-src` listing the federated origins | Federation loads executable code cross-origin; that list should be deliberate, not a wildcard. `federatedOrigins()` returns it |
| **Serve everything over HTTPS** | A token in memory is still exposed by a mixed-content page |
| **Version remote entries** | An unversioned `remoteEntry.js` cached by a browser can pin a user to a stale remote |
| **Consider httpOnly cookies** over `localStorage` | Would remove the XSS token-theft surface entirely, at the cost of CSRF handling — a deliberate trade, worth making before real traffic |

---

## What this is not

- **Not an API gateway.** The BFF (`:8081`) already fills that role. This is the *presentation*
  layer: it federates UI and owns the browser session.
- **Not a security boundary.** Server-side authorization is unchanged and remains authoritative.
- **Not a router replacement.** React Router still owns navigation; the gateway decides which
  *origin* serves each route.
