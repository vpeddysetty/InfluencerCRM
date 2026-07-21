# Influencer CRM Endpoints

This file lists API endpoints discovered from controller and route definitions in this repository.

## InfluencerWebExperience (BFF)

Base module: InfluencerWebExperience

### Health
- GET /health

### Auth
- POST /api/auth/signup
- POST /api/auth/login
- POST /api/auth/logout
- POST /api/auth/google/signup
- POST /api/auth/facebook/signup
- GET /api/auth/oauth/google/start
- GET /api/auth/oauth/google/callback
- GET /api/auth/oauth/facebook/start
- GET /api/auth/oauth/facebook/callback

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
- PATCH /api/campaign-creators/{id}/stage
- DELETE /api/campaign-creators/{id}

### Import Batches
- GET /api/import-batches
- GET /api/import-batches/{id}
- POST /api/import-batches/{id}/agent-column-mapping
- POST /api/import-batches/discover
- POST /api/import-batches/{id}/preview
- PATCH /api/import-batches/{id}/column-mapping
- POST /api/import-batches/{id}/hydrate

### Creator Workflow
- GET /api/creator-workflow-tasks
- GET /api/creator-workflow-tasks/{id}
- POST /api/creator-workflow-tasks
- PUT /api/creator-workflow-tasks/{id}
- DELETE /api/creator-workflow-tasks/{id}
- GET /api/creator-workflow-approvals
- GET /api/creator-workflow-approvals/{id}
- POST /api/creator-workflow-approvals
- PUT /api/creator-workflow-approvals/{id}
- DELETE /api/creator-workflow-approvals/{id}
- GET /api/creator-workflow-payments
- GET /api/creator-workflow-payments/{id}
- POST /api/creator-workflow-payments
- PUT /api/creator-workflow-payments/{id}
- DELETE /api/creator-workflow-payments/{id}
- GET /api/creator-workflow-events
- GET /api/creator-workflow-events/{id}
- POST /api/creator-workflow-events
- PUT /api/creator-workflow-events/{id}
- DELETE /api/creator-workflow-events/{id}

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
- GET /import-batches/{id}
- GET /import-batches/{id}/columns
- POST /import-batches
- POST /import-batches/discover
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

### Creator Workflow
- GET /creator-workflow-tasks
- GET /creator-workflow-tasks/{id}
- POST /creator-workflow-tasks
- PUT /creator-workflow-tasks/{id}
- DELETE /creator-workflow-tasks/{id}
- GET /creator-workflow-approvals
- GET /creator-workflow-approvals/{id}
- POST /creator-workflow-approvals
- PUT /creator-workflow-approvals/{id}
- DELETE /creator-workflow-approvals/{id}
- GET /creator-workflow-payments
- GET /creator-workflow-payments/{id}
- POST /creator-workflow-payments
- PUT /creator-workflow-payments/{id}
- DELETE /creator-workflow-payments/{id}
- GET /creator-workflow-events
- GET /creator-workflow-events/{id}
- POST /creator-workflow-events
- PUT /creator-workflow-events/{id}
- DELETE /creator-workflow-events/{id}

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
