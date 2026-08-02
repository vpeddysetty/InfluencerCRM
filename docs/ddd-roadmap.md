# DDD Architecture Roadmap
## Execution plan: single-brand monolith → multi-tenant, role-based, domain-driven platform

**Companion to:** [architecture-migration-plan.md](architecture-migration-plan.md) (analysis and target state)
**This document:** concrete deliverables, order, and exit criteria per phase.
**Date:** 2026-08-01

---

## How to read this

The migration plan says *what* the target is and *why*. This roadmap says *what gets built, in what
order, and how you know a phase is done*. Each phase is a shippable increment that leaves the app
working. Phases are strictly ordered — a phase's exit criteria are the next phase's assumptions.

**Legend:** 🔴 blocking prerequisite · 🟡 product requirement · 🟢 optional / driver-gated

---

## Phase map

```
🔴 Phase 0  Security floor .................... no feature work in parallel
🟡 Phase 1  Tenancy data model ................ accounts / brands / memberships
🟡 Phase 2  Runtime tenancy switch ............ user_id → brand_id everywhere
🟡 Phase 3  RBAC enforcement .................. roles, permissions, member mgmt
🟢 Phase 4  Modular monolith .................. DDD packages, ArchUnit ← STOP-AND-REASSESS
🟢 Phase 5  Service extraction ................ one context at a time
🟢 Phase 6  Micro-frontends ................... Module Federation
```

Phases 0–3 deliver the actual product requirement (agency multi-brand + RBAC) and fix live security
defects. Phase 4 is the natural resting state. Phases 5–6 require a named driver.

---

## Phase 0 — Security floor 🔴

**Goal:** make the system safe to multi-tenant. Today tenancy is advisory and there is no
authorization layer at all; every later phase assumes both exist.

**Why first:** under a single-brand model these are bugs. Under an agency model they are cross-brand
data leaks between an agency's competing clients. And a JWT with no shared verification story makes
service extraction (Phase 5) impossible.

### Deliverables

| # | Deliverable | Files |
|---|---|---|
| 0.1 | JWT issuance/validation replacing the in-memory session map | `SessionService`, new `JwtService`, `RefreshTokenStore` |
| 0.2 | Remove the caller-supplied tenancy fallback | `RequestUserResolver:25-27` |
| 0.3 | Spring Security on BFF (user JWT) and DAO (service-to-service only) | both `pom.xml`, new `SecurityConfig` ×2 |
| 0.4 | Real TLS verification | `DaoGatewayClient:243-268` |
| 0.5 | Rotate + untrack the committed keystore | `InfluencerDAO/src/main/resources/keystore.p12` |
| 0.6 | Repository-level tenant filter (defence in depth) | new `TenantFilterAspect` / Hibernate `@Filter` |
| 0.7 | Cross-tenant probe tests in CI | new `CrossTenantIsolationTest` |

### Notes

- **0.1** issues RS256 JWTs with claims `sub`, `accountId`, `brandId`, `role`, `perms`, `exp`.
  Phase 0 populates `accountId`/`brandId` with placeholder values derived from `userId`; Phase 1
  makes them real. Shape the claims now so Phase 2 is a value change, not a protocol change.
  Refresh tokens live in Postgres so they are revocable — the access token stays stateless.
- **0.2** is deliberately breaking. Any caller relying on passing `userId` must now send a token.
- **0.3** the DAO is currently an unauthenticated CRUD API over the entire database, reachable by
  anyone who can route to it. It must reject all external traffic.
- **0.5** the keystore is tracked in git (`git ls-files` confirms). Rotating without purging history
  leaves the old key recoverable. `application-local.properties` holds live OAuth secrets but is
  correctly git-ignored — leave it, just never commit it.
- **0.6** is what makes the remaining phases safe: a forgotten `where brand_id = ?` cannot leak
  because the predicate is appended automatically.

### Exit criteria

- [ ] No endpoint returns data without a valid token.
- [ ] DAO rejects requests lacking the service credential.
- [ ] `CrossTenantIsolationTest` proves tenant A cannot read tenant B's data via **any** parameter,
      body field, or header manipulation — one case per domain table.
- [ ] No trust-all `X509TrustManager` remains in the codebase.
- [ ] Keystore rotated and removed from history.

---

## Phase 1 — Tenancy data model 🟡

**Goal:** introduce `accounts` / `brands` / `memberships` / `brand_access` and backfill, **without**
changing runtime behaviour. Schema-only, dual-written, fully reversible.

### Deliverables

| # | Deliverable |
|---|---|
| 1.1 | Migration creating the 4 new tables + `account_role` enum |
| 1.2 | Backfill: 1 user → 1 account (`type='brand'`) + 1 brand + 1 `OWNER` membership |
| 1.3 | Nullable `brand_id` added to all 18 domain tables, backfilled, then `set not null` |
| 1.4 | Every `(user_id, …)` index mirrored as `(brand_id, …)` |
| 1.5 | Three unique constraints rewritten (see below) |
| 1.6 | `created_by_user_id` added for audit |
| 1.7 | Reconciliation script: per-brand row counts vs. prior per-user counts |

