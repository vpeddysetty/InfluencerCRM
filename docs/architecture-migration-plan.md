# Architecture Migration Plan
## From single-brand monolith → multi-tenant, role-based, domain-driven micro-apps

**Status:** Phases 0–4 delivered (2026-08-02); Phases 5–6 gated at the stop-and-reassess point.
See [ddd-roadmap.md](ddd-roadmap.md) for per-phase completion records and live verification.
**Date:** 2026-08-01
**Scope:** tenancy model change (single brand → agency/multi-brand), RBAC introduction, and decomposition into per-context codebases (UI + BFF + product service).

---

## 1. Executive summary

Three changes are being requested at once, and they have a strict dependency order:

| # | Change | Depends on | Reversible? |
|---|---|---|---|
| A | **Tenancy**: single-brand → agency managing many brands | nothing | hard once data exists |
| B | **RBAC**: multiple marketers with differing access levels | A (roles are scoped *to* a brand) | medium |
| C | **Decomposition**: per-context UI/BFF/service codebases | A and B | easy per-context |

**A and B must land before C.** Splitting first means implementing the same tenancy and
authorization model N times, in N codebases, with N chances to get it wrong — and the current
codebase has *zero* authorization enforcement to inherit. Every service you extract before the
security model exists is a service you will rewrite.

The plan below is 7 phases. Phases 0–3 are non-negotiable prerequisites and deliver most of
the value. Phases 4–6 are the actual decomposition and are individually optional/deferrable.

---

## 2. Current-state findings

Evidence gathered from the codebase on 2026-08-01.

### 2.1 There is no authorization layer at all

```
grep -rn "PreAuthorize|hasRole|hasAuthority|SecurityFilterChain|@Secured"
  InfluencerDAO/src/main/java InfluencerWebExperience/src/main/java
→ 0 results
```

Neither Spring Boot app has Spring Security on the classpath
([InfluencerDAO/pom.xml](../InfluencerDAO/pom.xml), [InfluencerWebExperience/pom.xml](../InfluencerWebExperience/pom.xml)
— only `spring-security-crypto` for BCrypt). There is nothing to extend; RBAC is greenfield.

### 2.2 Tenancy is advisory, not enforced

