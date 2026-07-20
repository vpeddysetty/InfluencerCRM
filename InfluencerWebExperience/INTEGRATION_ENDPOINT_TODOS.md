# Experience Service Integration Endpoint TODOs

Date: 2026-07-19
Purpose: endpoints needed by the React UI for full backend integration.

## Status Legend
- [x] Exposed in Experience service
- [x] Completed in Experience service

## 1) Health and Auth

### Health
- [x] GET /health

### Auth
- [x] POST /api/auth/signup
- [x] POST /api/auth/login
- [x] POST /api/auth/logout
- [x] POST /api/auth/google/signup
- [x] POST /api/auth/facebook/signup
- [x] GET /api/auth/oauth/google/start
- [x] GET /api/auth/oauth/google/callback
- [x] GET /api/auth/oauth/facebook/start
- [x] GET /api/auth/oauth/facebook/callback

Notes:
- Auth endpoints exist already in openapi.yaml and AuthController.

## 2) Campaigns (UI: campaigns page)

- [x] GET /api/campaigns?userId={userId}
- [x] GET /api/campaigns/{campaignId}
- [x] POST /api/campaigns
- [x] PUT /api/campaigns/{campaignId}
- [x] DELETE /api/campaigns/{campaignId}

Minimum response fields expected by current UI:
- id
- name
- budget
- status

## 3) Creators (UI: creators page)

- [x] GET /api/creators?userId={userId}
- [x] GET /api/creators/{creatorId}
- [x] POST /api/creators
- [x] PUT /api/creators/{creatorId}
- [x] DELETE /api/creators/{creatorId}

Minimum response fields expected by current UI:
- id
- name
- handle
- platform
- email

## 4) Campaign-Creator Workflow (UI: workflow page)

- [x] GET /api/campaign-creators?userId={userId}&campaignId={campaignId}&creatorId={creatorId}&stage={stage}
- [x] GET /api/campaign-creators/{id}
- [x] POST /api/campaign-creators
- [x] PUT /api/campaign-creators/{id}
- [x] PATCH /api/campaign-creators/{id}/stage
- [x] DELETE /api/campaign-creators/{id}

Minimum response fields expected by current UI:
- id
- campaignId
- creatorId
- stage
- agreedFee (or fee)
- notes
- contentDueAt (mapped to dueDate)
- tags (string array)

## 5) Import Flow (UI: import page)

- [x] POST /api/import-batches/discover (multipart/form-data: file, userId)
- [x] POST /api/import-batches/{id}/preview
- [x] PATCH /api/import-batches/{id}/column-mapping
- [x] POST /api/import-batches/{id}/hydrate
- [x] GET /api/import-batches/{id}
- [x] GET /api/import-batches?userId={userId}

## 6) Recommended BFF Rules for Experience Service

- [x] Enforce user scoping from auth token (do not trust browser-provided userId blindly).
- [x] Normalize DAO payloads into UI-friendly DTOs.
- [x] Return stable error envelope for all endpoints:
  - errorCode
  - message
  - details (optional)
- [x] Add pagination support for list endpoints when datasets grow.
- [x] Publish these paths in Experience openapi.yaml.

## 7) Suggested Delivery Order

- [x] Phase A: campaigns + creators list/create endpoints.
- [x] Phase B: campaign-creators list/create/update-stage endpoints.
- [x] Phase C: creator-workflow endpoints in Experience layer.
- [x] Phase D: import endpoints in Experience layer.
- [x] Phase E: delete and full update endpoints + pagination.

## 8) Creator-Owner Workflow Endpoints (New)

### Tasks
- [x] GET /api/creator-workflow-tasks?userId={userId}&campaignCreatorId={campaignCreatorId}
- [x] GET /api/creator-workflow-tasks/{id}
- [x] POST /api/creator-workflow-tasks
- [x] PUT /api/creator-workflow-tasks/{id}
- [x] DELETE /api/creator-workflow-tasks/{id}

### Approvals
- [x] GET /api/creator-workflow-approvals?userId={userId}&campaignCreatorId={campaignCreatorId}
- [x] GET /api/creator-workflow-approvals/{id}
- [x] POST /api/creator-workflow-approvals
- [x] PUT /api/creator-workflow-approvals/{id}
- [x] DELETE /api/creator-workflow-approvals/{id}

