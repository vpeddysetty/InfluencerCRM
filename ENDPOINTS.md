# Influencer CRM Endpoints

This file lists API endpoints discovered from controller and route definitions in this repository.

## InfluencerWebExperience (BFF)

Base module: InfluencerWebExperience

### Health
- GET /health

### Auth
- POST /api/auth/signup — body `{ email, password, brandName, accountType? }`.
  `accountType` is `brand` (default) or `agency`; any other value, or an unrecognised
  field, is a 400 rather than being silently ignored.
- POST /api/auth/login
- POST /api/auth/logout
- POST /api/auth/google/signup
- POST /api/auth/facebook/signup
- GET /api/auth/oauth/google/start
- GET /api/auth/oauth/google/callback
- GET /api/auth/oauth/facebook/start
- GET /api/auth/oauth/facebook/callback

### Account members & invitations
- GET /api/brands/members
- POST /api/brands/members/invite — `{ email, role, brandId? }`; returns the one-time token.
  `OWNER` cannot be granted this way. Only its hash is stored.
- GET /api/brands/members/invitations
- POST /api/brands/members/invitations/accept — `{ token }`, as the signed-in user
- POST /api/brands/members/invitations/{id}/revoke
- PUT /api/brands/members/{userId} — change role (not your own)
- DELETE /api/brands/members/{userId}

### Creator portal
Creator routes authenticate with `X-Creator-Token`, never the operator JWT — a creator has no
account, brand or role. Brand-side routes use the normal bearer token.
- POST /api/creator-portal/auth/signup
- POST /api/creator-portal/auth/login
- POST /api/creator-portal/auth/logout
- GET /api/creator-portal/me
- GET /api/creator-portal/collaborations — every brand record confirmed as this creator
- POST /api/creator-portal/claims — assert a brand's creator record is you (unverified)
- GET /api/creator-portal/claims
- GET /api/creator-portal/pending-claims — brand side; claims awaiting a decision
- POST /api/creator-portal/claims/{linkId}/{approve|reject} — brand side
- POST /api/creator-portal/invite — brand side; links a creator as confirmed

### Campaigns
- GET /api/campaigns
- GET /api/campaigns/{id}
- POST /api/campaigns
- PUT /api/campaigns/{id}
- DELETE /api/campaigns/{id}

### Creators
- GET /api/creators
- GET /api/creators/{id}
- POST /api/creators
- PUT /api/creators/{id}
- DELETE /api/creators/{id}

### Campaign Creators
- GET /api/campaign-creators
- GET /api/campaign-creators/{id}
- POST /api/campaign-creators
- PUT /api/campaign-creators/{id}
- DELETE /api/campaign-creators/{id}

### Import Batches
- GET /api/import-batches
- GET /api/import-batches/{id}
- GET /api/import-batches/{id}/columns
- POST /api/import-batches/{id}/agent-column-mapping
- POST /api/import-batches/discover
- POST /api/import-batches/discover-multi
- POST /api/import-batches/{id}/preview
- PATCH /api/import-batches/{id}/column-mapping
- POST /api/import-batches/{id}/hydrate
- DELETE /api/import-batches/{id}
- POST /api/import-batches/{id}/delete

### Workflow Boards
- GET /api/workflow-boards
- GET /api/workflow-boards/{id}
- POST /api/workflow-boards
- PUT /api/workflow-boards/{id}
- DELETE /api/workflow-boards/{id}
- GET /api/workflow-board-stages
- PUT /api/workflow-board-stages/replace
- GET /api/workflow-cards
- GET /api/workflow-cards/{id}
- POST /api/workflow-cards
- PUT /api/workflow-cards/{id}
- PUT /api/workflow-cards/{id}/placement
- DELETE /api/workflow-cards/{id}

### Influencer Tracking
- GET /api/influencer-campaign-codes
- GET /api/influencer-campaign-codes/{id}
- POST /api/influencer-campaign-codes
- PUT /api/influencer-campaign-codes/{id}
- DELETE /api/influencer-campaign-codes/{id}
- GET /api/influencer-sale-attributions
- GET /api/influencer-sale-attributions/{id}
- POST /api/influencer-sale-attributions
- PUT /api/influencer-sale-attributions/{id}
- DELETE /api/influencer-sale-attributions/{id}

