# Test Report — Multi-Tenant, Role-Based InfluenCRM

**Date:** 2026-08-02
**Scope:** DDD migration Phases 0–6 (security floor → tenancy → RBAC → modular monolith → schema split & events → UI decomposition)
**Result:** **146 / 146 passing** (76 unit + ArchUnit, 70 behavioural against the running stack)

---

## 1. How to run the app

```bash
# 1. Database (already running if you have not stopped it)
docker compose up -d postgres

# 2. Apply schema + migrations (only needed on a fresh database)
docker exec -i influencercrm-postgres psql -U influencercrm_user -d influencercrm_db \
  -f - < schema/influencer_crm_schema.sql
docker exec -i influencercrm-postgres psql -U influencercrm_user -d influencercrm_db \
  -f - < schema/zzz_apply_migrations.sql

# 3. Seed the demo accounts below (see §2 for what they prove)
docker exec -i influencercrm-postgres psql -U influencercrm_user -d influencercrm_db \
  -f - < schema/seed/test_accounts.sql

# 4. Services
cd InfluencerDAO            && mvn spring-boot:run                                  # :8443 (https)
cd InfluencerWorkflowService && mvn spring-boot:run                                 # :8444 (extracted)
cd InfluencerWebExperience  && mvn spring-boot:run -Dspring-boot.run.profiles=local # :8081
cd InfluencerUI             && npm run dev                                          # :5173
```

**Routing Workflow to the extracted service** — the flag defaults to `false` (monolith), which is
the safe committed state. To cut over:

```bash
cd InfluencerWebExperience && mvn spring-boot:run -Dspring-boot.run.profiles=local   -Dspring-boot.run.arguments=--web-experience.workflow-service-enabled=true
```

The BFF logs which target it chose at startup. Flipping the flag back is the rollback — seconds,
no redeploy.

Open **http://localhost:5173** and log in with any account below.

---

## 2. Login credentials

**Password for every demo account: `DemoPass123!`**

> These are seeded demo accounts on a local database. They share one well-known password and must
> never exist in a deployed environment.

### 2.1 Agency — "Northstar Agency (demo)"

An agency managing two competing client brands: **Aurora Beauty** and **Lumen Fitness**.

| Email | Role | Reaches | What it demonstrates |
|---|---|---|---|
| `demo.admin@northstar.test` | ADMIN | Both brands | Brand switcher; create brands; manage members |
| `demo.manager@northstar.test` | MANAGER | Aurora only | Full brand control **and** commission approval — but **cannot** create payouts |
| `demo.marketer@northstar.test` | MARKETER | Aurora only | Day-to-day campaign/creator work; **no** financial approval at all |
| `demo.analyst@northstar.test` | ANALYST | Aurora only | Strictly read-only; every write is refused |
| `demo.finance@northstar.test` | FINANCE | Both brands | Owns the payout chain; **cannot** edit campaign or creator data |

### 2.2 Solo brand

The original single-brand product still works unchanged. Create one by signing up:

| Action | Result |
|---|---|
| Sign up with any new email | Auto-provisions account + brand + `OWNER` membership |
| Log in | Sees exactly **1** brand, so the brand switcher stays hidden |

Any account created before this migration also still works and is now an `OWNER` of its own
single-brand account.

---

## 3. What to try in the UI

### 3.1 Agency multi-brand (log in as `demo.admin`)

1. A **brand switcher** appears in the header — solo accounts do not show it.
2. Add creator `@shared_star` to **Aurora** with rate `5000`.
3. Switch to **Lumen**, add the **same** `@shared_star` with rate `2000`.
4. Switch back to Aurora — the rate is still `5000`.

That is the deliberate decision in [architecture-migration-plan.md §3.4](architecture-migration-plan.md):
creators are **per-brand rows**. Rates, safety notes and scores are *relationship* data, so one
agency client never sees another's negotiated terms.

5. Try adding `@shared_star` to Aurora a second time → **409 Conflict**, "already exists for this brand".

### 3.2 Role gating (log in as `demo.analyst`)

- The nav shows fewer entries — links needing write permission are hidden.
- The brand switcher shows only **Aurora**; Lumen is invisible.
- Any create/edit action is refused with **403** even if called directly against the API.

### 3.3 Separation of duties (`demo.manager` vs `demo.finance`)