### Payments
- [x] GET /api/creator-workflow-payments?userId={userId}&campaignCreatorId={campaignCreatorId}
- [x] GET /api/creator-workflow-payments/{id}
- [x] POST /api/creator-workflow-payments
- [x] PUT /api/creator-workflow-payments/{id}
- [x] DELETE /api/creator-workflow-payments/{id}

### Timeline Events
- [x] GET /api/creator-workflow-events?userId={userId}&campaignCreatorId={campaignCreatorId}
- [x] GET /api/creator-workflow-events/{id}
- [x] POST /api/creator-workflow-events
- [x] PUT /api/creator-workflow-events/{id}
- [x] DELETE /api/creator-workflow-events/{id}

Minimum response fields expected by workflow-capable UI:
- id
- userId
- campaignCreatorId
- Tasks: title, assigneeActor, assigneeCreatorId, status, priority, dueAt, completedAt
- Approvals: reviewRound, submissionUrl, submittedByActor, submittedAt, decision, decidedByActor, decidedAt
- Payments: amount, currency, status, scheduledAt, paidAt, failedAt
- Events: actor, eventType, eventBody, eventData, createdAt

## 9) Integration Mapping (Experience -> DAO)

DAO layer is now available for workflow resources and supports list filters by userId and campaignCreatorId.

DAO endpoints available for wiring:
- [x] /creator-workflow-tasks
- [x] /creator-workflow-approvals
- [x] /creator-workflow-payments
- [x] /creator-workflow-events

Experience integration TODOs:
- [x] Add workflow clients in Experience service for each DAO resource.
- [x] Pass through list filter params (userId, campaignCreatorId) from Experience to DAO.
- [x] Derive userId from auth token in Experience before DAO calls.
- [x] Map DAO payloads to Experience DTOs (do not expose internal-only fields by default).
- [x] Add error translation from DAO errors to Experience error envelope.
- [x] Add OpenAPI paths/schemas for all workflow endpoints in Experience.

## 10) Experience Layer Missing Integrations (Consolidated)

- [x] Campaigns integration to DAO
- [x] Creators integration to DAO
- [x] Campaign-creators integration to DAO
- [x] Import-batches integration to DAO
- [x] Creator-workflow tasks integration to DAO
- [x] Creator-workflow approvals integration to DAO
- [x] Creator-workflow payments integration to DAO
- [x] Creator-workflow events integration to DAO

## 11) Influencer Campaign Code + Attribution Tracking (New)

### Campaign code management
- [x] GET /api/influencer-campaign-codes?userId={userId}&campaignId={campaignId}&creatorId={creatorId}
- [x] GET /api/influencer-campaign-codes/{id}
- [x] POST /api/influencer-campaign-codes
- [x] PUT /api/influencer-campaign-codes/{id}
- [x] DELETE /api/influencer-campaign-codes/{id}

Minimum response fields:
- id
- userId
- campaignId
- creatorId
- campaignCreatorId
- code
- codeType
- landingUrl
- startsAt
- endsAt
- isActive

### Sale attribution tracking
- [x] GET /api/influencer-sale-attributions?userId={userId}&campaignCodeId={campaignCodeId}&campaignCreatorId={campaignCreatorId}
- [x] GET /api/influencer-sale-attributions/{id}
- [x] POST /api/influencer-sale-attributions
- [x] PUT /api/influencer-sale-attributions/{id}
- [x] DELETE /api/influencer-sale-attributions/{id}

Minimum response fields:
- id
- userId
- campaignCodeId
- campaignId
- creatorId
- campaignCreatorId
- platform
- status
- orderId
- orderLineId
- saleAmount
- discountAmount
- netAmount
- commissionAmount
- currency
- occurredAt
- trackedAt

## 12) DAO Integration Mapping for New Tracking Module

DAO endpoints available for wiring:
- [x] /influencer-campaign-codes
- [x] /influencer-sale-attributions

Experience integration TODOs:
- [x] Add experience client for /influencer-campaign-codes DAO endpoints.
- [x] Add experience client for /influencer-sale-attributions DAO endpoints.
- [x] Validate campaign-code uniqueness errors and return friendly conflict response.
- [x] Normalize attribution platform/status strings in Experience DTOs.
- [x] Add OpenAPI paths/schemas for both resources in Experience service.
- [x] Enforce tenant scoping from auth token before forwarding to DAO.