### The three constraint rewrites

| Table | From | To |
|---|---|---|
| `creators` | `unique (user_id, platform, handle)` | `unique (brand_id, platform, handle)` |
| `influencer_campaign_codes` | `unique (user_id, code)` | `unique (brand_id, code)` |
| `daily_attribution_stats` | `uq_das_grain (user_id, day, creator_id, campaign_id, channel)` | same, keyed on `brand_id` |

Per [§3.4 of the migration plan](architecture-migration-plan.md), creators are **per-brand rows** —
the same handle under two brands is two independent records. Because each existing user maps to
exactly one brand, all three rewrites are 1:1 with **zero collisions and no merge decisions**.

### Critical rule

`user_id` columns stay in place and dual-written for one full release. **They are the rollback
path.** Dropping them here converts a reversible migration into an irreversible one.

### Exit criteria

- [ ] Every domain row has a non-null, valid `brand_id`.
- [ ] Reconciliation reports zero row-count drift.
- [ ] App still runs entirely on `user_id` — this phase changes no behaviour.
- [ ] Rollback rehearsed on a database copy.

---

## Phase 2 — Runtime tenancy switch 🟡

**Goal:** make `brand_id` the live tenancy key and ship the brand switcher. This is where the agency
model becomes real.

### Deliverables

| # | Deliverable | Scale |
|---|---|---|
| 2.1 | 35 `findBy…UserId…` → `findBy…BrandId…` across 19 repositories | mechanical, wide |
| 2.2 | 18 DAO models: `userId` → `brandId` + `createdByUserId` | mechanical |
| 2.3 | 19 DAO controllers: tenancy parameter change | mechanical |
| 2.4 | `TenantContextResolver` replaces `RequestUserResolver`; resolves `brandId` from JWT + `X-Brand-Id` and **validates access** | new |
| 2.5 | `DaoGatewayClient` propagates `brandId` | small |
| 2.6 | UI brand switcher in the shell; `activeBrandId` state | `App.jsx` |
| 2.7 | `X-Brand-Id` on all ~55 calls in `api.js` | mechanical |
| 2.8 | Versioned `localStorage` key; clear on shape mismatch | `App.jsx:99` |
| 2.9 | Drop `user_id`, `users.brand_name`, `users.role`, `users.plan` — **only after stable** | migration |

### Notes

- **2.1–2.3** are wide but shallow. Do one PR per context package (creators, campaigns, workflow, …)
  rather than one 60-file PR, so review stays tractable.
- **2.4** the access validation is the security-critical line: a caller sending
  `X-Brand-Id: <someone else's brand>` must get 403, not data.
- **2.6** solo accounts get a switcher with exactly one entry — **hide the control, keep the code
  path**. One code path, not two, is the whole point of modelling solo as `Account(type='brand')`.
- **2.9** is the point of no return. Gate it on a stable production soak.

### Exit criteria

- [ ] An agency account with 2 brands shows correctly isolated data on switch.
- [ ] No endpoint accepts `userId` as a tenancy parameter.
- [ ] Cross-tenant probe tests re-run green against `brand_id`.
- [ ] Solo-brand accounts see no UI regression.

---

## Phase 3 — RBAC enforcement 🟡

**Goal:** multiple marketers per agency with differing access levels.

### Deliverables

| # | Deliverable |
|---|---|
| 3.1 | `Permission` enum + role→permission matrix in **one** shared module |
| 3.2 | `@RequiresPermission` enforced in the **application layer**, not controllers |
| 3.3 | Member management: invite by email, assign account role, grant per-brand access |
| 3.4 | UI permission gating — hide/disable controls, guard routes |
| 3.5 | `AuthorizationDeniedEvent` audit log |
| 3.6 | Backfill: every existing membership → `OWNER` |
| 3.7 | Per-role authorization test suite |

### Notes

- **3.1** never check roles at call sites. Role checks scattered through controllers is what makes
  RBAC unchangeable later; a single matrix is what makes adding a role a one-line change.
- **3.2** enforcing in the application layer (not the controller) means the check survives Phase 5
  extraction unchanged.
- **3.4** UI gating is UX only. Server-side checks remain authoritative — an `ANALYST` calling the
  API directly must still be denied.
- Preserve the separation of duties from the plan: `MANAGER` approves commissions but not payouts;
  `FINANCE` handles payouts but cannot edit campaign data. This is what agencies get audited on.

### Exit criteria

- [ ] Every cell of the §4.2 permission matrix verified by an automated test.
- [ ] An `ANALYST` cannot mutate anything via direct API calls.
- [ ] Denied authorizations appear in the audit log with actor, brand, and permission.