[`RequestUserResolver.resolveUserId()`](../InfluencerWebExperience/src/main/java/com/influencer/webe/service/RequestUserResolver.java#L16-L29)
falls back to a **caller-supplied** `userId` when no bearer token is present:

```java
if (explicitUserId != null) {
    return explicitUserId;   // ← any caller can claim any tenant
}
```

The DAO tier is worse: it is reachable directly and trusts `userId` from the query string or body
unconditionally. 224 `userId` references in DAO, 214 in the BFF.

### 2.3 Sessions are in-memory and will not survive decomposition

[`SessionService`](../InfluencerWebExperience/src/main/java/com/influencer/webe/service/SessionService.java#L15)
stores sessions in a `ConcurrentHashMap`. Tokens are opaque `UUID.randomUUID()` values with no
claims. A second BFF instance — let alone six services — cannot validate them. **This is the single
hardest blocker to decomposition** and must be replaced with signed JWTs.

### 2.4 `role` already exists but is vestigial

`schema/influencer_crm_schema.sql:13` declares `create type user_role as enum ('owner', 'marketer')`
and `users.role` defaults to `'owner'`. But:

- [`AuthService`](../InfluencerWebExperience/src/main/java/com/influencer/webe/service/AuthService.java#L45)
  hardcodes `"owner"` on every signup — password *and* both social paths (lines 45, 100).
- The only reads are `getRole()`/`setRole()` passthroughs in `UserController` and `User`.
- It is returned in `AuthResponse` and **never checked anywhere**.

Good news: the column exists and is populated. Bad news: it is the wrong shape — role is global
per user, but under an agency model role must be **per (user, brand)** pair.

### 2.5 The data model hard-codes one-brand-per-user

`schema/influencer_crm_schema.sql:3` states it plainly: *"Single-tenant-per-account model: every
table hangs off `users`."* All 18 non-user tables carry `user_id uuid not null references users(id)`.
The brand identity is a single nullable `users.brand_name text` column.

**Two unique constraints break under multi-brand and are the sharpest data-migration risk:**

| Constraint | File | Breaks because |
|---|---|---|
| `creators unique (user_id, platform, handle)` | `influencer_crm_schema.sql:74` | Two brands in one agency legitimately work with the same creator |
| `influencer_campaign_codes unique (user_id, code)` | `:285` + migration `2026_07_19:76` | Codes must be unique per *brand*, not per agency user |

Also affected: `uq_das_grain unique (user_id, day, creator_id, campaign_id, channel)`
(`2026_07_27:166`) — the daily stats grain silently collapses across brands.

Not affected (already brand-agnostic): `unique (campaign_id, creator_id)`,
`uq_campaign_briefs_campaign`, `uq_landing_templates_campaign`, `uq_landing_templates_slug`.

### 2.6 Repository layer is uniformly user-keyed

35 `findBy…UserId…` methods across 19 repositories (12 plain `findByUserId`, plus 23 compound
variants). This is mechanically good news — the tenancy key is applied consistently, so swapping
`userId` → `brandId` is a wide but shallow, largely mechanical change.

### 2.7 UI holds a flat single-tenant session

[`App.jsx`](../InfluencerUI/src/App.jsx#L278-L331) is 1512 lines with ~40 `useState` hooks, and
persists `{ authToken, userId, brandName, … }` plus most domain collections into a single
`localStorage` blob. There is no notion of "current brand", no role, and no permission gating on
any route or control. All 10 pages read from this shared state.

### 2.8 Layer-split, not domain-split

The DAO exposes one CRUD controller per table (19 controllers / 19 repos / 2 services). The BFF
orchestrates by chaining table calls and re-shapes leaked rows field-by-field in
[`ResponseShapeService`](../InfluencerWebExperience/src/main/java/com/influencer/webe/service/ResponseShapeService.java)
(456 lines). ~40 DAO URL paths are hardcoded in the BFF. Business logic that belongs in a domain
sits in BFF services (`AttributionService`, `CouponService`, `PayoutService`, `LandingService`).

### 2.9 Transport security is disabled

[`DaoGatewayClient.buildHttpClient()`](../InfluencerWebExperience/src/main/java/com/influencer/webe/client/DaoGatewayClient.java#L243-L268)
installs a trust-all `X509TrustManager`. Under decomposition this becomes N× service-to-service
hops with no certificate validation. Must be fixed before, not after.

---

## 3. Target tenancy model

### 3.1 Core concept: `Account` → `Brand` → `Membership`

```
Account  (the paying entity: a solo brand OR an agency)
  │  account_type: 'brand' | 'agency'
  │
  ├── Brand  (a managed brand; solo accounts have exactly 1, agencies have many)
  │     └── all 18 domain tables hang off brand_id
  │
  └── Membership  (user ↔ account, carries the account-level role)
        └── BrandAccess  (membership ↔ brand, carries the per-brand role)
```

A solo brand user is simply an `Account` of type `'brand'` with one `Brand` and one `Membership`
of role `OWNER`. **This is the key simplification: there is one code path, not two.** The single-brand
product is the degenerate case of the agency product, so no feature is written twice.

### 3.2 New tables

```sql
create table accounts (
    id           uuid primary key default gen_random_uuid(),
    name         text not null,
    account_type text not null check (account_type in ('brand','agency')),
    plan         text not null default 'free',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create table brands (
    id           uuid primary key default gen_random_uuid(),
    account_id   uuid not null references accounts(id) on delete cascade,
    name         text not null,
    status       text not null default 'active',
    custom_attributes jsonb not null default '{}'::jsonb,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    unique (account_id, name)
);

create type account_role as enum ('OWNER','ADMIN','MANAGER','MARKETER','ANALYST','FINANCE');

create table memberships (
    id           uuid primary key default gen_random_uuid(),
    account_id   uuid not null references accounts(id) on delete cascade,
    user_id      uuid not null references users(id)    on delete cascade,
    role         account_role not null,
    status       text not null default 'active',
    created_at   timestamptz not null default now(),
    unique (account_id, user_id)
);

-- Scopes a member to specific brands. A membership with NO rows here and role in
-- (OWNER, ADMIN) implicitly has access to ALL brands in the account.
create table brand_access (
    id            uuid primary key default gen_random_uuid(),
    membership_id uuid not null references memberships(id) on delete cascade,
    brand_id      uuid not null references brands(id)      on delete cascade,
    role          account_role not null,
    created_at    timestamptz not null default now(),
    unique (membership_id, brand_id)
);
```

`users` keeps `id`, `email`, `password_hash`, `custom_attributes`. Drop `brand_name`, `role`, and
`plan` from `users` — they move to `brands.name`, `memberships.role`, and `accounts.plan`.

### 3.3 The tenancy key change

Every one of the 18 domain tables changes `user_id` → `brand_id`:

```
creators, campaigns, campaign_creators, interactions, import_batches,
workflow_boards, workflow_board_stages, workflow_cards,
influencer_campaign_codes, influencer_sale_attributions,
influencer_commissions, influencer_payouts, daily_attribution_stats,
marketplace_connections, campaign_briefs, landing_templates,
landing_page_views, mapping_examples
```

Keep a nullable `created_by_user_id` for audit — you lose attribution otherwise, and agencies will
ask "who added this creator?" within the first week.

### 3.4 Decision: creator identity is per-brand (two rows, not one)

**Decision.** When two brands managed by the same agency both work with the same creator
(`platform='instagram', handle='@someone'`), that is **two independent `creators` rows**, one per
brand. The constraint becomes `unique (brand_id, platform, handle)`.

**Context.** `schema/influencer_crm_schema.sql:74` currently declares
`unique (user_id, platform, handle)` with the comment *"a user can't have the same handle twice on
the same platform"*. Under the agency model, `user_id` becomes `brand_id`, and the question is
whether the uniqueness scope should widen to the account (one shared creator record per agency) or
stay at the brand (one record per brand).

**Rationale for per-brand rows:**

1. **Most creator fields are relationship data, not identity data.** `preferred_rate`,
   `minimum_fee`, `brand_safety_score`, `safety_notes`, `tags`, `custom_attributes`, `source`, and
   `last_active_at` describe *this brand's* relationship with the creator. A shared row forces one
   brand's negotiated rate and safety assessment onto every other brand in the agency. Brand A
   paying $5k and Brand B paying $2k for the same creator is normal and must be representable.
2. **Confidentiality between an agency's clients.** Two competing brands under one agency must not
   see each other's rates, notes, or safety scores. A shared row makes leakage the default and
   requires per-field ACLs to prevent it — far more complex than duplication.
3. **It preserves the tenancy invariant.** Every domain table hangs off exactly one `brand_id`, so
   the repository-level tenant filter (Phase 0.6) is uniform with no exceptions. A shared
   account-level `creators` table would be the *only* table needing a different rule, and exceptions
   to a security invariant are where leaks live.
4. **Migration is a no-op.** Each existing user maps to exactly one brand, so
   `unique (user_id, …)` → `unique (brand_id, …)` produces zero collisions and requires no merge
   decisions or data loss. The account-wide alternative would require deduplicating existing rows
   with conflicting rates and notes — irreversible, and unresolvable without asking the user.
5. **Brands leave agencies.** Off-boarding a brand is `delete from brands where id = ?` with its
   creator rows cascading cleanly. With shared rows, off-boarding requires untangling which
   relationship data belongs to the departing brand.

**Consequences and what we give up:**

- **Duplicate storage.** Acceptable — creator rows are small and counts are in the thousands.
- **No cross-brand roll-up out of the box.** "Which of our brands work with @someone?" and
  "what has this creator earned across the agency?" cannot be answered by a single query.
- **No shared updates.** A creator changing their handle must be updated per brand.

**Mitigation — the `creator_identity` read model (deferred, not now).** Cross-brand questions are a
*reporting* concern, not a constraint concern. When the need is real, add an account-scoped
projection built from `CreatorImported` / `CreatorUpdated` events:

```sql
create table creator_identity (          -- account-scoped projection, NOT a source of truth
    id           uuid primary key default gen_random_uuid(),
    account_id   uuid not null references accounts(id) on delete cascade,
    platform     text not null,
    handle       text not null,
    brand_ids    uuid[] not null default '{}',   -- which brands hold a row for this creator
    unique (account_id, platform, handle)
);
```

This gives agency-wide visibility **without** merging the confidential per-brand relationship data,
and it can be introduced at any later point because it is derived, never written to directly.
Explicitly out of scope for Phases 0–3.

**Same reasoning applies to `influencer_campaign_codes`.** Codes are brand-facing artifacts pushed
to a brand's own storefront, so `unique (brand_id, code)` is correct: two brands may independently
issue the code `SUMMER20`.

---

## 4. Target role model

### 4.1 Roles

| Role | Scope | Intent |
|---|---|---|
| `OWNER` | Account | Billing, delete account, manage admins. Exactly one required per account. |
| `ADMIN` | Account | Manage brands, invite/remove members, all brand data. |
| `MANAGER` | Brand | Full control of assigned brands incl. approving payouts. |
| `MARKETER` | Brand | Day-to-day: campaigns, creators, outreach, content. No financial approval. |
| `ANALYST` | Brand | Read-only + exports. The safe default for contractors. |
| `FINANCE` | Account | Commissions and payouts across all brands. No campaign/creator edit. |

### 4.2 Permissions

Do **not** check roles at call sites — check permissions, and map roles→permissions in one place.
Role checks scattered through controllers is exactly the mistake that makes RBAC unchangeable later.

```
creator:read|write|delete       campaign:read|write|delete
campaign_creator:assign         workflow:read|write
coupon:read|write|push          attribution:read
commission:read|approve         payout:read|create|approve
marketplace:connect             content:read|write|publish
import:execute|undo             brand:read|create|update|delete
member:invite|update|remove     account:billing
```

| | OWNER | ADMIN | MANAGER | MARKETER | ANALYST | FINANCE |
|---|---|---|---|---|---|---|
| creator:write | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| campaign:write | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| import:execute | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| coupon:push | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| commission:approve | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| payout:create | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ |
| payout:approve | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ |
| member:invite | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| brand:create | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| account:billing | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| *:read | ✅ | ✅ | ✅ | ✅ | ✅ | scoped |

**Separation of duties note:** `MANAGER` can approve commissions but cannot create or approve
payouts; `FINANCE` can do both but cannot edit campaign data. This is deliberate — it is the control
agencies get audited on. Do not collapse these to simplify.

### 4.3 The authorization primitive

Every request resolves to a `SecurityContext`:

```java
record TenantContext(
    UUID userId,
    UUID accountId,
    UUID brandId,          // the ACTIVE brand for this request
    AccountRole role,      // effective role for (user, brandId)
    Set<Permission> permissions,
    Set<UUID> accessibleBrandIds
) {}
```

Resolved **once**, in a filter, from a signed JWT + the `X-Brand-Id` header. Never from a request
body. Every repository call takes `brandId` from this context, never from user input.

---

## 5. Target service decomposition

Seven contexts, each a separate codebase with its own UI remote, BFF slice, and product service.

| Context | Owns tables | Extract order |
|---|---|---|
| **Identity & Access** | accounts, brands, users, memberships, brand_access | 1st (foundation) |
| **Collaboration Workflow** | workflow_boards, workflow_board_stages, workflow_cards | 2nd (pilot) |
| **Creator Relationship** | creators, interactions, campaign_creators | 3rd |
| **Campaign Management** | campaigns, campaign_briefs, import_batches | 4th |
| **Attribution & Commerce** | influencer_campaign_codes, influencer_sale_attributions, marketplace_connections, daily_attribution_stats | 5th |
| **Payouts & Finance** | influencer_commissions, influencer_payouts | 6th |
| **Content & Landing** | landing_templates, landing_page_views | 7th |
| **AI Mapping** | mapping_examples | already separate (`agent_service`) |

### 5.1 Per-context repository layout

```
influencrm-<context>/
├── ui/                     # React remote (Vite Module Federation)
│   ├── src/pages/
│   ├── src/api/            # this context's API client slice only
│   └── vite.config.js      # exposes ./Routes
├── service/                # Spring Boot product service
│   ├── domain/             # aggregates, value objects, domain events — no framework imports
│   ├── application/        # use cases, permission checks, tx boundaries
│   ├── infrastructure/     # JPA repos, HTTP clients, event publishers
│   └── api/                # REST controllers (behavioral, not CRUD)
└── contract/               # OpenAPI spec + published event schemas
```

The BFF does **not** get one codebase per context. Keep **one gateway** that owns auth verification,
brand-context resolution, routing, and cross-context read composition. Splitting the BFF per context
would force the UI shell to talk to N origins and re-implement auth N times — a common and expensive
mistake. Per-context *modules* inside one gateway deployable.

### 5.2 Cross-context rules

- Cross-context references are **ID-only UUIDs, no FK**. `workflow_cards.creator_id` becomes a plain
  uuid column with no `references creators(id)`.
- Each context replicates the 2–3 fields it displays from other contexts into a local read model,
  updated by events.
- Postgres **schema-per-context** in one instance (`identity.`, `campaign.`, `attribution.`…) with
  per-service credentials that can only reach their own schema. Enforces boundaries without the
  operational cost of N databases. Physical split later, if ever.

### 5.3 Events

The attribution→commission→payout chain is currently synchronous BFF orchestration and should be
event-driven:

```
SaleAttributed → CommissionAccrued → PayoutRequested → PayoutApproved → PayoutSettled
BrandCreated / MemberInvited / BrandAccessGranted / BrandArchived
CreatorImported / CampaignLaunched
```

Start with a transactional **outbox table + polling publisher**. Do not introduce Kafka until
volume demands it — the outbox pattern is what makes the eventual broker swap a config change.

---

## 6. Sequencing

Each phase ships independently and leaves the app working. Do not start a phase before its
predecessor is in production.

---

### Phase 0 — Security floor *(blocking; no feature work in parallel)*

The current system cannot be safely multi-tenanted, let alone decomposed, until these land.
Under a single-brand model these are bugs; under an agency model they are cross-brand data leaks.

1. **Replace opaque in-memory sessions with signed JWTs.**
   Rewrite [`SessionService`](../InfluencerWebExperience/src/main/java/com/influencer/webe/service/SessionService.java)
   to issue RS256 JWTs with claims `sub`, `accountId`, `brandId`, `role`, `perms`, `exp`.
   Add refresh tokens in Postgres (revocable). This is the prerequisite for *every* later phase.
2. **Delete the `explicitUserId` fallback** in
   [`RequestUserResolver:25-27`](../InfluencerWebExperience/src/main/java/com/influencer/webe/service/RequestUserResolver.java#L25-L27).
   Tenancy comes from the token only. Expect this to break callers — that is the point.
3. **Add Spring Security to both apps.** BFF validates user JWTs; DAO accepts only a
   service-to-service credential (mTLS or a signed internal token) and **rejects all direct
   external traffic**. Today the DAO is an open, unauthenticated CRUD API over the whole database.
4. **Fix trust-all TLS** in
   [`DaoGatewayClient:243-268`](../InfluencerWebExperience/src/main/java/com/influencer/webe/client/DaoGatewayClient.java#L243-L268).
5. **Rotate the committed keystore** and purge it from git history.
6. **Enforce tenancy in the repository layer** — a JPA `@Filter` or Hibernate interceptor that
   appends the tenant predicate automatically, so a forgotten `findByUserId` cannot leak.

**Exit criteria:** no endpoint returns data without a valid token; DAO unreachable from outside;
an automated test proves user A cannot read user B's data by any parameter manipulation.

---

### Phase 1 — Data model: accounts, brands, memberships

1. Create `accounts`, `brands`, `memberships`, `brand_access` (§3.2).
2. **Backfill migration** — for each existing user:
   ```sql
   -- 1 account (type='brand') + 1 brand (name = users.brand_name) + 1 membership (role='OWNER')
   ```
3. Add nullable `brand_id` to all 18 domain tables; backfill from the user's new brand; then
   `set not null` and add indexes on `(brand_id, …)` mirroring every existing `(user_id, …)` index.
4. **Rewrite the three broken unique constraints** (§2.5):
   - `creators`: `unique (user_id, platform, handle)` → `unique (brand_id, platform, handle)`
   - `influencer_campaign_codes`: `unique (user_id, code)` → `unique (brand_id, code)`
   - `daily_attribution_stats`: `uq_das_grain` → keyed on `brand_id`
5. Keep `user_id` columns in place, dual-written, for one release. **Do not drop them in this phase** —
   they are the rollback path.
6. Add `created_by_user_id` for audit.

**Exit criteria:** every domain row has a valid `brand_id`; row counts per brand match prior counts
per user; app still runs against `user_id`.

---

### Phase 2 — Switch the runtime to brand tenancy

1. Rewrite all 35 `findBy…UserId…` repository methods → `findBy…BrandId…`. Mechanical but wide;
   do it in one PR per context package to keep review tractable.
2. Resolve `brandId` in the BFF filter from JWT + `X-Brand-Id` header; validate the caller actually
   has access to that brand; inject into `TenantContext`.
3. Propagate `brandId` (not `userId`) to the DAO on every call.
4. UI: add a **brand switcher** in the shell; store `activeBrandId`; send `X-Brand-Id` on every
   request from [`api.js`](../InfluencerUI/src/api.js). Solo accounts get a switcher with one
   entry — hide the control, keep the code path.
5. Clear/migrate the `localStorage` blob in [`App.jsx`](../InfluencerUI/src/App.jsx#L99) — its shape
   changes and stale state will produce confusing cross-brand bugs. Version the storage key.
6. Drop `user_id` columns and `users.brand_name` / `users.role` / `users.plan` **only after** this
   phase is stable in production.

**Exit criteria:** an agency account with 2 brands shows correctly isolated data on switch; no
endpoint accepts `userId` as a tenancy parameter.

---

### Phase 3 — RBAC enforcement

1. Implement `Permission` enum + role→permission matrix (§4.2) in one shared module.
2. Add `@RequiresPermission("payout:approve")` and enforce in the application layer — **not** in
   controllers, so the check survives the service split.
3. Member management: invite by email, assign account role, grant per-brand access.
   New endpoints in Identity context.
4. **UI permission gating** — hide/disable controls by permission, and guard routes. Server-side
   checks remain authoritative; UI gating is UX only.
5. Add `AuthorizationDeniedEvent` audit logging. Agencies will need this for their own clients.
6. Backfill: every existing membership → `OWNER`.

**Exit criteria:** matrix in §4.2 verified by an automated test per role; an `ANALYST` cannot mutate
anything via direct API calls.

---

### Phase 4 — Modular monolith (in-place DDD restructure)

**No new deployables in this phase.** Reorganize inside the existing repos:

```
com.influencer.<context>.domain
com.influencer.<context>.application
com.influencer.<context>.infrastructure
com.influencer.<context>.api
```

1. Move business logic out of BFF services (`AttributionService`, `CouponService`, `PayoutService`,
   `LandingService`) into the owning context's application layer.
2. Convert anemic models into aggregates with invariants (e.g. `Campaign` enforces its own budget
   and status transitions rather than a controller doing it).
3. Replace CRUD endpoints with behavioral ones: `PUT /influencer-campaign-codes/{id}` →
   `POST /codes/{id}/redeem`.
4. Introduce Postgres schema-per-context; move tables; **no cross-schema FKs** between contexts.
5. Add the outbox table and publish the first domain events in-process.
6. Enforce module boundaries with ArchUnit tests — this is what proves the boundaries are real
   before you spend money on separate deployables.
7. Shrink `ResponseShapeService` as each context starts returning its own contract.

**Exit criteria:** ArchUnit passes with zero cross-context package imports; each context's tables
live in its own schema; still one deployable per tier.

**⚠️ Stop-and-reassess gate.** Phases 0–4 deliver roughly 80% of the benefit at roughly 20% of the
cost. Only continue to Phase 5 if there is a concrete driver: independent scaling of a specific
context, separate teams needing independent release cadence, or a compliance boundary. "It would be
cleaner" is not a driver at this codebase's size (~19k LOC).

---

### Phase 5 — Extract services (one context at a time)

Strangler-fig, in the order given in §5. For each context:

1. Stand up the new service repo with its own DB credentials scoped to its schema.
2. Route the gateway to the new service behind a **feature flag**; keep the monolith path live.
3. Dual-run and compare responses in staging.
4. Cut over, monitor, then delete the monolith code path.

**Order and rationale:**

| Order | Context | Why here |
|---|---|---|
| 1 | **Identity & Access** | Everything depends on it. Must be a real service before others can validate tokens independently. |
| 2 | **Collaboration Workflow** | Pilot. Self-contained (3 tables, no cross-context writes), no money, already rebuilt once — cheapest place to learn the deployment/observability pattern. |
| 3 | Creator Relationship | High read volume, clean aggregate. |
| 4 | Campaign Management | Depends on Creator read models. |
| 5 | Attribution & Commerce | Highest write volume; benefits most from independent scaling. |
| 6 | Payouts & Finance | Money — extract only once the event backbone and audit logging are proven. |
| 7 | Content & Landing | Public-facing landing pages have a different scaling and caching profile. |

---

### Phase 6 — Micro-frontends

```
shell (host)          → auth, brand switcher, nav, permission context, design system
├── mf-identity       → members, brands, settings
├── mf-workflow       → WorkflowPage (734 lines — cleanest extract)
├── mf-creators       → CreatorsPage
├── mf-campaigns      → CampaignsPage, ImportPage
├── mf-commerce       → CouponsPage, MarketplacePage
├── mf-finance        → PayoutsPage, DashboardPage
└── mf-content        → ContentPage, LandingPage
```

**Prerequisite:** decompose [`App.jsx`](../InfluencerUI/src/App.jsx) *first*. Its 1512 lines and ~40
`useState` hooks are shared by all 10 pages; federating without untangling that just distributes the
coupling across repos where it is harder to fix. Extract per-page state into each remote, leaving
only auth + active brand + permissions in a shared context provider.

Split [`api.js`](../InfluencerUI/src/api.js) (~55 functions) so each remote owns its slice.

---

## 7. Impact summary by area

| Area | Impact | Effort |
|---|---|---|
| `schema/` | 4 new tables; `user_id`→`brand_id` on 18 tables; 3 unique constraints rewritten; all tenant indexes rebuilt | High |
| DAO controllers (19) | Every one changes tenancy param; CRUD→behavioral in Phase 4 | High |
| DAO repositories (19) | 35 `findBy…UserId…` → `findBy…BrandId…` | Medium (mechanical) |
| DAO models (18) | `userId`→`brandId` + `createdByUserId` | Medium (mechanical) |
| `SessionService` | Full rewrite: in-memory map → signed JWT + refresh store | High |
| `RequestUserResolver` | Rewrite as `TenantContextResolver`; delete unsafe fallback | Medium |
| `AuthService` | Signup creates account+brand+membership; drop hardcoded `"owner"` (lines 45, 100) | Medium |
| `DaoGatewayClient` | Fix trust-all TLS; propagate brand + service auth headers | Low |
| `ResponseShapeService` | Shrinks toward deletion as contexts own their contracts | Low (deletion) |
| BFF services (13) | Business logic relocated to owning contexts in Phase 4 | High |
| `App.jsx` | Brand switcher, permission context, state decomposition, storage versioning | High |
| `api.js` | `X-Brand-Id` on all ~55 calls; split per context in Phase 6 | Medium |
| UI pages (10) | Permission gating on controls and routes | Medium |
| `agent_service` | Lowest impact — already isolated; needs brand-scoped `mapping_examples` | Low |

---

## 8. Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| Backfill assigns rows to the wrong brand | **Critical** | Full backup; per-user row-count reconciliation before/after; dual-write window with `user_id` retained one full release |
| Cross-brand data leak after tenancy switch | **Critical** | Repository-level tenant filter (Phase 0.6) so a missed predicate cannot leak; automated cross-tenant probe tests in CI |
| `creators` unique constraint change causes duplicate merge decisions | ~~High~~ **Resolved** | Settled in §3.4: creators are per-brand rows (`unique (brand_id, platform, handle)`). Migration is 1:1 with zero collisions and no merge decisions. Cross-brand roll-up deferred to the derived `creator_identity` projection |
| JWT rewrite logs out all users | Medium | Ship during low traffic; accept dual validation (old map + new JWT) for one release |
| Decomposition stalls half-done | Medium | Every context is independently valuable; Phase 4 is a stable resting state — it is fine to stop there permanently |
| Distributed transactions across contexts | Medium | Sagas + outbox; never 2PC. Keep the payout chain eventually consistent with explicit compensating actions |
| `localStorage` shape change breaks returning users | Low | Version the storage key; clear on mismatch |

---

## 9. Recommendation

Commit to **Phases 0–3** unconditionally. They deliver multi-brand, agency support, and RBAC —
the actual product requirement — on the existing deployables, and they fix security defects that are
already live.

Treat **Phase 4** as the natural destination. A modular monolith with enforced boundaries gives you
the domain-driven design, the testability, and the option value of splitting later at a fraction of
the operational cost.

Treat **Phases 5–6** as opt-in, triggered by a specific and named pressure. At ~19k LOC, the
distributed-systems tax — tracing, per-service CI, contract tests, eventual-consistency bugs — will
exceed the modularity benefit until team size or a scaling hotspot justifies it. The sequencing above
is designed so that stopping after Phase 4 leaves no wasted work: the boundaries drawn there are
exactly the seams Phase 5 would cut along.
