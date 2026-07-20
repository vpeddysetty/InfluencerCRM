# InfluencerCRB Web Experience Service - Detailed Design

## 1. Purpose

The InfluencerCRB Web Experience Service provides the brand-facing authentication and account entry layer for the platform. It exposes Spring Boot HTTP APIs for:

- brand user sign up
- brand user login
- brand user logout
- Google sign up / OAuth callback flow
- Facebook sign up / OAuth callback flow

The service does not own the user data model. It integrates with the DAO service and persists users through the DAO `users` table entity and REST endpoints.

## 2. Scope

Included in this service:

- REST API for signup, login, logout
- Google and Facebook OAuth browser redirect and callback flow
- DAO-backed user creation, lookup, and update
- password hashing for local brand sign up
- in-memory session issuance and invalidation
- OAuth state tracking for redirect-based social sign up
- error normalization for API responses

Not included:

- UI rendering
- persistent session storage
- refresh tokens
- account recovery / password reset
- multi-tenant role management beyond the brand owner entry point
- full production OAuth secret management and secrets rotation

## 3. Runtime Stack

- Java 17
- Spring Boot 3.2.5
- Spring Web
- Spring Validation
- Jackson
- Spring Security Crypto for password hashing
- Java HttpClient for DAO and OAuth calls

## 4. High-Level Architecture

1. API Layer
   - exposes `/api/auth/*` endpoints
   - exposes `/health`
2. Auth Service Layer
   - coordinates brand signup, login, logout, and social signup
   - hashes passwords and creates sessions
3. OAuth Flow Layer
   - generates provider authorization URLs
   - stores and consumes OAuth `state`
   - handles browser callback completion
4. OAuth Profile Layer
   - exchanges authorization codes for access tokens
   - fetches provider profile data
5. DAO Client Layer
   - calls DAO `users` endpoints over HTTPS
   - performs user lookup, create, and update operations
6. Session Layer
   - holds active sessions in memory
   - invalidates sessions on logout

## 5. Project Structure

```text
InfluencerWebExperience/
  pom.xml
  src/main/java/com/influencer/webe/
    InfluencerWebExperienceApplication.java
    config/
      WebExperienceProperties.java
    client/
      DaoUserClient.java
    controller/
      AuthController.java
      ApiExceptionHandler.java
      HealthController.java
    service/
      AuthService.java
      OAuthFlowService.java
      OAuthProfileService.java
      OAuthStateService.java
      SessionService.java
  src/main/resources/
    application.properties
```

## 6. API Design

### GET /health

Returns service liveness.

Response:

```json
{"status":"ok"}
```

### POST /api/auth/signup

Creates a brand user using email, password, and optional brand name.

Request:

```json
{
  "email": "brand@example.com",
  "password": "secret123",
  "brandName": "Example Brand"
}
```

Behavior:

- normalizes email to lowercase
- checks DAO for existing user by email
- hashes password with BCrypt
- creates user through DAO `/users`
- returns an access token from the in-memory session store

### POST /api/auth/login

Authenticates a brand user with email and password.

Request:

```json
{
  "email": "brand@example.com",
  "password": "secret123"
}
```

Behavior:

- looks up user through DAO `/users/by-email`
- verifies password hash with BCrypt
- creates an in-memory session
- returns a bearer token payload

### POST /api/auth/logout

Invalidates the current bearer token.

Request:

```json
{
  "accessToken": "<token>"
}
```

Behavior:

- removes token from the in-memory session store
- returns HTTP 204 when successful

### POST /api/auth/google/signup

Social signup entry point for token-based Google login/signup.

Request:

```json
{
  "accessToken": "<google-access-token>",
  "fallbackEmail": "optional@brand.com",
  "fallbackDisplayName": "Brand Owner",
  "brandName": "Example Brand"
}
```

Behavior:

- resolves Google profile from the provider userinfo endpoint
- creates or updates the DAO user record
- stores provider metadata in `custom_attributes`
- creates an in-memory session