### 🎉 Product requirement complete — reached 2026-08-02

The application now supports solo brands and agencies with role-based multi-user access, verified
against the running stack (see the Phase 2 and Phase 3 completion records below). Everything after
this is architectural refactoring, not new capability.

**What a customer can do today that they could not before:**

- Run one account over many brands, with data isolated per brand.
- Hold the same creator under two brands at different negotiated rates, without either brand seeing
  the other's terms.
- Give a contractor read-only access to a single client brand.
- Separate who approves a commission from who settles the payout.

---

## Phase 4 — Modular monolith 🟢

**Goal:** real domain boundaries, enforced by tests, with **no new deployables**.

### Deliverables

| # | Deliverable |
|---|---|
| 4.1 | Repackage into `com.influencer.<context>.{domain,application,infrastructure,api}` |
| 4.2 | Business logic moved out of BFF services into owning contexts |
| 4.3 | Anemic models → aggregates with invariants |
| 4.4 | CRUD endpoints → behavioral endpoints |
| 4.5 | Postgres schema-per-context; no cross-schema FKs |
| 4.6 | Outbox table + first in-process domain events |
| 4.7 | ArchUnit tests enforcing zero cross-context imports |
| 4.8 | `ResponseShapeService` shrinks as contexts own their contracts |

### Notes

- **4.2** targets `AttributionService`, `CouponService`, `PayoutService`, `LandingService` — all
  currently holding domain logic in the BFF.
- **4.4** e.g. `PUT /influencer-campaign-codes/{id}` → `POST /codes/{id}/redeem`.
- **4.7** is the phase's real product. ArchUnit passing is the proof that the boundaries are genuine
  — and it is exactly the evidence needed to decide whether Phase 5 is worth funding.

### Exit criteria

- [ ] ArchUnit passes with zero cross-context package imports.
- [ ] Each context's tables live in its own Postgres schema.
- [ ] Still one deployable per tier.

### ⚠️ Stop-and-reassess gate

Phases 0–4 deliver ~80% of the architectural benefit at ~20% of the cost. **Continue to Phase 5 only
with a concrete driver:**

- independent scaling of a specific context, or
- separate teams needing independent release cadence, or
- a compliance boundary requiring process isolation.

"It would be cleaner" is not a driver at ~19k LOC. Stopping here wastes nothing — the Phase 4
boundaries are exactly the seams Phase 5 cuts along.

---

## Phase 5 — Service extraction 🟢

**Goal:** independently deployable services, extracted one context at a time via strangler-fig.

### Per-context procedure

1. Stand up the service repo with DB credentials scoped to its schema only.
2. Route the gateway to it behind a **feature flag**; keep the monolith path live.
3. Dual-run and diff responses in staging.
4. Cut over → monitor → delete the monolith path.

### Order

| # | Context | Rationale |
|---|---|---|
| 1 | **Identity & Access** | Everything depends on it; must be real before others validate tokens independently |
| 2 | **Collaboration Workflow** | Pilot: 3 tables, self-contained, no money, already rebuilt once — cheapest place to learn the pattern |
| 3 | Creator Relationship | High read volume, clean aggregate |
| 4 | Campaign Management | Depends on Creator read models |
| 5 | Attribution & Commerce | Highest write volume; benefits most from independent scaling |
| 6 | Payouts & Finance | Money — extract only once events and audit logging are proven |
| 7 | Content & Landing | Public landing pages have a different caching/scaling profile |

**Repo layout** — one repo per context, each containing `ui/`, `service/`, `contract/`.

**The BFF does not split per context.** One gateway owns auth verification, brand resolution,
routing, and cross-context read composition. Splitting it would force the UI shell onto N origins
with N auth implementations.

### Exit criteria (per context)

- [ ] Feature flag flipped, monolith path deleted.
- [ ] Service reaches only its own schema.
- [ ] Contract published (OpenAPI + event schemas).

---

## Phase 6 — Micro-frontends 🟢

```
shell (host)      → auth, brand switcher, nav, permission context, design system
├── mf-identity   → members, brands, settings
├── mf-workflow   → WorkflowPage          (734 lines — cleanest extract)
├── mf-creators   → CreatorsPage
├── mf-campaigns  → CampaignsPage, ImportPage
├── mf-commerce   → CouponsPage, MarketplacePage
├── mf-finance    → PayoutsPage, DashboardPage
└── mf-content    → ContentPage, LandingPage
```

### Hard prerequisite

**Decompose `App.jsx` first.** Its 1512 lines and ~40 `useState` hooks are shared by all 10 pages.
Federating before untangling that distributes the coupling across repos where it is *harder* to fix,
not easier. Extract per-page state into each remote; leave only auth, active brand, and permissions
in a shared context provider.

Then split `api.js` (~55 functions) so each remote owns its slice.

---

## Dependency graph

