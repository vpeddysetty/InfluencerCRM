# Context Contracts
**Date:** 2026-08-02  
**Purpose:** the published surface of each bounded context — what it owns, what it exposes, and what it announces.

This is the document a future service extraction is executed against. For each context it records
the HTTP surface, the tables it owns, the in-process ports other contexts may call, and the domain
events it publishes. Anything **not** listed here is private: no other context may depend on it.

Events marked ✅ are implemented and verified end-to-end; the rest are the planned chain, named now
so the vocabulary is fixed before the code exists.

---

## Identity & Access (`identity`)
**Owns (schema `identity`):** users, accounts, brands, memberships, brand_access

**Published ports:** BrandLookupPort, TokenVerifier (impl)

**Domain events:** BrandCreated, MemberInvited

**HTTP surface** (14 endpoints)

```
GET    /api/auth/oauth/facebook/callback
GET    /api/auth/oauth/facebook/start
GET    /api/auth/oauth/google/callback
GET    /api/auth/oauth/google/start
GET    /api/brands
GET    /api/brands/members
POST   /api/auth/facebook/signup
POST   /api/auth/google/signup
POST   /api/auth/login
POST   /api/auth/logout
POST   /api/auth/refresh
POST   /api/auth/signup
POST   /api/brands
POST   /api/brands/switch
```

## Creator Relationship (`creator`)
**Owns (schema `creator`):** creators, interactions, campaign_creators

**Published ports:** CreatorProvisioningPort

**Domain events:** CreatorImported, CreatorUpdated

**HTTP surface** (10 endpoints)

```
DELETE /api/campaign-creators/{id}
DELETE /api/creators/{id}
GET    /api/campaign-creators
GET    /api/campaign-creators/{id}
GET    /api/creators
GET    /api/creators/{id}
POST   /api/campaign-creators
POST   /api/creators
PUT    /api/campaign-creators/{id}
PUT    /api/creators/{id}
```

## Campaign Management (`campaign`)
**Owns (schema `campaign`):** campaigns, campaign_briefs, import_batches

**Published ports:** —

**Domain events:** CampaignLaunched, ImportHydrated

**HTTP surface** (22 endpoints)

```
DELETE /api/campaign-briefs/{id}
DELETE /api/campaigns/{id}
DELETE /api/import-batches/{id}
GET    /api/campaign-briefs
GET    /api/campaign-briefs/{id}
GET    /api/campaigns
GET    /api/campaigns/{id}
GET    /api/import-batches
GET    /api/import-batches/{id}
GET    /api/import-batches/{id}/columns
PATCH  /api/import-batches/{id}/column-mapping
POST   /api/campaign-briefs
POST   /api/campaigns
POST   /api/content/draft
POST   /api/import-batches/discover
POST   /api/import-batches/discover-multi
POST   /api/import-batches/{id}/agent-column-mapping
POST   /api/import-batches/{id}/delete
POST   /api/import-batches/{id}/hydrate
POST   /api/import-batches/{id}/preview
PUT    /api/campaign-briefs/{id}
PUT    /api/campaigns/{id}
```

## Collaboration Workflow (`workflow`)
**Owns (schema `workflow`):** workflow_boards, workflow_board_stages, workflow_cards

**Published ports:** —

**Domain events:** CardMoved

**HTTP surface** (13 endpoints)

```
DELETE /api/workflow-boards/{id}
DELETE /api/workflow-cards/{id}
GET    /api/workflow-board-stages
GET    /api/workflow-boards
GET    /api/workflow-boards/{id}
GET    /api/workflow-cards
GET    /api/workflow-cards/{id}
POST   /api/workflow-boards
POST   /api/workflow-cards
PUT    /api/workflow-board-stages/replace
PUT    /api/workflow-boards/{id}
PUT    /api/workflow-cards/{id}
PUT    /api/workflow-cards/{id}/placement
```

## Attribution & Commerce (`attribution`)
**Owns (schema `attribution`):** influencer_campaign_codes, influencer_sale_attributions, marketplace_connections, daily_attribution_stats

**Published ports:** —

**Domain events:** SaleAttributed, CouponPushed

**HTTP surface** (25 endpoints)

```
DELETE /api/influencer-campaign-codes/{id}
DELETE /api/influencer-sale-attributions/{id}
DELETE /api/marketplace-connections/{id}
GET    /api/analytics/influencer-revenue
GET    /api/influencer-campaign-codes
GET    /api/influencer-campaign-codes/{id}
GET    /api/influencer-sale-attributions
GET    /api/influencer-sale-attributions/{id}
GET    /api/marketplace-connections
GET    /api/marketplace-connections/{id}
GET    /api/marketplace-providers
POST   /api/attribution/simulate
POST   /api/coupons/generate
POST   /api/coupons/generate-bulk
POST   /api/coupons/{id}/personalization/{decision}
POST   /api/coupons/{id}/personalize
POST   /api/coupons/{id}/push
POST   /api/influencer-campaign-codes
POST   /api/influencer-sale-attributions
POST   /api/marketplace-connections
POST   /api/marketplace-connections/connect
POST   /api/webhooks/marketplace/{providerKey}
PUT    /api/influencer-campaign-codes/{id}
PUT    /api/influencer-sale-attributions/{id}
PUT    /api/marketplace-connections/{id}
```

## Payouts & Finance (`finance`)
**Owns (schema `finance`):** influencer_commissions, influencer_payouts

**Published ports:** —

**Domain events:** CommissionAccrued ✅, CommissionApproved ✅, PayoutRequested, PayoutSettled

**HTTP surface** (14 endpoints)

```
DELETE /api/influencer-commissions/{id}
DELETE /api/influencer-payouts/{id}
GET    /api/daily-attribution-stats
GET    /api/influencer-commissions
GET    /api/influencer-commissions/{id}
GET    /api/influencer-payouts
GET    /api/influencer-payouts/{id}
GET    /api/payout-providers
POST   /api/influencer-commissions
POST   /api/influencer-commissions/{id}/approve
POST   /api/influencer-payouts
POST   /api/influencer-payouts/create
PUT    /api/influencer-commissions/{id}
PUT    /api/influencer-payouts/{id}
```

## Content & Landing (`content`)
**Owns (schema `content`):** landing_templates, landing_page_views

**Published ports:** —

**Domain events:** ContentPublished

**HTTP surface** (5 endpoints)

```
GET    /api/landing-page-views
GET    /api/landing-templates
GET    /s/{slug}/{creator}
POST   /api/landing-templates/preview
POST   /api/landing-templates/save
```

## AI Mapping (`mapping`)
**Owns (schema `mapping`):** mapping_examples

**Published ports:** —

**Domain events:** —

**HTTP surface:** none — reached only through its ports.

---

## Cross-context rules

1. **ID-only references.** A context stores another context's id as a plain UUID. Cross-schema
   foreign keys still exist while this is one database; they are dropped at extraction.
2. **No shared entities.** Ports speak in ids, primitives and attribute maps — never in another
   context's domain type. This is enforced by ArchUnit in both the DAO and the BFF.
3. **Events are past tense.** `CommissionApproved`, not `ApproveCommission`. An event states what
   happened; it never instructs a consumer.
4. **Handlers are idempotent.** The outbox delivers at-least-once, so redelivery must be harmless.
5. **Tenancy travels.** Every event carries `brand_id`; a consumer never infers the tenant.