### POST /api/auth/facebook/signup

Social signup entry point for token-based Facebook login/signup.

Behavior mirrors Google signup but uses the Facebook profile endpoint.

### GET /api/auth/oauth/google/start

Starts browser-based Google OAuth sign up.

Behavior:

- creates OAuth `state`
- returns HTTP 302 redirect to Google authorization URL
- includes configured redirect URI and scopes

### GET /api/auth/oauth/google/callback

Completes browser-based Google OAuth sign up.

Behavior:

- validates `state`
- exchanges authorization `code` for an access token
- fetches Google profile information
- creates or updates DAO user data
- returns the auth response payload

### GET /api/auth/oauth/facebook/start

Starts browser-based Facebook OAuth sign up.

Behavior mirrors Google start but uses the Facebook authorization URL and scopes.

### GET /api/auth/oauth/facebook/callback

Completes browser-based Facebook OAuth sign up.

Behavior mirrors Google callback but uses Facebook token exchange and profile lookup.

## 7. Authentication Flow

### 7.1 Brand sign up

1. Client posts email, password, and brand name.
2. Service checks the DAO for an existing user with the same email.
3. Password is hashed with BCrypt.
4. DAO user record is created via `/users`.
5. Session token is issued in memory.

### 7.2 Brand login

1. Client posts email and password.
2. Service queries DAO `/users/by-email`.
3. BCrypt password verification runs locally.
4. Session token is issued in memory.

### 7.3 Logout

1. Client sends the bearer token.
2. Session token is removed from the in-memory store.

### 7.4 Social signup

The service supports two forms:

- token-based social signup for direct client integration
- browser redirect OAuth flow for standard provider-based sign in

Browser flow steps:

1. `/oauth/{provider}/start` creates `state`
2. user is redirected to the provider authorization page
3. provider returns to `/oauth/{provider}/callback`
4. service exchanges `code` for access token
5. service fetches user profile and resolves email/provider identity
6. DAO user is created or updated
7. a session token is issued

## 8. DAO Integration

The service integrates with the DAO `users` entity through these endpoints:

- `GET /users`
- `GET /users/by-email?email=...`
- `POST /users`
- `PUT /users/{id}`

### DAO responsibilities

- store the canonical user row
- maintain user timestamps
- keep `custom_attributes` JSONB data
- enforce unique email at the database level

### Web service responsibilities

- validate sign up and login inputs
- hash passwords before DAO write
- add social metadata to `custom_attributes`
- avoid direct database access

## 9. Persistence and Session Design

### DAO persistence

The web service delegates user persistence to the DAO service and never writes directly to PostgreSQL.

### Session management

- sessions are kept in memory using a concurrent map
- session TTL is configured through `web-experience.session-ttl-minutes`
- logout invalidates the token immediately
- expired tokens are evicted on lookup

This is sufficient for local development and MVP flows, but not production-grade distributed auth.

## 10. OAuth Design

### Provider configuration

Each provider has:

- authorization URI
- token URI
- client ID
- client secret
- redirect URI
- userinfo URI

### State handling

- every redirect start creates a random `state`
- `state` is stored in-memory with a short TTL
- callback must present the same state to be accepted
- state is consumed once and cannot be reused

### Profile resolution

- Google: uses the Google userinfo endpoint
- Facebook: uses the Facebook Graph API profile endpoint
- if provider lookup fails, a fallback email can be used for local testing

## 11. Configuration

### Core properties

- `web-experience.dao-base-url`
- `web-experience.session-ttl-minutes`

### Google OAuth properties

- `web-experience.oauth.google.authorization-uri`
- `web-experience.oauth.google.token-uri`
- `web-experience.oauth.google.client-id`
- `web-experience.oauth.google.client-secret`
- `web-experience.oauth.google.redirect-uri`
- `web-experience.oauth.google.userinfo-uri`