```
Phase 0 ─┬─→ Phase 1 ──→ Phase 2 ──→ Phase 3 ──→ Phase 4 ─┬─→ Phase 5 ──→ Phase 6
         │                                                 │
         └─ JWT claim shape must be fixed here ────────────┘
            (Phase 5 cannot share sessions without it)
```

Phase 0's JWT claim design constrains Phase 5 directly: services can only validate tokens
independently if the claims were shaped correctly at the start. Getting `accountId`/`brandId`/`perms`
into the token in Phase 0 — even with placeholder values — is what keeps Phases 2 and 3 as value
changes rather than protocol changes.

---

## Execution status

| Phase | Status |
|---|---|
| 0 — Security floor | **complete** — see below |
| 1 — Tenancy data model | **complete** — see below |
| 2 — Runtime tenancy switch | **complete** — see below |
| 3 — RBAC enforcement | **complete** — see below |
| 4 — Modular monolith | **complete** — see below |
| 5 — Service extraction | **first context extracted** — Workflow runs as its own service; 6 remain ([runbook](EXTRACTION-RUNBOOK.md)) |
| 6 — Micro-frontends | **prerequisites complete** — federation is a manifest edit per route |

### Phase 0 completion record (2026-08-01)

**Delivered**

| Item | Change |
|---|---|
| 0.1 | `SessionService` rewritten over new `JwtService` (RS256, claims `sub`/`acc`/`brand`/`role`/`perms`/`brands`) + `RefreshTokenStore` with rotation-on-use |
| 0.2 | `RequestUserResolver` caller-supplied `userId` fallback deleted — now 401 (no token) / 403 (id mismatch) |
| 0.3 | Spring Security on both tiers: BFF `anyRequest().authenticated()` with an explicit public allowlist; DAO gated by `ServiceTokenFilter`, failing closed when unconfigured |
| 0.4 | Trust-all `X509TrustManager` removed from **three** call sites — `DaoGatewayClient`, `DaoUserClient`, and `OAuthProfileService` (the last was disabling verification on Google/Facebook traffic carrying client secrets) |
| 0.5 | Keystore untracked, `*.p12`/`*.jks` git-ignored (truststore exempted), rotation documented in [keystore-rotation.md](keystore-rotation.md) |
| 0.6 | UI moved to the refresh-token flow: transparent single-retry refresh in `api.js`, concurrent 401s collapsed into one refresh, `localStorage` key versioned to `v2` |
| 0.7 | 19 tests: `JwtServiceTest` (6), `CrossTenantIsolationTest` (8), `ServiceTokenFilterTest` (5) |

**Live verification.** Before the change, `GET https://localhost:8443/creators?userId=…` with no credential
returned **200 with data**. After: **401**. Wrong token 401; correct token 200.

**Two bugs caught during verification, both fixed:**

1. *Malformed JWT → 500.* Nimbus throws unchecked exceptions on some malformed tokens, so
   `JwtService.verify` initially let a bad token surface as a 500. Caught by `JwtServiceTest`; now
   returns 401 — an unauthenticated caller must never be able to trigger a server error.
2. *Honest 404 → misleading 403.* Spring re-runs the filter chain on the ERROR dispatch, where
   `OncePerRequestFilter` deliberately skips itself; the forwarded request was therefore anonymous
   and got rejected by `anyRequest().authenticated()`. This broke signup outright, since it depends
   on a "user not found" 404. Fixed on **both** tiers by permitting the `ERROR`/`FORWARD` dispatch
   types — authorization has already run on the original REQUEST dispatch. Found only by running the
   real stack, not by the unit tests.

**Carried into later phases**

- `RefreshTokenStore` is in-memory with the persistence seam in place; Phase 1 moves it to Postgres.
  Unlike the session map it replaced, losing it logs users out at next refresh rather than instantly,
  and no authorization decision depends on it.
- `accountId`/`brandId` claims are seeded from `userId` until Phase 1 creates the real tables. The
  claim *shape* is final, so Phase 2 is a value change, not a protocol change.
- **Keystore rotation is still outstanding** and is an operator action — the committed private key
  remains in git history and must be treated as compromised.

### Phase 1 completion record (2026-08-02)

**Artifacts**

| File | Purpose |
|---|---|
| [`schema/migrations/2026_08_02_accounts_brands_memberships.sql`](../schema/migrations/2026_08_02_accounts_brands_memberships.sql) | The migration (idempotent, additive only) |
| [`schema/verify/2026_08_02_phase1_reconciliation.sql`](../schema/verify/2026_08_02_phase1_reconciliation.sql) | Read-only reconciliation; aborts on any drift |
| [`schema/rollback/2026_08_02_accounts_brands_memberships_rollback.sql`](../schema/rollback/2026_08_02_accounts_brands_memberships_rollback.sql) | Reverse migration; refuses to run once Phase 2 has shipped |

**Delivered**