- `demo.manager` **can** approve a commission but **cannot** create a payout batch.
- `demo.finance` **can** create and approve payouts but **cannot** add a creator.

Neither role can both create the financial obligation and settle it — the control an agency gets
audited on.

---

## 4. Test results

### 4.1 Automated unit + architecture tests — 76 passing

```
InfluencerWebExperience  50 tests   JwtServiceTest, CrossTenantIsolationTest, RolePermissionsTest,
                                    BffContextBoundaryTest (11 rules)
InfluencerDAO            22 tests   ServiceTokenFilterTest, CommissionServiceTest,
                                    ContextBoundaryTest (12 rules)
InfluencerWorkflowService 4 tests   WorkflowServiceBoundaryTest — keeps the extracted
                                    service from re-growing a monolith dependency
                        ───────
                         76 tests   0 failures, 0 errors
```

**23 ArchUnit rules** enforce context boundaries across both tiers. Both rule sets were verified to
*fail* on a deliberately planted cross-context import, then return green once it was removed — a
boundary test that has never failed is not evidence of anything.

Run with `mvn test` in either module.

### 4.2 Behavioural tests against the running stack — 70 passing

Run with `bash tests/behavioural_suite.sh` while the stack is up.

| Group | Cases | Result |
|---|---|---|
| A. Authentication & security floor | 7 | ✅ all pass |
| B. Solo brand (original product) | 14 | ✅ all pass |
| C. Agency multi-brand | 12 | ✅ all pass |
| D. Role-based access control | 15 | ✅ all pass |
| E. Session lifecycle | 6 | ✅ all pass |
| F. Domain events / outbox | 3 | ✅ all pass |
| G. Data integrity | 4 | ✅ all pass |
| H. Extracted Workflow service | 9 | ✅ all pass |
| **Total** | **70** | **✅ 0 failures** |

#### A. Authentication & security floor

| Case | Expected | Actual |
|---|---|---|
| Unauthenticated `GET /api/creators` | 401 | 401 ✅ |
| `?userId=` injection with no token | 401 | 401 ✅ |
| Forged bearer token | 401 | 401 ✅ |
| DAO reachable directly, no service token | 401 | 401 ✅ |
| DAO with wrong service token | 401 | 401 ✅ |
| DAO with correct service token | 200 | 200 ✅ |
| Public health endpoint | 200 | 200 ✅ |

**Before this work, row 4 returned `200` with data.** The DAO was an unauthenticated read/write API
over the entire database.

#### B. Solo brand

Signup issues a token, auto-provisions a brand, resolves role `OWNER`, shows exactly 1 brand.
`POST` creator / campaign / workflow-board all `200`; `GET` on all 7 collection endpoints `200`.

#### C. Agency multi-brand

| Case | Result |
|---|---|
| ADMIN reaches both client brands | 2 brands ✅ |
| Same handle under brand A (rate 5000) | 200 ✅ |
| Same handle under brand B (rate 2000) | 200 ✅ |
| Duplicate handle **within** one brand | 409 ✅ |
| Same coupon code issued by both brands | 201 / 201 ✅ |
| Brand belonging to another account | 403 ✅ |
| Malformed `X-Brand-Id` header | 403 ✅ |
| Brand switch re-mints the token | new token, new brand ✅ |

#### D. Role-based access control

| Actor | Action | Expected | Actual |
|---|---|---|---|
| ANALYST | read creators | 200 | 200 ✅ |
| ANALYST | write creator / campaign / payout / brand | 403 | 403 ✅ ×4 |
| ANALYST | out-of-scope brand | 403 | 403 ✅ |
| MARKETER | write creator | allowed | 200 ✅ |
| MARKETER | approve commission | 403 | 403 ✅ |
| MARKETER | create payout | 403 | 403 ✅ |
| MANAGER | approve commission | allowed | permitted ✅ |
| MANAGER | create payout | 403 | 403 ✅ |
| FINANCE | create payout | allowed | permitted ✅ |
| FINANCE | write creator | 403 | 403 ✅ |

#### E. Session lifecycle

Refresh issues a new access token, rotates the refresh token, the new token works, replaying the old
one is rejected, logout returns 204, and refresh after logout is rejected.

#### F. Domain events / outbox

