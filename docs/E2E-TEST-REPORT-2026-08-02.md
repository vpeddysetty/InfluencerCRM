# End-to-End Test Report — Brand User & Agency Owner

**Date:** 2026-08-02
**Scope:** Full UI-driven journeys for two personas (solo brand `OWNER`, agency `ADMIN`), the
complete role matrix, cross-tenant isolation, and database verification of every journey.
**Result:** **85 / 86 assertions passing** — 70/70 API journeys, 15/16 real-browser UI checks.
The single remaining failure is a pre-existing gap, asserted deliberately so it stays tracked.

**Four defects were found and fixed during this run**, one of them critical.

---

## 1. Environment under test

| Component | Port | Notes |
|---|---|---|
| Digital Presentation Service (DPS) | 8090 | Owns the session; the only origin the browser authenticates against |
| BFF / Web Experience | 8081 | Authorization + tenancy enforcement |
| DAO gateway | 8443 | mTLS to Postgres |
| Context services | 8444–8450 | workflow, identity, creator, campaign, attribution, finance, content |
| Presentation gateway (shell) | 5173 | The origin a user visits |
| Micro-frontends | 5174–5179 | workflow, campaigns, creators, commerce, finance, content |
| Postgres 15 (pgvector) | 5432 | `influencercrm_db`, 26 tables across 9 schemas |
| Redis 7 | 6379 | Server-side session store |

Tests drive the stack exactly as a browser does: an httpOnly `INFLUENCRM_SESSION` cookie plus the
`XSRF-TOKEN` → `X-XSRF-TOKEN` double-submit header, through the `/dps/api/*` proxy. No test holds a
bearer token, because the browser never does.

---

## 2. Credentials used

**Password for every account below: `DemoPass123!`** — seeded local demo accounts only.

### 2.1 Agency owner — journey J-A

| Field | Value |
|---|---|
| Email | `demo.admin@northstar.test` |
| Role | `ADMIN` on account `Northstar Agency (demo)` (`account_type = agency`) |
| User id | `28fcf9ff-aad8-49b2-aaf1-da78cc1618d3` |
| Account id | `dededede-0000-0000-0000-0000000000aa` |
| Brands reachable | `Aurora Beauty (client)` `…b1`, `Lumen Fitness (client)` `…b2` |
| Permissions | 31, incl. `creator:*`, `campaign:*`, `payout:create`, `payout:approve`, `commission:approve` |

### 2.2 Solo brand user — journey J-B

Created through the **real signup flow** during this run, which also tests auto-provisioning.

| Field | Value |
|---|---|
| Email | `e2e.brand.owner@veridianglow.test` |
| Brand name at signup | `Veridian Glow Co` |
| Role | `OWNER` (`account_type = brand`) |
| User id | `bab5f3cf-2339-4269-8835-dca39b4e1100` |
| Account id | `fcc8275c-6b76-41f1-af25-70aac2d38b6d` |
| Brand id | `ee70e0a6-06d5-458a-b929-5b651cd5fb35` |
| Permissions | 32, incl. `account:billing` (an OWNER-only capability) |

### 2.3 Role matrix accounts

| Email | Role | Used to prove |
|---|---|---|
| `demo.analyst@northstar.test` | ANALYST | Read-only; every write refused |
| `demo.marketer@northstar.test` | MARKETER | Campaign/creator work allowed, finance refused |
| `demo.manager@northstar.test` | MANAGER | Can approve a commission, cannot create a payout |
| `demo.finance@northstar.test` | FINANCE | Owns payouts, cannot touch creator data |

---

## 3. Test data created

| Entity | Value | Tenant |
|---|---|---|
| Creator | `@shared_star`, Instagram, 250 000 followers, rate **5000 USD** | Aurora |
| Creator | `@shared_star`, same handle, rate **2000 USD** | Lumen |
| Creator | `@marketer_ok`, TikTok, rate 900 | Aurora (by MARKETER) |
| Creator | `@veridian_muse`, YouTube, 88 000 followers, rate 1500 | Veridian |
| Campaign | `Aurora Summer Glow 2026`, budget 50 000, paid | Aurora |
| Campaign | `Veridian Launch Q3`, budget 12 000, affiliate | Veridian |
| Coupon | `AURORA-STAR-20` — 20 % discount | Aurora |
| Coupon | `VERIDIAN-MUSE-15` — 15 % discount, 12 % commission | Veridian |
| Order | `E2E-ORDER-1` — 420.00 USD sale, 63.00 discount | Veridian |
| Board | `Aurora Creator Pipeline` + 7 stages | Aurora |
| Board | `Veridian Outreach` + 7 stages | Veridian |