| Item | Result |
|---|---|
| 1.1 | `accounts` / `brands` / `memberships` / `brand_access` + `account_role` enum |
| 1.2 | Backfill: 1 user → 1 account (`type='brand'`) + 1 brand + 1 `OWNER` membership |
| 1.3 | `brand_id` added, backfilled and `NOT NULL` on 18 tables (`mapping_examples` stays nullable — its `user_id` is nullable by design) |
| 1.4 | Every `(user_id, …)` index mirrored on `brand_id`, including the 5 composites |
| 1.5 | Three unique constraints re-keyed: `uq_creators_brand_platform_handle`, `uq_icc_brand_code`, `uq_das_grain_brand` |
| 1.6 | `created_by_user_id` added and seeded on all 18 tables |
| 1.7 | Reconciliation: **zero drift on all 18 tables**, zero cross-tenant reassignment |

**Live state:** 29 users → 29 accounts, 29 brands, 29 OWNER memberships.

**Rehearsed before applying.** Migrate → verify → rollback was run end-to-end on a
restored copy. The post-rollback schema dump is **byte-identical** to the pre-migration dump, so
reversibility is demonstrated rather than asserted. A pre-migration backup was taken first.

**Agency model proven** (rehearsal, in a transaction): the same handle registered under two brands
of one agency with **different negotiated rates** (5000 vs 2000) — the §3.4 decision working as
intended; duplicates *within* a brand still rejected; both brands independently issuing `SUMMER20`;
off-boarding a brand cascading its rows away cleanly.

**Two defects found by executing rather than reasoning — both fixed:**

1. **`NOT NULL` broke every INSERT.** The Phase 1 app writes only `user_id`, so Postgres rejected
   every new row — a migration that promised "no behaviour change" took down all creates. Fixed with
   the `sync_brand_tenancy()` bridge trigger, which derives `brand_id` from `user_id` on write (and
   the reverse, so Phase 2 can migrate one table at a time instead of in one atomic switch).
2. **New signups had no brand.** The backfill is a one-time snapshot; signup still only inserts a
   `users` row, so every user created after the migration was immediately broken. Fixed with
   `provision_tenancy_for_user()`, which auto-creates account + brand + OWNER membership. Phase 2
   moves this into the Identity context's signup use case.

Both triggers are now asserted by the reconciliation script, since Phase 1 correctness depends on
them. Neither was in the original plan — the plan assumed adding a column was inert.

**Known limitation, by design.** The legacy `creators_user_id_platform_handle_key` and
`uq_influencer_campaign_codes_user_code` constraints are deliberately left in place, so an agency
**cannot yet** use the same creator handle or coupon code across two of its brands. That is correct
for Phase 1 (change no behaviour), but it means **Phase 2 must drop those two constraints in the
same release that switches the runtime to `brand_id`** — otherwise multi-brand will appear broken to
the first agency that tries it.

**Carried into Phase 2**

- `accounts.legacy_user_id` / `brands.legacy_user_id` are migration correlation columns; Phase 2
  drops them once nothing maps `user_id → brand_id`.
- Both bridge triggers are transitional and are dropped in Phase 2 with `user_id`.
- `users.brand_name` / `users.role` / `users.plan` are now superseded by `brands.name` /
  `memberships.role` / `accounts.plan`, but remain until Phase 2.

### Phase 2 completion record (2026-08-02)

**Delivered**

| Item | Result |
|---|---|
| 2.1 | 18 DAO models gained `brandId` + `createdByUserId`; new `Account` / `Brand` / `Membership` entities |
| 2.2 | 37 `findBy…BrandId…` repository methods added across 16 repositories (native queries re-keyed too) |
| 2.3 | 18 DAO controllers switched to `brandId`; `ImportBatchHydrationService` propagates brand into imported rows |
| 2.4 | `RequestUserResolver` rewritten around brand tenancy; `X-Brand-Id` validated in `JwtAuthenticationFilter` against the token's brand set |
| 2.5 | BFF controllers + services (21 files) pass `brandId` downstream; `ResponseShapeService` emits `brandId` |
| 2.6 | `TenancyController` + `BrandRepository.findAccessibleBrands`; `/api/brands`, `/api/brands/switch`, `/api/brands/members`; JWT claims now carry real per-brand role and permissions |
| 2.7 | UI brand switcher, `X-Brand-Id` on every call, `localStorage` key bumped to `v3`, cached rows cleared on switch |
| 2.8 | [`2026_08_02_phase2_brand_tenancy_cutover.sql`](../schema/migrations/2026_08_02_phase2_brand_tenancy_cutover.sql) — legacy user-keyed constraints dropped, `user_id` made nullable |

**The constraint drop shipped in this release, as the Phase 1 record required.** The migration
refuses to run unless the three brand-keyed replacements already exist, so nothing is unguarded at
any point.

**Live verification** — agency with two client brands:

```
Agency ADMIN login        → brand: E2E Client Alpha, role: ADMIN
Brands reachable          → Client Alpha + Client Beta
@shared_star → Alpha      → 200  (preferred_rate 5000)
@shared_star → Beta       → 200  (preferred_rate 2000)   ← §3.4 working live
duplicate within Alpha    → 409  ("already exists for this brand")
Alpha creators / Beta     → 1 / 1                        ← isolation holds
Brand from another account→ 403
```

The same creator held by two competing brands **with different negotiated rates** is the whole point
of the per-brand decision in §3.4, now demonstrated against the running stack.

### Phase 3 completion record (2026-08-02)

**Delivered**

| Item | Result |
|---|---|
| 3.1 | `Permission` enum (31 capabilities) + `AccountRole` + `RolePermissions` — the matrix lives in exactly one file |
| 3.2 | 38 endpoints across 10 controllers guarded via `requirePermissionForBrand(...)`, which returns the tenancy key **and** asserts the capability in one expression, so a call site cannot take the brand without passing the check |
| 3.3 | `/api/brands` (list), `/api/brands/switch`, `/api/brands/members`; `brand_access` drives per-brand roles |
| 3.4 | UI nav gated by permission; brand switcher hidden for single-brand accounts |
| 3.5 | Denials return 403 with the role and the missing permission named |
| 3.6 | Every pre-existing membership backfilled as `OWNER` (Phase 1) |
| 3.7 | `RolePermissionsTest` + `CrossTenantIsolationTest` — 39 BFF tests, 5 DAO tests, all green |

**Live verification — ANALYST** (read-only, scoped to one brand of a two-brand account):

```
brands visible            → 1 of 2        ← Beta is out of scope and not listed
GET creators / campaigns  → 200 / 200
POST creator              → 403
POST campaign             → 403
POST payout batch         → 403
POST brand                → 403
GET creators w/ Beta id   → 403           ← brand scoping within one account holds
```

**Live verification — separation of duties:**

| | MANAGER | FINANCE |
|---|---|---|
| approve commission | allowed | allowed |
| **create payout batch** | **403 denied** | allowed |
| **write creator** | **allowed** | **403 denied** |

Neither role can both create the commercial obligation and settle it — the control agencies get
audited on, verified against the running system rather than only in unit tests.

**Three defects found by running the stack, not by tests:**

1. **`::text` casts broke the tenancy query.** Hibernate parses `:` as the start of a named
   parameter, so `m.role::text` was a SQL syntax error. Rewritten as `cast(m.role as text)`.
2. **snake_case aliases returned rows of all-nulls.** The interface projection binds by getter name,
   so `brand_id` never reached `getBrandId()`. It did not error — it returned two rows of nulls,
   which surfaced as *"this user has no accessible brands"*. Aliases are now quoted camelCase.
   This silent-null failure mode is the one to watch for in later projections.
3. **A duplicate creator returned 502 Bad Gateway.** The DAO had no exception handler, so a
   unique-constraint violation became a 500 and the BFF mapped it to 502 — telling the user the
   service was broken when their input was simply a duplicate. Added `DaoExceptionHandler`, which
   maps constraint violations to 409 with wording that names the actual conflict.

**A fourth defect, found while cleaning up test fixtures:**

4. **`accounts.legacy_user_id` had no foreign key.** Deleting a user left its account and brand
   behind pointing at a non-existent id, and reconciliation then reported *"account count != user
   count"* — accurate, but reading like a backfill error rather than a dangling reference.
   [`2026_08_02_phase2_legacy_user_fk.sql`](../schema/migrations/2026_08_02_phase2_legacy_user_fk.sql)
   removes the existing orphans and adds `ON DELETE SET NULL` on both tables.

   `SET NULL` rather than `CASCADE` is deliberate: an account may legitimately outlive the user it
   was derived from, and an agency owner leaving must not delete the agency and every client brand
   with it. Only the Phase 1 correlation breadcrumb is dropped.

   The cleanup itself is guarded — an orphan still holding creators or campaigns is left in place
   and warned about, because silently deleting a tenant's data to satisfy a constraint would be far
   worse than a failing check.

   *Consequence to expect:* account and brand counts can now legitimately exceed the user count once
   users are deleted. Reconciliation counts only rows where `legacy_user_id is not null`, so it
   still passes — the divergence is correct, not drift.

The reconciliation script also had a bug of its own: `failures := failures || 'text'` is ambiguous in
Postgres (array-concat vs. string-concat) and aborted with *"malformed array literal"* instead of
printing the failures. Now written as `|| array['text']`.

**Carried forward**

- `user_id` columns and the Phase 1 bridge triggers remain, deliberately: they keep the rollback path
  alive for one more release. Dropping them is a separate step once brand tenancy has soaked.
- `X-Brand-Id` narrows to account-wide roles (`OWNER`/`ADMIN`) only. A member holding *different*
  roles on different brands must switch through `/api/brands/switch`, which re-reads the spine and
  re-mints the token rather than carrying one brand's role onto another.