### Facebook OAuth properties

- `web-experience.oauth.facebook.authorization-uri`
- `web-experience.oauth.facebook.token-uri`
- `web-experience.oauth.facebook.client-id`
- `web-experience.oauth.facebook.client-secret`
- `web-experience.oauth.facebook.redirect-uri`
- `web-experience.oauth.facebook.userinfo-uri`

## 12. Security Notes

- passwords are hashed with BCrypt before DAO persistence
- auth tokens are bearer-style session tokens
- local HTTP clients trust the DAO HTTPS endpoint with a self-signed cert for development
- OAuth client secrets must be replaced with real provider credentials before production use
- sessions are in-memory and will be lost on restart

## 13. Error Handling

The service normalizes common failures into API-friendly responses:

- `IllegalArgumentException` -> HTTP 400
- `IllegalStateException` -> HTTP 502
- DAO call failures -> HTTP 502
- OAuth state issues -> HTTP 400
- invalid provider configuration -> HTTP 400

## 14. Current Status

The service currently provides:

- brand signup/login/logout
- Google and Facebook social signup
- Google and Facebook browser redirect/callback OAuth flows
- DAO user integration
- local in-memory sessions
- environment-based OAuth configuration

## 15. Next Priorities

1. Replace in-memory sessions with durable token storage
2. Add provider refresh-token support
3. Add automated integration tests with DAO mocked HTTP responses
4. Add front-end sign-in screens or a thin UI layer
5. Move OAuth secrets to a secure secret store

## Service Contracts

### Auth response contract

All successful auth endpoints return the same response shape:

```json
{
  "userId": "<uuid>",
  "email": "brand@example.com",
  "brandName": "Example Brand",
  "role": "owner",
  "plan": "free",
  "accessToken": "<bearer-token>",
  "tokenType": "Bearer",
  "issuedAt": "2026-07-18T12:00:00Z"
}
```

### Brand signup contract

Request:

```json
{
  "email": "brand@example.com",
  "password": "secret123",
  "brandName": "Example Brand"
}
```

Rules:

- `email` is required and normalized to lowercase
- `password` is required and hashed with BCrypt before persistence
- `brandName` is optional

DAO contract:

- read user by email from `GET /users/by-email?email=...`
- create user through `POST /users`

### Login contract

Request:

```json
{
  "email": "brand@example.com",
  "password": "secret123"
}
```

Rules:

- `email` is required
- `password` is required
- invalid credentials return HTTP 400 through the API error handler

DAO contract:

- lookup user through `GET /users/by-email?email=...`

### Logout contract

Request:

```json
{
  "accessToken": "<token>"
}
```

Rules:

- `accessToken` is required
- session token is removed from the in-memory store

### Social signup contract

Request:

```json
{
  "accessToken": "<provider-token>",
  "fallbackEmail": "optional@brand.com",
  "fallbackDisplayName": "Brand Owner",
  "brandName": "Example Brand"
}
```

Rules:

- the provider token is used to fetch profile data when available
- fallback email is only used when the provider profile cannot be resolved in local/dev scenarios
- provider metadata is merged into `custom_attributes.oauth`

DAO contract:

- create a new user when the email does not exist
- update the existing user when the email already exists

### OAuth browser flow contract

Start request:

- `GET /api/auth/oauth/{provider}/start`

Callback request:

- `GET /api/auth/oauth/{provider}/callback?code=...&state=...`

Behavior:

- `start` returns HTTP 302 redirect to the provider authorization endpoint
- `callback` validates `state`
- `callback` exchanges `code` for an access token
- `callback` resolves profile data and returns the standard auth response

### Error contract

The service normalizes common failures as JSON responses:

```json
{
  "error": "message"
}
```

Status mapping:

- HTTP 400 for invalid input, invalid credentials, missing OAuth state, and missing provider config
- HTTP 502 for DAO or provider transport failures