Accruing a commission writes a `CommissionAccrued` row carrying brand tenancy; the relay drains it to
`published` and the consuming handler fires — **without the emitter ever calling it**.

#### G. Data integrity

24 tables across 9 context schemas, none left in `public`, every user resolves to a brand, no creator
row without a brand.

#### H. Extracted Workflow service

The first context extracted into its own deployable, per [EXTRACTION-RUNBOOK.md](EXTRACTION-RUNBOOK.md).

| Case | Result |
|---|---|
| Service rejects calls without a service token | 401 ✅ |
| Service serves with the token | 200 ✅ |
| `svc_workflow` **cannot** write `finance.influencer_payouts` | denied ✅ |
| `svc_workflow` **cannot** write `campaign.campaigns` | denied ✅ |
| `svc_workflow` **can** write its own tables | allowed ✅ |
| Monolith and extracted service return identical rows | 23 = 23 ✅ |
| Cross-context FKs on `workflow_cards` removed | 0 ✅ |
| Intra-aggregate FKs survived | 3 ✅ |
| Orphan-monitoring view empty | 0 ✅ |

**Dual-run diff before cutover** — all three collections byte-identical between the monolith
(`:8443`) and the extracted service (`:8444`), and the write path returned the same response shape.
That is what made the cutover uneventful.

**Rollback rehearsed.** Flipping `web-experience.workflow-service-enabled` back to `false` returned
routing to the monolith and all three endpoints kept serving 200. Cutover and rollback are both a
property change, not a redeploy.

**Committed state is the safe one:** the flag defaults to `false`, so the monolith serves Workflow
until someone deliberately flips it after a production soak.

---

## 5. Defects found and fixed during testing

Every one of these was found by running the real stack, not by unit tests.

| # | Defect | Impact | Fix |
|---|---|---|---|
| 1 | `RequestUserResolver` accepted a caller-supplied `userId` when no token was present | **Any client could read/write any tenant's data** | Fallback deleted; 401/403 |
| 2 | DAO reachable unauthenticated | Full DB read/write to anyone who could route to the port | `ServiceTokenFilter`, fails closed |
| 3 | Trust-all TLS in **three** places, incl. Google/Facebook OAuth traffic carrying client secrets | Interceptable service and OAuth traffic | Real certificate verification |
| 4 | Malformed JWT threw an unchecked exception | Unauthenticated caller could force a 500 | Caught, returns 401 |
| 5 | Spring's ERROR dispatch re-ran the filter chain unauthenticated | An honest 404 came back as 403; **broke signup entirely** | `ERROR`/`FORWARD` dispatch permitted |
| 6 | `NOT NULL brand_id` with an app that only wrote `user_id` | **Every insert failed** after the tenancy migration | `sync_brand_tenancy()` bridge trigger |
| 7 | New signups had no brand | Every user created after the migration was broken | `provision_tenancy_for_user()` trigger |
| 8 | `::text` casts in a native query | Hibernate parsed `:` as a parameter → SQL syntax error | `cast(x as text)` |
| 9 | snake_case projection aliases | Returned rows of **all nulls**, surfacing as "no accessible brands" | Quoted camelCase aliases |
| 10 | Duplicate creator returned 502 Bad Gateway | Told the user the service was broken when their input was a duplicate | `DaoExceptionHandler` → 409 |
| 11 | `accounts.legacy_user_id` had no FK | Deleting a user orphaned its account and brand | `ON DELETE SET NULL` |
| 12 | Aggregate invariant violation returned 500 | Told the caller to retry something that could never succeed | → 409 |
| 13 | **FINANCE was treated as brand-scoped** | Finance users had zero accessible brands and **could not log in at all** | FINANCE is account-wide, fixed in both tiers + regression test |

Defect 13 is the one this final round caught. It also revealed a design trap worth remembering: the
"which roles reach all brands" rule was written **twice** — once in SQL, once in Java. They disagreed.
Both now carry a comment pointing at the other.

---

## 6. What is *not* covered

Stated plainly so the report is not read as more than it is.

- **No UI automation.** Every result above is API-level or database-level. The UI was verified by
  build success and manual inspection, not by browser tests.
- **No load or concurrency testing.** The outbox relay uses `FOR UPDATE SKIP LOCKED` so multiple
  instances *should* drain safely, but that has not been tested under real contention.