## InfluencerDAO

Base module: InfluencerDAO

### Users
- GET /users
- GET /users/by-email
- GET /users/{id}
- POST /users
- PUT /users/{id}
- DELETE /users/{id}

### Tenancy
- GET /tenancy/users/{userId}/brands
- GET /tenancy/users/{userId}/account — the account provisioned for a user, via `legacy_user_id`
- GET /tenancy/brands/{id}
- GET /tenancy/accounts/{accountId}/brands
- GET /tenancy/accounts/{accountId}/members
- POST /tenancy/provision — account + brand + membership in one transaction; idempotent
- POST /tenancy/brands
- PUT /tenancy/brands/{id}
- PATCH /tenancy/accounts/{id} — sets `accountType` (`brand` | `agency`) and/or `name`
- PUT /tenancy/accounts/{accountId}/members/{userId} — change role
- DELETE /tenancy/accounts/{accountId}/members/{userId}
- POST /tenancy/accounts/{accountId}/invitations
- GET /tenancy/accounts/{accountId}/invitations
- GET /tenancy/invitations/by-token/{tokenHash}
- POST /tenancy/invitations/{id}/accept
- POST /tenancy/invitations/{id}/revoke

### Creator identities
- POST /creator-identities
- GET /creator-identities/by-email
- GET /creator-identities/{id}
- POST /creator-identities/{identityId}/links
- GET /creator-identities/{identityId}/links
- GET /creator-identities/links/pending
- POST /creator-identities/links/{linkId}/decision

### Campaigns
- GET /campaigns
- GET /campaigns/{id}
- POST /campaigns
- PUT /campaigns/{id}
- DELETE /campaigns/{id}

### Creators
- GET /creators
- GET /creators/{id}
- POST /creators
- PUT /creators/{id}
- DELETE /creators/{id}

### Campaign Creators
- GET /campaign-creators
- GET /campaign-creators/{id}
- POST /campaign-creators
- PUT /campaign-creators/{id}
- DELETE /campaign-creators/{id}

### Import Batches
- GET /import-batches
- GET /import-batches?userId={userId}
- GET /import-batches/{id}
- GET /import-batches/{id}/columns
- POST /import-batches
- POST /import-batches/discover
- POST /import-batches/discover-multi
- PUT /import-batches/{id}
- PATCH /import-batches/{id}/column-mapping
- POST /import-batches/{id}/hydrate
- POST /import-batches/{id}/preview
- DELETE /import-batches/{id}

### Mapping Examples
- GET /mapping-examples
- GET /mapping-examples/{id}
- POST /mapping-examples
- PUT /mapping-examples/{id}
- DELETE /mapping-examples/{id}

### Interactions
- GET /interactions
- GET /interactions/{id}
- POST /interactions
- PUT /interactions/{id}
- DELETE /interactions/{id}

### Workflow Boards
- GET /workflow-boards
- GET /workflow-boards/{id}
- POST /workflow-boards
- PUT /workflow-boards/{id}
- DELETE /workflow-boards/{id}
- GET /workflow-board-stages
- POST /workflow-board-stages
- PUT /workflow-board-stages/{id}
- PUT /workflow-board-stages/replace
- DELETE /workflow-board-stages/{id}
- GET /workflow-cards
- GET /workflow-cards/{id}
- POST /workflow-cards
- PUT /workflow-cards/{id}
- PUT /workflow-cards/{id}/placement
- DELETE /workflow-cards/{id}

### Influencer Attribution
- GET /influencer-campaign-codes
- GET /influencer-campaign-codes/{id}
- POST /influencer-campaign-codes
- PUT /influencer-campaign-codes/{id}
- DELETE /influencer-campaign-codes/{id}
- GET /influencer-sale-attributions
- GET /influencer-sale-attributions/{id}
- POST /influencer-sale-attributions
- PUT /influencer-sale-attributions/{id}
- DELETE /influencer-sale-attributions/{id}

## Agent Service (FastAPI)

Base module: agent_service

- GET /health
- GET /mappings/examples
- POST /mappings/review
- POST /mappings/approve
- POST /map-columns
- POST /map-upload
