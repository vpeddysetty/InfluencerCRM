# Social Sign-In Setup (Google & Facebook)

Brand owners can sign up / log in with Google or Facebook. The backend flow is
implemented in the Web Experience BFF; this guide covers the **configuration**
you must complete to make it work end-to-end.

## How the flow works

```
UI (localhost:5173)
  └─ user clicks "Google" / "Facebook"
       └─ location.assign(DPS /dps/auth/oauth/{provider}/start)   [full-page redirect]
            └─ DPS 302 → BFF /api/auth/oauth/{provider}/start
                 └─ BFF 302 → provider consent screen
                      └─ user approves
                           └─ provider 302 → BFF /api/auth/oauth/{provider}/callback  (redirect URI)
                                └─ BFF: exchange code → fetch profile → upsert user → create session
                                     └─ BFF 302 → DPS /dps/auth/oauth/complete?handoff=<opaque code>
                                          └─ DPS redeems the code server-to-server (POST /api/auth/oauth/handoff)
                                               └─ DPS 302 → UI, with the httpOnly session cookie already set
```

**No token ever reaches the browser.** The redirect carries only a single-use
handoff code, valid for 60 seconds and consumed on first read; the tokens travel
on a server-to-server call and end up in the DPS session store. This replaced an
earlier flow that base64'd the `AuthResponse` into a URL fragment for a popup to
`postMessage` — which put an access and refresh token somewhere any script on the
landing page could read, and left them in browser history.

The redirect URI you register with each provider **must exactly match** the one
the BFF sends. It now points at the **BFF's own origin**: the callback is a BFF
endpoint, and routing it through the UI origin made a provider redirect depend on
the Vite dev server's proxy being up.

Default redirect URIs (dev):
- `http://localhost:8081/api/auth/oauth/google/callback`
- `http://localhost:8081/api/auth/oauth/facebook/callback`

Override with `WEBE_PUBLIC_BASE_URL` if the BFF is reachable at another origin.

## 1. Google — Google Cloud Console

1. Go to https://console.cloud.google.com/ and create (or pick) a project.
2. **APIs & Services → OAuth consent screen**
   - User type: **External** (for testing you can leave it in "Testing" mode).
   - Add your email as a **Test user** so you can sign in before the app is verified.
   - Scopes needed: `openid`, `email`, `profile` (the defaults).
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID**
   - Application type: **Web application**.
   - **Authorized redirect URIs** — add exactly:
     `http://localhost:8081/api/auth/oauth/google/callback`
   - (Google allows `http` for `localhost`. For any non-localhost host you must use `https`.)
4. Copy the **Client ID** and **Client secret**.

## 2. Facebook — Meta for Developers

1. Go to https://developers.facebook.com/apps/ and **Create App** (type: "Consumer"
   or "None"; you need the **Facebook Login** product).
2. Add the **Facebook Login** product to the app.
3. **Facebook Login → Settings → Valid OAuth Redirect URIs** — add exactly:
   `http://localhost:8081/api/auth/oauth/facebook/callback`
4. Under **App Settings → Basic**, copy the **App ID** and **App Secret**.
5. While the app is in **Development** mode, only app admins/testers/developers can
   log in — add your Facebook account as a tester if needed.
6. Facebook only returns `email` if the user granted it; the backend rejects a
   profile with no email (`facebook profile did not include an email address`).

## 3. Provide the credentials to the BFF

The `application.properties` values read from the environment first and fall back
to `replace-me`. Use **either** approach:

### Option A — git-ignored local properties file
1. Copy `src/main/resources/application-local.properties.example`
   to `src/main/resources/application-local.properties` (git-ignored).
2. Fill in the four values (`google.client-id/secret`, `facebook.client-id/secret`).
3. Run the BFF with the `local` profile:
   ```
   mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.port=18081
   ```

### Option B — environment variables
```
set GOOGLE_OAUTH_CLIENT_ID=...           (PowerShell: $env:GOOGLE_OAUTH_CLIENT_ID="...")
set GOOGLE_OAUTH_CLIENT_SECRET=...
set FACEBOOK_OAUTH_CLIENT_ID=...
set FACEBOOK_OAUTH_CLIENT_SECRET=...
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18081
```

## 4. Verify

1. Start Postgres, the DAO (`:8443`), the BFF (`:8081`), the **DPS (`:8090`)**, and
   the UI (`:5173`). The DPS is required — it is where the flow now completes.
2. Open the app, click **Google** or **Facebook** on the landing page.
3. The page redirects to the provider consent screen → approve → you land back on
   the app, signed in, with an `INFLUENCRM_SESSION` cookie set.
4. Confirm no token leaked into the browser: DevTools → Application → Local
   Storage and Session Storage should contain **no** access or refresh token, and
   the session cookie should be marked `HttpOnly`.

### Quick backend checks (no browser)
- `GET http://localhost:8090/dps/auth/oauth/google/start`
  - `302` to the BFF's start endpoint.
  - An unsupported provider gives `400 Unsupported provider: ...`.
- `GET http://localhost:8081/api/auth/oauth/google/start`
  - With `replace-me`: `400 google.client-id is not configured`.
  - With real creds: `302` to `accounts.google.com/...`.
- Callback error path bounces to the DPS, not the UI:
  `GET .../oauth/google/callback?code=x&state=bogus`
  → `302 http://localhost:8090/dps/auth/oauth/complete?error=...`
  → DPS `302` to the UI's `/login?error=...`
- Handoff codes are single-use — replaying one fails:
  `POST http://localhost:8081/api/auth/oauth/handoff {"handoff":"<code>"}`
  → second call gives `Invalid or expired OAuth handoff code`.

## Account linking

A provider sign-in whose email matches an existing account is **refused** unless
the provider states it verified that address (`email_verified`). Google sets that
claim; Facebook's Graph API does not expose one, so a Facebook identity never
auto-links. The account holder links the provider deliberately from a signed-in
session instead.

This is not a convenience trade-off. Matching on an unverified email means anyone
who can present `owner@brand.com` to a lax provider — or post it to the social
signup endpoint, which accepts an email with no token at all — receives a session
for that brand's account.

## Production notes

- Set `web-experience.public-base-url` (or `WEBE_PUBLIC_BASE_URL`) to the BFF's
  real external origin — the provider redirect URIs derive from it.
- Set `web-experience.dps-base-url` (`WEBE_DPS_BASE_URL`) to the real DPS origin,
  and `dps.ui-base-url` (`DPS_UI_BASE_URL`) to the real UI origin.
- Register the production redirect URIs in both provider consoles.
- Enable `dps.cookie-secure=true`; a Secure cookie is never sent over plain HTTP.
- The handoff store is in-memory, so the DPS must reach the **same** BFF instance
  that handled the callback. Behind a load balancer, either enable session
  affinity for `/api/auth/oauth/**` or move the store to Redis alongside the
  refresh tokens.
- Never commit real client secrets — `application-local.properties` is git-ignored.
