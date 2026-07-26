# Social Sign-In Setup (Google & Facebook)

Brand owners can sign up / log in with Google or Facebook. The backend flow is
implemented in the Web Experience BFF; this guide covers the **configuration**
you must complete to make it work end-to-end.

## How the flow works

```
UI (localhost:5173)
  └─ user clicks "Google" / "Facebook"
       └─ window.open('/api/auth/oauth/{provider}/start')   [popup]
            └─ BFF 302 → provider consent screen
                 └─ user approves
                      └─ provider 302 → /api/auth/oauth/{provider}/callback  (redirect URI)
                           └─ BFF: exchange code → fetch profile → upsert user → create session
                                └─ BFF 302 → http://localhost:5173/oauth-callback.html#result=<base64url AuthResponse>
                                     └─ popup postMessage → main window establishes session → navigates to /import
```

The redirect URI you register with each provider **must exactly match** the one
the BFF sends. By default that is the **UI origin** (the Vite dev server proxies
`/api` to the BFF), so both the popup and the app stay same-origin.

Default redirect URIs (dev):
- `http://localhost:5173/api/auth/oauth/google/callback`
- `http://localhost:5173/api/auth/oauth/facebook/callback`

## 1. Google — Google Cloud Console

1. Go to https://console.cloud.google.com/ and create (or pick) a project.
2. **APIs & Services → OAuth consent screen**
   - User type: **External** (for testing you can leave it in "Testing" mode).
   - Add your email as a **Test user** so you can sign in before the app is verified.
   - Scopes needed: `openid`, `email`, `profile` (the defaults).
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID**
   - Application type: **Web application**.
   - **Authorized redirect URIs** — add exactly:
     `http://localhost:5173/api/auth/oauth/google/callback`
   - (Google allows `http` for `localhost`. For any non-localhost host you must use `https`.)
4. Copy the **Client ID** and **Client secret**.

## 2. Facebook — Meta for Developers

1. Go to https://developers.facebook.com/apps/ and **Create App** (type: "Consumer"
   or "None"; you need the **Facebook Login** product).
2. Add the **Facebook Login** product to the app.
3. **Facebook Login → Settings → Valid OAuth Redirect URIs** — add exactly:
   `http://localhost:5173/api/auth/oauth/facebook/callback`
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

1. Start Postgres, the DAO (`:8443`), the BFF (`:18081`), and the UI (`:5173`).
2. Open the app, click **Google** or **Facebook** on the landing page.
3. A popup opens the provider consent screen → approve → popup closes → the app
   lands on `/import`, signed in.

### Quick backend checks (no browser)
- `GET http://localhost:18081/api/auth/oauth/google/start`
  - With `replace-me`: `400 google.client-id is not configured`.
  - With real creds: `302` to `accounts.google.com/...`.
- Callback error path always bounces to the UI page:
  `GET .../oauth/google/callback?code=x&state=bogus`
  → `302 http://localhost:5173/oauth-callback.html#error=...`

## Production notes

- Set `web-experience.ui-base-url` (or `WEBE_UI_BASE_URL`) to the real UI origin,
  e.g. `https://app.yourdomain.com`. The redirect URIs derive from it automatically.
- Register the production redirect URIs in both provider consoles.
- Never commit real client secrets — `application-local.properties` is git-ignored.