- **Member invitation is not implemented.** Endpoints and permissions exist; the UI flow to invite a
  user and assign a role does not. Demo memberships were seeded directly.
- **The BFF still holds domain logic.** `AttributionService`, `CouponService`, `PayoutService` and
  `LandingService` were not moved into their contexts.
- **No micro-frontend federation.** See §7.
- **Keystore rotation is outstanding** — the previously committed private key is still in git
  history and must be treated as compromised. See [keystore-rotation.md](keystore-rotation.md).

---

## 7. Phase 5 & 6 — extraction foundation

The decision here changed mid-project, deliberately. The earlier position was that extraction should
wait for a scaling or team driver. The counter-argument — that Claude agents change the cost of the
mechanical work, and that laying the foundation *before* scale pressure is cheaper than retrofitting
under it — is the one being acted on.

So the **prerequisites for extraction are now complete**, while the runtime split itself is left as a
deliberate, reversible next step per context.

### Delivered

| Prerequisite | What it means |
|---|---|
| **BFF split into 7 contexts × 3 layers** | The BFF was still layer-split (all controllers in one package). A service cannot move out while its API layer sits in a shared pile with six others |
| **11 BFF ArchUnit rules** | Boundaries enforced at build time in *both* tiers — 23 rules total |
| **`shared → identity` coupling inverted** | `RequestUserResolver` (used by every context) imported Identity's `SessionService`. Extracting Identity would have broken all seven contexts. Now `shared` owns a `TokenVerifier` contract that Identity implements |
| **8 per-context DB roles** | `svc_identity`…`svc_mapping`, each scoped to its own schema. Verified: `svc_finance` **cannot** write `creator.creators`; `svc_creator` **cannot** write `finance.influencer_payouts`; both **can** publish events and read the tenancy spine |
| **Published contracts** | [contracts/README.md](contracts/README.md) — 104 endpoints mapped to 8 contexts, plus owned tables, ports and events |
| **Route manifest** | `shell/routeManifest.js` declares routes as data with owning context and api slice. Federating a page becomes a one-line change |
| **Extraction runbook** | [EXTRACTION-RUNBOOK.md](EXTRACTION-RUNBOOK.md) — per-context checklist, ordering, known blockers, effort estimates |

### Verified isolation

```sql
svc_finance -> finance.influencer_payouts (own)      allowed
svc_finance -> creator.creators (foreign)            DENIED
svc_finance -> campaign.campaigns (foreign)          DENIED
svc_creator -> finance.influencer_payouts (foreign)  DENIED
svc_creator -> creator.creators (own)                allowed
svc_creator -> shared.domain_events (publish)        allowed
svc_creator -> identity.brands SELECT                allowed
svc_creator -> identity.brands INSERT                DENIED
```

This is what turns the schema split from an organisational convention into a boundary the database
enforces. ArchUnit stops a developer crossing it; these roles stop the *runtime* crossing it.

### Not delivered, and why

- **Separate deployables.** Every prerequisite is in place; the step is now gated on a per-context
  decision, not on missing groundwork. See the runbook's checklist.
- **Module Federation remotes.** Same — the manifest and `SessionContext` make each one a scoped
  change rather than a redesign.

The application still connects as `influencercrm_user`, which legitimately spans contexts in one
connection. Switching a service to its own role is a config change taken at extraction time; doing
it now would break the monolith.

---

## 8. Summary

| Area | Status |
|---|---|
| Security floor | ✅ 13 defects fixed, all verified |
| Solo-brand product | ✅ no regression |
| Agency multi-brand | ✅ working, with per-brand creator terms |
| RBAC + separation of duties | ✅ enforced server-side, gated in UI |
| Session lifecycle | ✅ JWT + rotating refresh tokens |
| Domain events | ✅ outbox → relay → consumer, transactional |
| Context boundaries | ✅ 23 ArchUnit rules (12 DAO + 11 BFF), both proven to fail on violation |
| Extraction prerequisites | ✅ per-context DB roles, published contracts, route manifest, runbook |
| First extraction (Workflow) | ✅ own service + own DB role, dual-run identical, cutover and rollback both proven |
| Data integrity | ✅ reconciliation passes |

**146 / 146 tests passing.**
