# End-to-End Test Report — Coupons, Marketplace, Attribution, Payouts

**Date:** 2026-07-31 · **Scope:** Phases 0–5 of `coupon-attribution-plan.md`
**Result:** ✅ **API 27/27 pass · UI 17/17 pass** (after 3 fixes, re-tested green)

---

## 1. Environment under test

| Component | Port | How launched | Status |
|---|---|---|---|
| Postgres 15 (pgvector) | 5432 | `docker compose up -d postgres` | healthy |
| InfluencerDAO | 8443 (HTTPS) | `mvn -o spring-boot:run` (module dir) | up |
| InfluencerWebExperience (BFF) | 18081 | `mvn -o spring-boot:run --server.port=18081` | up |
| InfluencerUI (Vite) | 5173 | `npm run dev` (proxies `/api` → 18081) | up |

Phase 0 migration `2026_07_27_coupons_marketplace_commissions.sql` was applied to the live DB via
`psql -f` inside the container (additive; no data loss). All 4 new tables + 7 new coupon columns
verified present. Provider registries auto-discovered: marketplace `mock`, payout `manual`.

---

## 2. Issues found & fixed

All three were **pre-existing latent defects** in the (previously unexercised) attribution
foundation, surfaced the moment real inserts ran through it. Each was fixed, the affected service
recompiled, the DAO restarted, and the suite re-run.

### Issue 1 — jsonb columns bound as `varchar` → 500 on coupon insert
`influencer_campaign_codes.metadata`, `marketplace_connections.metadata`, and
`influencer_sale_attributions.raw_payload` are `jsonb` columns mapped to `String` fields **without**
`@JdbcTypeCode(SqlTypes.JSON)`. Postgres rejected the `varchar` bind:
`column "metadata" is of type jsonb but expression is of type character varying`.
**Fix:** added `@JdbcTypeCode(SqlTypes.JSON)` to all three fields — matching the established pattern
already used on `Campaign.customAttributes`, `ImportBatch.columnMapping`, etc.

### Issue 2 — Postgres enum columns bound as `varchar` → 500 on attribution insert
`influencer_sale_attributions.platform` / `.status` are Postgres enums (`attribution_platform`,
`attribution_status`). Hibernate bound the String as `varchar`; PG won't implicitly coerce:
`column "platform" is of type attribution_platform but expression is of type character varying`.
**Fix:** added `?stringtype=unspecified` to the DAO JDBC URL so the driver sends untyped string
literals PG can coerce to the enum. Harmless to the jsonb columns (those set their SQL type explicitly).

### Issue 3 — over-strict test assertion (not a product bug)
Dashboard revenue returned `100.0`; the test compared the string `'100.00'`. Value was correct;
**fixed the assertion** to compare numerically (`[decimal]`).

---

## 3. API E2E results (27/27)

Driven against the BFF on 18081 with a fresh signup per run (idempotent).

| # | Check | Phase | Result |
|---|---|---|---|
| 1–2 | signup returns token + userId | auth | ✅ |
| 3–4 | create campaign + creators | setup | ✅ |
| 5–7 | single coupon: created, commission fields, syncStatus=local | 1 | ✅ |
| 8 | template `{CREATOR}{DISCOUNT}` → `JADE15` | 1 | ✅ |
| 9 | duplicate code rejected (409) | 1 | ✅ |
| 10 | bulk-generate → 2 coupons | 1 | ✅ |
| 11 | coupons listed (≥4) | 1 | ✅ |
| 12 | marketplace connect (mock) | 2 | ✅ |
| 13 | credentials NOT exposed in response | 2 (security) | ✅ |
| 14 | coupon push → syncStatus=synced + externalCouponId | 2 | ✅ |
| 15–16 | order attributed; commission = 10% of $200 = $20.00 | 3 | ✅ |
| 17 | duplicate order deduped | 3 | ✅ |
| 18 | second order (different coupon) attributed | 3 | ✅ |
| 19 | unknown code → unattributed | 3 | ✅ |
| 20 | refund → attribution refunded + commission clawed back | 3 | ✅ |
| 21–23 | dashboard KPIs present; leaderboard populated; refunded revenue excluded | 4 | ✅ |
| 24 | pending commission exists | 5 | ✅ |
| 25 | commission approved (pending→approved) | 5 | ✅ |
| 26 | payout created & paid (manual provider) | 5 | ✅ |
| 27 | commission flipped to paid + linked to payout | 5 | ✅ |

---

## 4. UI E2E results (17/17) — Playwright, headless Chromium

Full browser flow against 5173. Screenshots in `InfluencerUI/e2e-shots/c01…c09-*.png`.

| Check | Result |
|---|---|
| signup → workspace loads | ✅ |
| campaign created (Campaigns page) | ✅ |
| creator created (Creators page) | ✅ |
| Coupons page loads | ✅ |
| single coupon appears in list | ✅ |
| Marketplace providers listed (capability chips) | ✅ |
| store connected | ✅ |
| push button appears after connect (capability-gated) | ✅ |
| coupon pushed → shows Synced | ✅ |
| Dashboard loads | ✅ |
| coupon selectable in simulate tool | ✅ |
| simulate order → outcome attributed | ✅ |
| dashboard shows $300 revenue | ✅ |
| Payouts page loads | ✅ |
| pending commission → Approve action | ✅ |
| approved commission → Create payout action | ✅ |
| payout succeeded (history shows batch) | ✅ |

Visual confirmation (`c08`): KPI tiles Revenue $300 · Orders 1 · AOV $300 · Commission $30 ·
Cost $30 · **ROI 10.00×**, leaderboard with revenue-share bar, channel breakdown. Payouts (`c09`):
"Paid … $30.00 (paid)", pending 0, **Payout history (1)**. All 8 nav tabs render.

---

## 5. Restarts performed

DAO restarted 3× (once per code/config fix: jsonb fix #1, jsonb fix #2, enum JDBC fix), each
followed by a full re-run of the API suite. BFF restarted once after the Phase 3–5 controllers
compiled. UI hot-reloads (no restart needed). Final green run was against the last restarted stack.

---

## 6. Known limitations / carried-forward items (NOT bugs found in test)

These are deliberate scope boundaries recorded in the plan, not test failures:

1. **Authz gaps on plain CRUD pass-throughs** — the `?userId=`/IDOR gaps remain on the generic
   list/CRUD endpoints. The *money-moving* paths (marketplace push, payout, commission approve) DO
   enforce ownership. A dedicated hardening pass is still owed.
2. **Credentials not encrypted at rest** — marketplace credentials are JSON-serialized into
   `credentials_encrypted` as-is; envelope encryption is a Phase 6 prerequisite before real Shopify
   keys flow. Trust-all TLS is still in the BFF egress path.
3. **Attribution is live-computed** — `daily_attribution_stats` exists but isn't materialized by a
   job yet; fine at current scale, revisit for volume.
4. **No real Shopify adapter yet** — Phase 6. The Mock adapter exercises the full SPI.

## 7. ⚠️ Unrelated finding (flagging, not in scope)

`.env` at the repo root contains what appears to be a **real OpenAI API key committed to the repo**
(`OPENAI_API_KEY=sk-proj-…`). `.env` is gitignored, but if it was ever committed or shared this key
is exposed and should be rotated. Recommend verifying `git log -- .env` and rotating if needed.
