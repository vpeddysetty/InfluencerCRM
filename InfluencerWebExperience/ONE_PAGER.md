# InfluencerCRB Web Experience Service - One Pager

## Goal

Give brand users a simple authentication entry point for InfluencerCRB: sign up, log in, log out, and start social sign up with Google or Facebook, while keeping the canonical user record in the DAO `users` table.

## What It Does

- brand email/password sign up
- brand email/password login
- logout by bearer token invalidation
- Google social sign up and OAuth callback flow
- Facebook social sign up and OAuth callback flow
- DAO-backed user create, lookup, and update

## API Surface

- `GET /health`
- `POST /api/auth/signup`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `POST /api/auth/google/signup`
- `POST /api/auth/facebook/signup`
- `GET /api/auth/oauth/google/start`
- `GET /api/auth/oauth/google/callback`
- `GET /api/auth/oauth/facebook/start`
- `GET /api/auth/oauth/facebook/callback`

## How It Works

1. Local signup hashes the password with BCrypt and writes the user through DAO `/users`.
2. Login resolves the user through DAO `/users/by-email` and verifies the password locally.
3. Logout removes the in-memory session token.
4. Social signup can use either direct access tokens or browser redirect/callback OAuth flows.
5. OAuth callback exchanges the code for a token, resolves the provider profile, and creates or updates the DAO user row.

## Dependencies

- DAO service over HTTPS at `web-experience.dao-base-url`
- DAO `users` entity and REST endpoints
- Google OAuth configuration
- Facebook OAuth configuration
- in-memory session and OAuth state storage

## Configuration

- `web-experience.dao-base-url`
- `web-experience.session-ttl-minutes`
- `web-experience.oauth.google.*`
- `web-experience.oauth.facebook.*`

## Notes

- OAuth client IDs and secrets must be replaced with real provider credentials.
- Sessions are in-memory and are intended for MVP/local use.
- The service is a Spring Boot API layer, not a UI renderer.

## Service Contracts

- Successful auth responses share one shape: `userId`, `email`, `brandName`, `role`, `plan`, `accessToken`, `tokenType`, `issuedAt`.
- Brand signup requires `email` and `password`, and optionally `brandName`.
- Login requires `email` and `password`.
- Logout requires `accessToken`.
- Social signup accepts `accessToken`, optional `fallbackEmail`, optional `fallbackDisplayName`, and optional `brandName`.
- OAuth browser flow uses `GET /api/auth/oauth/{provider}/start` and `GET /api/auth/oauth/{provider}/callback?code=...&state=...`.
- DAO lookup contract for login is `GET /users/by-email?email=...`.
- DAO persistence contract for signup is `POST /users` and `PUT /users/{id}`.

## Error Contract

- HTTP 400 for invalid input, invalid credentials, missing OAuth state, and missing provider configuration.
- HTTP 502 for provider or DAO transport failures.
- Error responses return a JSON object with an `error` message.