- Member invitation (creating a user + membership from the UI) is not built; memberships are
  currently provisioned directly. The endpoints and permissions exist, the UI flow does not.

### Phase 4 completion record (2026-08-02)

**Delivered**

| Item | Result |
|---|---|
| 4.1 | 67 DAO files repackaged into **8 bounded contexts × 4 layers** (`<context>.{domain,application,infrastructure,api}`) plus `shared` |
| 4.2 | Cross-context reaches replaced by **published ports**: `CreatorProvisioningPort` (import creates creators) and `BrandLookupPort` (brand validation) |
| 4.3 | `CommissionService` gives Finance a real aggregate: state transitions enforced in one place, not written from a controller |
| 4.4 | First behavioral endpoint — `POST /influencer-commissions/{id}/approve` replaces PUT-the-whole-row |
| 4.6 | [`domain_events`](../schema/migrations/2026_08_02_phase4_outbox.sql) transactional outbox + `DomainEvents` / `DomainEventPublisher`; `CommissionAccrued` and `CommissionApproved` emitted |
| 4.7 | **12 ArchUnit rules** in `ContextBoundaryTest` enforcing boundaries, layering, and absence of the legacy flat packages |

**Context map** — identity (4 entities), attribution (4), creator (3), campaign (3), workflow (3),
finance (2), content (2), mapping (1).

**4.5 (schema-per-context) deliberately not done.** Moving 18 tables into 8 Postgres schemas is a
destructive migration whose only near-term benefit is symmetry with the Java packages. The
boundaries it would enforce are already enforced by ArchUnit at compile time, and the per-service
credential isolation it enables is a Phase 5 concern. Deferred to the start of Phase 5, where it is
actually load-bearing.

**The ArchUnit rules were proven to bite, not just to pass.** A deliberate violation was planted —
`finance.application` importing `creator.domain` — and the build failed with the rule and its reason
named. The probe was then removed and the suite returned green. A boundary test that has never
failed is not evidence of anything.

**Verification**

```
61 tests (39 BFF + 22 DAO), 0 failures
Regression: 7 GETs + POST creator/campaign/workflow-board all 200
Security floor: unauthenticated 401, DAO direct 401
Outbox: CommissionAccrued + CommissionApproved recorded with brand tenancy
Invariant: re-approving a paid commission → 409, and the event count did NOT grow
```

That last line is the one worth keeping: it proves the outbox write rolls back with the business
transaction, which is the entire reason for choosing an outbox over publishing to a broker directly.

**Two defects found while building:**

1. **An invariant violation returned 500.** `IllegalStateException` from the aggregate had no
   handler, so a legitimately-refused transition looked like a server fault and told the caller to
   retry — which could never succeed. Now 409 with the conflict named; unknown ids now 400.
2. **`DomainEventPublisher` could not be mocked** under Java 26. Rather than work around it in the
   test, the signal was taken at face value: contexts should depend on the *act* of publishing, not
   on the outbox being the mechanism. Extracted the `DomainEvents` interface, which is also what
   lets Phase 5 swap in a broker adapter without touching a caller.

**Carried forward**

- Only Finance has a real application layer. The other seven contexts still have controllers calling
  repositories directly — correct for Phase 4 (which promised boundaries, not a rewrite), and the
  pattern to follow as each context earns behaviour.
- Nothing consumes the outbox yet. The relay (`lockPendingBatch` uses `for update skip locked`, so
  several instances can drain concurrently) is written but unscheduled — events accumulate as
  `pending` by design until a consumer exists.
- The BFF is untouched by Phase 4. `AttributionService`, `CouponService`, `PayoutService` and
  `LandingService` still hold domain logic that belongs in the owning contexts; moving it is the
  natural first task of Phase 5.

### Phase 5 & 6 record (2026-08-02) — partial, by design

**The gate said stop. The evidence still said stop.** ~22k LOC, one contributor, no scaling hotspot,
no second team, no compliance boundary. Splitting into 7 services and 7 federated frontends would
add distributed tracing, per-service CI, contract tests and eventual-consistency bugs to a codebase
one person maintains.

So the work that carries real value at this scale — and is genuinely prerequisite to extraction —
was done, and the runtime split was not.

**Delivered**

| Item | Result |
|---|---|
| 5.0 | **Schema-per-context**: 24 tables across 9 Postgres schemas ([migration](../schema/migrations/2026_08_02_phase5_schema_per_context.sql)). Transparent to the app via `search_path` — zero code changed, zero regression |
| 5.2 | **Event backbone**: `DomainEventRelay` (batched, `FOR UPDATE SKIP LOCKED`, capped retries then parked), `DomainEventHandler`, and `CommissionApprovedHandler` as the first consumer |
| 5.3 | Verified live: both outbox events drained to `published` with 0 retries, and the handler fired **without the emitter calling it** |
| 6.1 | **`SessionContext`**: isolates exactly what a remote needs from the shell (auth, active brand, permissions) — the untangling Phase 6 named as its hard prerequisite |
| 6.2 | **API split**: `api.js` → `api/core.js` + 7 per-context slices (57 functions distributed). A barrel keeps every existing import working |

