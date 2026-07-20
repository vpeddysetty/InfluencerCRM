# InfluencerCRB Web Experience Service - One Page Summary

## Goal

Provide the brand-facing authentication layer for InfluencerCRB with signup, login, logout, and social auth, while persisting users through the DAO `users` entity.

## What Is Exposed

- GET /health
- POST /api/auth/signup
- POST /api/auth/login
- POST /api/auth/logout
- POST /api/auth/google/signup
- POST /api/auth/facebook/signup
- GET /api/auth/oauth/google/start
- GET /api/auth/oauth/google/callback
- GET /api/auth/oauth/facebook/start
- GET /api/auth/oauth/facebook/callback

Service entrypoint: `InfluencerWebExperience/src/main/java/com/influencer/webe/InfluencerWebExperienceApplication.java`

## Core Pipeline

1. Brand signup and login
   - hashes passwords with BCrypt
   - checks and writes users through the DAO service
   - issues in-memory bearer sessions

2. Social signup
   - supports token-based Google/Facebook signup
   - supports browser redirect and callback OAuth flows
   - stores provider metadata in `custom_attributes`

3. DAO integration
   - uses DAO `/users/by-email` for lookup
   - uses DAO `/users` and `/users/{id}` for create/update
   - never touches PostgreSQL directly

4. Session and state handling
   - sessions are in-memory
   - OAuth `state` is in-memory with short TTL
   - logout invalidates tokens immediately

## Configuration

Important properties:

- `web-experience.dao-base-url`
- `web-experience.session-ttl-minutes`
- `web-experience.oauth.google.*`
- `web-experience.oauth.facebook.*`

## Important Notes

- OAuth client ID and secret values must be replaced before production use.
- The current session model is MVP-friendly but not cluster-safe.
- Local HTTPS clients trust the DAO self-signed certificate for development.