Stage template used (matches the UI's `DEFAULT_BOARD_STAGES`):
`Prospect → Outreach → Negotiation → Contracted → In Production → Published → Paid`

---

## 4. Defects found and fixed

### 4.1 CRITICAL — Cross-tenant IDOR, including destructive delete

**Any authenticated user could read *and permanently delete* another tenant's records by ID.**

`findById` and `delete` on three BFF controllers took only the path variable — no `Authorization`
header, no brand resolution — while `list`, `create` and `update` on the same controllers all
resolved and enforced `brandId`. The list endpoints were never affected because they filter by the
resolved brand; a lookup by id carries no such filter.

Proven exploitable during this run, not merely theoretical:

```
solo brand OWNER  GET    /dps/api/creators/{agency-creator-id}   → 200  + PII (name, email)
solo brand OWNER  GET    /dps/api/campaigns/{agency-campaign-id} → 200  + budget
solo brand OWNER  DELETE /dps/api/creators/{agency-creator-id}   → 200
                  → the agency's row count went from 1 to 0
```

**Fixed** in `CreatorsController`, `CampaignsController`, `CampaignCreatorsController` and
`ImportBatchesController` (whose `findById` and `/columns` were also unprotected) by fetching the
record and asserting its `brandId` matches the caller's resolved brand. The pattern follows the
ownership check that already existed on the import-batch delete path.

Refused with **404, not 403** — confirming that an id exists is itself a disclosure when the caller
is not entitled to the row.

After the fix, with the legitimate owner unaffected:

```
cross-tenant GET    → 404      victim row still present → 1
cross-tenant DELETE → 404      owner's own GET          → 200
```

### 4.2 HIGH — The UI could not be logged into at all

`InfluencerUI/vite.config.js` proxied `/api` to `http://localhost:18081`. Nothing listens on 18081
— the BFF is on **8081**. Every `/api` call from the browser returned **502 Bad Gateway**, and since
the shell's login still posts to `/api/auth/login`, *sign-in from a real browser was impossible*.
The wrong port was committed, so this was not a local misconfiguration.

This is the clearest argument for driving a browser rather than only the API: the DPS answered every
curl correctly while the actual application was unusable.

**Fixed** — target corrected to 8081 and made overridable via `VITE_BFF_URL`.

### 4.3 HIGH — Access and refresh tokens written to `localStorage`

The shell persisted a snapshot containing `authToken` and `refreshToken` under
`tejdux_ui_state_v3`. A raw JWT (`eyJraWQiOi…`) was readable by any script on the page — precisely
the XSS token-theft surface the DPS design exists to eliminate. The architecture had moved the
session server-side; this persistence was left behind.

**Fixed** — credentials removed from the snapshot and from the effect's dependencies, no longer
rehydrated from storage, and any pre-existing snapshot is stripped and rewritten on load so
upgrading actually clears the stored credential.

### 4.4 MEDIUM — Writes were not attributed to the acting user

`created_by_user_id` exists on every domain table and the DAO accepts it, but the BFF never sent it,
so rows created through the current path had no audit trail. **Fixed** for the creator and campaign
create paths, taking the id from the verified token — never the request body, since an audit trail a
caller can set is not an audit trail.

Verified afterwards:

| Row | `created_by` |
|---|---|
| `@shared_star` (Aurora) | `demo.admin@northstar.test` |
| `@marketer_ok` | `demo.marketer@northstar.test` |
| `@veridian_muse` | `e2e.brand.owner@veridianglow.test` |

---

## 5. Results

### 5.1 API journeys — 70 / 70

| Group | Assertions | Result |
|---|---:|---|
| J-A agency owner (login → creator → campaign → assignment → coupon → board → card → brand switch) | 26 | all pass |
| Role matrix (ANALYST, MARKETER, MANAGER, FINANCE) | 12 | all pass |
| J-B solo brand owner (signup → CRM → attribution → commission → payout → workflow → content) | 21 | all pass |
| Cross-tenant isolation, CSRF, auth, logout | 11 | all pass |

Security assertions, with the exact status returned:

| ID | Check | Status |
|---|---|---|
| A5 | Duplicate handle within a brand rejected | 409 |
| A14 | Cross-brand creator fetch while switched to Lumen | 404 |
| R1d | ANALYST write | 403 |
| R2c | MARKETER payout creation | 403 |
| R3c | MANAGER payout creation (separation of duties) | 403 |
| R4c | FINANCE creator creation (separation of duties) | 403 |
| X1–X4 | Cross-tenant read of creator/campaign, both directions | 404 |
| X5 | Unauthenticated API call | 401 |
| X6 | Forged session cookie | 401 |
| X7 | State change without `X-XSRF-TOKEN` | 403 |
| L1–L3 | Logout, then session invalid, then API refused | 204 / false / 401 |

The role-matrix payout tests send a **valid** payload, so a 403 proves authorization rather than
validation — an empty-array payload would have passed for the wrong reason.

### 5.2 Real-browser UI (Chromium) — 15 / 16

Both personas driven through the actual login form, with screenshots captured.

| ID | Check | Result |
|---|---|---|
| UI-A1…A3 | ADMIN logs in; Aurora rendered; brand switcher exposes Lumen | pass |
| UI-A4…A6 | Creators, Campaigns, Workflow pages render the created data | pass |
| UI-A7 | No token in `localStorage` / `sessionStorage` | pass *(after 4.3)* |
| UI-A8 | Session cookie invisible to `document.cookie` (httpOnly) | pass |
| UI-A9 | No uncaught console errors | pass |
| UI-B1…B3 | OWNER logs in; own brand rendered; **no agency brand names leak** | pass |
| UI-B4–B5 | Own creator listed; **agency's `@shared_star` absent** | pass |
| UI-B6 | Payouts page shows the settled **$50.40** | pass |
| UI-B7 | Data still loads after a hard page reload | **fail — known gap** |

**UI-B7** is a pre-existing architectural gap, not a regression. The DPS session cookie survives a
reload, but the shell's legacy `/api` data path still authenticates with an in-memory bearer token,
so a hard refresh shows "A valid Authorization Bearer token is required" until the user navigates.
Removing the `localStorage` token (4.3) made this visible; it did not cause it — storing a credential
in `localStorage` is not an acceptable way to paper over it. The fix is to migrate the shell's data
calls onto the `/dps/api/*` cookie proxy the micro-frontends already use. The assertion is left in
the suite so it flips to pass when that lands.

### 5.3 Regression check

`InfluencerWebExperience` — **64 / 64 existing tests pass** (JwtServiceTest, CrossTenantIsolationTest,
RolePermissionsTest, KeyRotationTest, OAuthHandoffServiceTest, BffContextBoundaryTest ArchUnit rules).

---

## 6. Database verification

Full output: `docs/E2E-DB-REPORT-2026-08-02.md`. Highlights:

**Tenancy** — every row created in journey J-A carries the identical `brand_id`
(`dededede-…b1`): creator, campaign, assignment, coupon, board and card. No row in any of the eight
tenant-scoped tables has a NULL `brand_id`.

**Per-brand creator rows** — the deliberate design decision holds:

| Brand | Handle | Rate |
|---|---|---|
| Aurora Beauty (client) | `@shared_star` | 5000.00 |
| Lumen Fitness (client) | `@shared_star` | 2000.00 |

Two distinct rows. Writing 2000 under Lumen did not disturb Aurora's negotiated 5000 — one agency
client never sees another's terms.

**Money chain** — order → attribution → commission → payout, verified by value and not just status:

```
order E2E-ORDER-1   sale 420.00  discount 63.00   status attributed
commission          gross 420.00  amount 50.40    status paid
payout              total 50.40   status paid     provider manual
arithmetic          420.00 × 12% = 50.40          matches = true
```

The commission is linked to its payout by `payout_id`, so the obligation and its settlement are
joined rather than merely coexisting.

**Signup provisioning** — one signup produced a `user`, an `account` (`brand`), a `brand`, and an
active `OWNER` membership, via the `provision_tenancy_for_user` trigger.

**Sessions** — 59 `dps:session:*` keys in Redis; nothing corresponding in the browser.

---

## 7. Recommendations

1. **Migrate the shell's data path to `/dps/api/*`** — closes UI-B7 and removes the last reason for
   the browser to hold a token at all.
2. **Add an ArchUnit rule** asserting every BFF handler with an `{id}` path variable resolves a
   permission. The IDOR was a consistent omission across three controllers; a test would have caught
   it, and would stop it recurring.
3. **Extend the ownership check** to the remaining by-id routes not exercised here (coupons,
   commissions, payouts, workflow cards) — the same shape of gap is plausible there.
4. **Reject unknown fields** on write payloads. `commissionRate` was silently ignored in favour of
   `commission_type`/`commission_value`, producing a commission of 0.00 with a 200 response. A
   caller cannot tell a typo from a success.
5. **Point the seeded demo accounts at documented passwords** — `aurora.brand@example.com` and the
   other pre-existing brand accounts do not accept `DemoPass123!`, so a fresh brand had to be
   created to test that persona.

---

## 8. Reproducing

```bash
# API journeys (70 assertions)
bash e2e.sh          # drives :8090 with cookie + CSRF, exactly as the browser does

# Real-browser journeys (16 assertions, writes screenshots)
cd InfluencerUI && node e2e-ui-check.mjs

# Database verification
bash dbreport.sh
```

Both harnesses are idempotent given the cleanup statements in §3 of the DB report.