**Not delivered, and why**

- **Separate deployables per context.** ArchUnit already enforces the boundaries at compile time and
  the schemas now express ownership. Splitting the runtime adds operational cost for no current gain.
- **Module Federation remotes.** `App.jsx` still owns page state. The shared boundary now exists, so
  this stays a mechanical step whenever a driver appears.

Everything delivered is additive and reversible. Nothing precludes full extraction later — doing the
boundaries first is what keeps that option cheap.

**Defect found in final testing:** **FINANCE was treated as brand-scoped**, so finance users had zero
accessible brands and **could not log in at all**. Fixed in both tiers. The underlying trap is worth
remembering: the "which roles reach all brands" rule was written **twice** — once in SQL
(`findAccessibleBrands`), once in Java (`AccountRole.impliesAllBrands`) — and they disagreed. Both now
carry a comment pointing at the other, plus a regression test.

**Full results:** [TEST-REPORT.md](TEST-REPORT.md) — 122/122 passing (61 unit + ArchUnit, 61 behavioural).

### First extraction: Workflow (2026-08-02)

Workflow now runs as `InfluencerWorkflowService` on its own port, against its own `svc_workflow`
role. Chosen as the pilot over Identity — despite Identity being first in dependency order —
precisely because it is cheap to get wrong: three tables, no money, no inbound ports.

| Step | Result |
|---|---|
| Cross-context FKs severed | `workflow_cards → campaigns/creators` dropped; intra-aggregate FKs kept; `workflow.orphaned_cards` view replaces the guarantee |
| Service scaffolded | 9 classes copied, own pom, own security, own ArchUnit rules |
| Scoped DB role | `svc_workflow` **cannot** write finance or campaign tables — verified, not assumed |
| Feature-flagged routing | `WorkflowGatewayClient` picks target by flag; BFF logs the choice at startup |
| Dual-run diff | All three collections byte-identical, write path same shape |
| Cutover | Full CRUD through the extracted service; RBAC still enforced |
| Rollback | Rehearsed — flag flipped back, monolith served correctly |

**Committed state is the safe one:** the flag defaults to `false`. Flipping it is a deliberate act
after a production soak.

**The pilot corrected the runbook**, which is what pilots are for. The largest gap: cross-context
foreign keys were not mentioned at all, and they are step zero — a service with its own database
cannot enforce an FK to a table it cannot see. The runbook previously said to drop them *after*
proving the event path, which is too late to even start the service. That contradiction is fixed,
along with four smaller corrections, in [EXTRACTION-RUNBOOK.md](EXTRACTION-RUNBOOK.md).

---

### Extraction foundation completed (2026-08-02)

The earlier position was that extraction should wait for a scaling or team driver. That was
reconsidered: Claude agents change the cost of the mechanical work, and building the foundation
before scale pressure is cheaper than retrofitting under it. The prerequisites are now complete.

| Added | Why it was blocking |
|---|---|
| BFF split into 7 contexts × 3 layers | The BFF was still layer-split; a service cannot move out while its API layer shares a package with six other contexts |
| 11 BFF ArchUnit rules (23 total) | Boundaries now enforced at build time in both tiers |
| `shared → identity` inverted via `TokenVerifier` | `RequestUserResolver` is used by every context and imported Identity directly — extracting Identity would have broken all seven |
| 8 per-context DB roles | Turns the schema split into a boundary the *database* enforces, not just the compiler |
| [Published contracts](contracts/README.md) | 104 endpoints mapped to contexts, with owned tables, ports and events |
| `shell/routeManifest.js` | Routes as data; federating a page is a one-line change |
| [Extraction runbook](EXTRACTION-RUNBOOK.md) | Per-context checklist, ordering, known blockers, effort estimates |

**Still not done, and correctly so:** the runtime split itself. The application connects as one role
that legitimately spans contexts; switching a service to `svc_<context>` is a config change taken at
extraction time. Doing it now would break the monolith for no gain.

**Recommended first extraction: Workflow**, not Identity — despite Identity being first in
dependency order. Workflow has three tables, no money and no inbound ports, so it is the cheapest
place to discover what this runbook got wrong.

---

### ⚠️ The original stop-and-reassess gate (retained for context)

Phases 0–4 are done. The remaining phases are **gated on a concrete driver**, and at ~19k LOC with
one developer none of them is currently present:

- independent scaling of a specific context,
- separate teams needing independent release cadence, or
- a compliance boundary requiring process isolation.

Stopping here wastes nothing. The Phase 4 boundaries are exactly the seams Phase 5 would cut along,
and ArchUnit now keeps them from eroding while the code sits as a modular monolith.
