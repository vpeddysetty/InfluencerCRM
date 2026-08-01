# Coupons, Marketplace Attribution & Influencer Revenue — Build Plan

**Status:** Draft for execution · **Author:** brainstorm w/ Claude · **Date:** 2026-07-27

Goal: let brand owners / marketers generate coupons for campaign↔influencer pairs, sync
them to external marketplaces (Shopigy, Shopify, …) through a pluggable adapter layer,
track channel + influencer attribution, process influencer commission payouts, and see a
dashboard of how each influencer drives sales & revenue.

---

## 0. What already exists (do NOT rebuild)

A prior increment (2026-07-19) already shipped the *plumbing* for codes + attribution.
It is wired end-to-end through the DAO and BFF but **has no UI, no marketplace sync, no
commission/payout logic, and no dashboard**. Build on it; don't duplicate it.

| Layer | Exists today | File(s) |
|---|---|---|
| Schema | `influencer_campaign_codes`, `influencer_sale_attributions` tables; enums `attribution_platform`, `attribution_status` | `schema/migrations/2026_07_19_influencer_code_attribution_tracking.sql` |
| DAO | Full CRUD controllers + repos + entities | `InfluencerCampaignCodeController`, `InfluencerSaleAttributionController` |
| BFF | Pass-through CRUD under `/api/influencer-campaign-codes` and `/api/influencer-sale-attributions` | `InfluencerTrackingController` |
| Payments (adjacent) | `creator-workflow-payments` CRUD (per campaign-creator) — reuse as payout substrate or supersede | see `INTEGRATION_ENDPOINT_TODOS.md` §8 |
| UI | **nothing** — no coupon page, no dashboard | — |

**Existing `influencer_campaign_codes` columns:** `user_id, campaign_id, creator_id,
campaign_creator_id, code, code_type, landing_url, starts_at, ends_at, is_active, metadata(jsonb)`.
Unique on `(user_id, code)`.

**Existing `influencer_sale_attributions` columns:** `user_id, campaign_code_id, campaign_id,
creator_id, campaign_creator_id, platform, status, order_id, order_line_id, customer_external_id,
sale_amount, discount_amount, net_amount, commission_amount, currency, occurred_at, tracked_at,
raw_payload(jsonb)`.

The model is already tenant-scoped (every row has `user_id`) and already carries a
`commission_amount` — so the accounting spine is present; what's missing is generation UX,
external sync, payout settlement, and reporting.

---

## 1. Architecture at a glance

```
 UI (React)                 BFF (Spring, external egress + SPI)          DAO (Spring/JPA)         External
 ┌──────────────┐           ┌───────────────────────────────┐           ┌──────────────┐        ┌──────────┐
 │ Coupon mgr   │──REST────▶│ TrackingController (exists)    │──HTTP────▶│ codes/attrib │        │ Shopigy  │
 │ Marketplace  │──REST────▶│ MarketplaceConnectionController│           │ connections  │◀──sync──│ Shopify  │
 │ settings     │           │ CouponController (generate)    │           │ commissions  │        │ Woo …    │
 │ Dashboard    │──REST────▶│ DashboardController (aggregates)│          │ payouts      │        └────┬─────┘
 └──────────────┘           │ WebhookController ◀──────────────────── webhook POST ─────────────────┘
                            │  ▼ MarketplaceProvider SPI (registry) │
                            │  ▼ PayoutProvider SPI (registry)      │
                            └───────────────────────────────┘
```

Key placement decision: **the SPI + registry live in the BFF**, which already owns all
external egress. The DAO stays a dumb tenant-scoped datastore. The UI never talks to a
marketplace directly.

---

## 2. The pluggable marketplace layer (the "platformize" ask)

Adding a new marketplace must be *"write one adapter class, register nothing manually."*

### 2.1 The SPI
```java
interface MarketplaceProvider {
    String key();                              // "shopigy", "shopify", "mock"
    String displayName();
    Set<Capability> capabilities();            // CREATE_COUPON, WEBHOOK_ORDERS, POLL_ORDERS, AFFILIATE_LINK
    ConnectionResult connect(Map<String,String> credentials);   // validate + return account handle
    ExternalCoupon createCoupon(CouponSpec spec, Connection conn);
    void updateCoupon(String externalId, CouponSpec spec, Connection conn);
    void deactivateCoupon(String externalId, Connection conn);
    List<OrderEvent> fetchOrders(Instant since, Connection conn);   // polling fallback
    boolean verifyWebhook(byte[] body, Map<String,String> headers, Connection conn);
    OrderEvent normalizeOrderEvent(JsonNode raw);  // → canonical shape
}
```

### 2.2 Design rules that make it actually pluggable
1. **Canonical DTOs in the middle** — `CouponSpec` and `OrderEvent` are *our* shapes. Adapters
   translate to/from vendor APIs. Attribution + dashboard code only ever sees canonical shapes,
   so a new marketplace never touches them.
2. **Capability flags, not assumptions** — UI reads `capabilities()` to gray out unsupported
   features per connected store (some marketplaces have no server-side coupon API, or no webhooks).
3. **Auto-discovery registry** — each adapter is a `@Component implements MarketplaceProvider`,
   injected as `List<MarketplaceProvider>` and keyed by `key()`. New marketplace = drop a class in
   `com.influencer.webe.marketplace.provider.*`. Zero wiring.
4. **Two ingestion modes, one normalizer** — webhook push (preferred) and polling pull both feed
   `normalizeOrderEvent` → same attribution pipeline.
5. **`MockMarketplaceProvider`** — full SPI over an in-memory store. Lets us build coupon UX +
   attribution + dashboard **before Shopigy's real API exists**, and gives deterministic tests.

### 2.3 New persistence for connections
`marketplace_connections`: `id, user_id, provider_key, display_name, status,
credentials_encrypted, external_account_ref, sync_cursor(timestamptz), created_at, updated_at`.

> ⚠️ **Secrets.** `credentials_encrypted` must be envelope-encrypted at rest (NOT a plaintext
> column, NOT `application.properties`). The BFF's trust-all X509 manager must NOT be in the path
> of marketplace API calls — use a normal validating HTTP client for these. See §7.

---

## 3. Data model additions

New migration `schema/migrations/2026_07_27_coupons_marketplace_commissions.sql`:

1. **Extend `influencer_campaign_codes`** (additive, nullable): `marketplace_connection_id uuid`,
   `external_coupon_id text`, `discount_type text`, `discount_value numeric(12,2)`,
   `commission_type text`, `commission_value numeric(12,2)`, `channel text`, `ref_slug text`,
   `sync_status text default 'local'`. Keeps the existing table as the coupon spine.
2. **`marketplace_connections`** — see §2.3.
3. **`influencer_commissions`** — one accrued row per attributed conversion:
   `id, user_id, attribution_id, creator_id, campaign_id, gross_sale, commission_amount,
   currency, status('pending'|'approved'|'paid'|'clawed_back'|'void'), approved_at, payout_id,
   created_at, updated_at`. Append-only in spirit; clawbacks are new rows / status transitions.
4. **`influencer_payouts`** — a batch settling many commissions to one creator:
   `id, user_id, creator_id, period_start, period_end, total_amount, currency, method,
   provider_key, provider_ref, status('draft'|'processing'|'paid'|'failed'), created_at, paid_at`.
5. **`daily_attribution_stats`** (rollup for dashboard): `user_id, day, creator_id, campaign_id,
   channel, clicks, orders, gross_sales, discounts, commission, refunds` — refreshed by a job.

All tables `user_id`-scoped; FKs are raw UUID columns per repo convention (no JPA associations).

---

## 4. Attribution pipeline

```
click/scan → landing (ref cookie) → checkout (code) → order webhook/poll
   → normalizeOrderEvent → attribution engine → influencer_sale_attributions row
   → commission accrual (influencer_commissions) → nightly rollup (daily_attribution_stats)
```

- **Channel tagging:** encode channel in `ref_slug`/UTM (`?ch=tiktok`) and/or per-channel code
  variants (`JADE-TT`). This is what powers "Jade converts 3× better on TikTok" in the dashboard.
- **Attribution model:** start **last-touch on the code** (simple, defensible). Store an
  `attribution_model` config field so first/multi-touch can be added later without migration.
- **Idempotency:** dedupe on `(user_id, order_id, order_line_id)` — webhooks retry.
- **Refunds/clawbacks:** an `attribution_status` transition to `refunded` must reverse the
  commission (new `clawed_back` row), never silently mutate a paid one.
- **Reconciliation job:** scheduled `fetchOrders(since=sync_cursor)` catches missed webhooks —
  the classic "silently lost 5% of orders" failure mode.

---

## 5. Payments / payouts (paying influencers, not shopper checkout)

This is **accounts-payable to influencers**, not checkout processing (the marketplace does that).

- **`PayoutProvider` SPI** mirrors the marketplace SPI (same registry pattern): `key()`,
  `capabilities()`, `pay(PayoutRequest)`, `status(ref)`.
- **`ManualPayoutProvider`** first (mark-as-paid + record reference). Most small brands start by
  paying via PayPal manually — this ships the full workflow with zero external integration.
  Stripe Connect / PayPal Payouts / Wise adapters slot in later, no core changes.
- **Approval gate:** commissions sit `pending` through a hold window (refund protection) →
  `approved` → batched into a `payout` → `paid`. Immutable ledger: reverse, don't edit.

---

## 6. Dashboard — "how each influencer drives revenue"

Reads **pre-aggregated `daily_attribution_stats`**, not raw event tables (live aggregation is how
dashboards die). New BFF endpoint `GET /api/analytics/influencer-revenue?...` returns shaped rollups.

- **KPI tiles:** attributed revenue, orders, AOV, commission owed, ROI (revenue ÷ total influencer
  cost), redemption rate.
- **Influencer leaderboard** (the money view): one row per influencer — revenue · orders · AOV ·
  redemption rate · commission · ROI · per-channel sparkline.
- **Drill-downs:** per-influencer (their codes, channel split, revenue-over-time), per-campaign ROI
  (revenue vs. commissions **+ flat fees from `workflow_cards.agreed_fee`**), channel comparison,
  conversion funnel (clicks → applies → purchases).
- **UI note:** current UI is a `localStorage`-backed god-component with no charting. Dashboard needs
  a real chart lib + a dedicated route; follow the `dataviz` skill for palette/formatting.

---

## 7. Cross-cutting prerequisites (treat as part of the work, not follow-ups)

These are *money and storefront credentials*. The repo's known gaps make them load-bearing here.

- **Authz / IDOR:** coupons, commissions, payouts, dashboards must enforce ownership. Today
  `RequestUserResolver` honors caller-supplied `?userId=` and `{id}` endpoints skip ownership checks
  → any brand could read another's revenue or redirect payouts. Add an ownership filter as part of
  Phase 1, at minimum on the new money endpoints.
- **Secrets at rest:** marketplace + payout credentials need envelope encryption; keep them out of
  `application.properties` and out of the trust-all TLS egress path.
- **Webhook auth:** verify signatures (`verifyWebhook`) — the webhook endpoint is public.

---

## 8. Phased delivery

Each phase is independently demoable. The `MockMarketplaceProvider` lets Phases 2–5 proceed with
no real Shopigy API.

### Phase 0 — Foundation & de-risk (small) — ✅ DONE (2026-07-27)
- ✅ New migration (§3) — `2026_07_27_coupons_marketplace_commissions.sql`: additive coupon columns
  on `influencer_campaign_codes` + `marketplace_connections`, `influencer_commissions`,
  `influencer_payouts`, `daily_attribution_stats`. Registered in `zzz_apply_migrations.sql`.
- ✅ DAO entities/repos/controllers for the 4 new tables + extended `InfluencerCampaignCode`
  entity/controller (follows existing flat-entity convention). DAO module compiles clean.
- ⬜ Ownership-filter helper on the new money endpoints (§7 authz) — **deferred to Phase 1**
  (lands with the BFF layer, where request→user resolution happens).
- **Demo:** tables exist; DAO CRUD returns tenant-scoped rows.
- **Next:** run the migration against the live DB (recreate the Postgres container, or `\i` the
  new migration file manually), then start Phase 1 (BFF `CouponController` + UI Coupons page).

### Phase 1 — Coupon generation UX (the headline ask) — ✅ DONE (2026-07-27)
- ✅ BFF `CouponController` + `CouponService`: `POST /api/coupons/generate` (single) and
  `POST /api/coupons/generate-bulk` (one per creator on a campaign). Template patterns
  (`{CREATOR}`, `{BRAND}`, `{DISCOUNT}`, `{CHANNEL}`, `{RANDOM}`), collision/uniqueness/profanity
  checks, vanity + randomized modes, per-tenant uniqueness enforced against existing codes.
- ✅ Extended `campaignCode` shaper for discount/commission/channel/refSlug/sync fields.
- ✅ BFF pass-through controllers for `marketplace-connections`, `influencer-commissions`,
  `influencer-payouts`, `daily-attribution-stats` (+ shapers; credentials never exposed).
- ✅ UI **Coupons page** (`CouponsPage.jsx`, route `/coupons`, nav link): single + bulk generate,
  template/vanity/random code modes, discount/commission/channel inputs, campaign-scoped creator
  picker, coupon list with campaign filter, copy-code / copy-tracking-link / delete.
- ✅ Wired into `App.jsx` (state, loader in `refreshWorkspaceData`, handlers, localStorage snapshot).
- All three modules compile clean (DAO + BFF `mvn compile`, UI `vite build`).
- **Demo:** marketer generates 1 and N codes for a campaign; codes are unique & listed. *(Needs the
  Phase 0 migration applied to the live DB and the DAO/BFF restarted — not yet run.)*
- ⬜ Ownership-filter helper on money endpoints (carried from Phase 0) — still pending; the BFF
  pass-throughs currently inherit the systemic `?userId=`/IDOR gaps. Fold into a hardening pass.

### Phase 2 — Marketplace SPI + Mock adapter — ✅ DONE (2026-07-27)
- ✅ SPI (`com.influencer.webe.marketplace`): `MarketplaceProvider` interface + canonical DTOs
  (`CouponSpec`, `ExternalCoupon`, `OrderEvent`, `Connection`, `ConnectionResult`, `Capability`
  enum) + `MarketplaceProviderRegistry` (auto-discovery via injected `List<MarketplaceProvider>`,
  keyed by `key()` — new marketplace = one `@Component`, zero wiring).
- ✅ `MockMarketplaceProvider` (in-memory) implementing the full SPI (connect/create/update/
  deactivate/fetchOrders/verifyWebhook/normalizeOrderEvent); declares all four capabilities.
- ✅ `MarketplaceService` (connect handshake → persist connection; push coupon → adapter →
  write back `externalCouponId` + `syncStatus`; ownership checks on coupon + connection).
- ✅ BFF endpoints: `GET /api/marketplace-providers`, `POST /api/marketplace-connections/connect`,
  `POST /api/coupons/{id}/push` (on CouponController).
- ✅ UI **Marketplace page** (`MarketplacePage.jsx`, route `/marketplace`, nav link): provider
  catalog with capability chips, connect form (provider + shop + apiKey), connected-store list
  with disconnect. Coupons page gained a **capability-gated "Push to store" / "Re-push"** action
  (store picker when >1 connection).
- BFF `mvn compile` + UI `vite build` both clean.
- **Demo:** connect a Mock store, push a coupon, `externalCouponId` + `syncStatus=synced` populate.
  *(Live run still gated on the Phase 0 migration being applied + stack restart.)*
- **Security note carried forward:** credentials are JSON-serialized into `credentials_encrypted`
  as-is (NOT yet encrypted) and the trust-all TLS client is still in the egress path — both are
  Phase 6 prerequisites before any real Shopify credentials flow through. `MarketplaceService`
  *does* enforce ownership on coupon + connection (partial authz hardening).

### Phase 3 — Attribution ingestion — ✅ DONE (2026-07-31)
- ✅ `AttributionService` pipeline: provider.normalizeOrderEvent → resolve coupon by code →
  dedupe on (orderId, orderLineId) → write `influencer_sale_attributions` → accrue
  `influencer_commissions`. Last-touch on the code. Refund/cancel → attribution `refunded` +
  commission `clawed_back` (paid commissions never clawed).
- ✅ `WebhookController`: public `POST /api/webhooks/marketplace/{providerKey}` (adapter verifies
  signature; mock trusts all) + auth-scoped `POST /api/attribution/simulate` test hook.
- **Verified live:** attributed / duplicate-deduped / unknown-unattributed / refund-clawback all pass.

### Phase 4 — Revenue dashboard — ✅ DONE (2026-07-31)
- ✅ `AnalyticsService` computes KPI tiles, per-influencer leaderboard, per-channel breakdown from
  attributions + commissions; ROI folds in flat fees (`workflow_cards.agreedFee`) + commission.
  (`daily_attribution_stats` table exists for future materialization; live-compute for now.)
- ✅ `GET /api/analytics/influencer-revenue`.
- ✅ UI **Dashboard** (`DashboardPage.jsx`, `/dashboard`): KPI tiles, leaderboard w/ revenue-share
  bars, channel breakdown, + a "simulate order" tool that drives the attribution pipeline.
- **Verified live:** $300 sale → $30 commission (10%) → ROI 10.0×; refunded revenue excluded.

### Phase 5 — Payouts — ✅ DONE (2026-07-31)
- ✅ `PayoutProvider` SPI + `PayoutProviderRegistry` + `ManualPayoutProvider` (records paid, no
  external call). `PayoutService`: approve commission (pending→approved gate), create payout batch
  (sum approved for a creator → payout → provider.pay → flip commissions to paid + link payoutId).
- ✅ Endpoints: `GET /api/payout-providers`, `POST /api/influencer-commissions/{id}/approve`,
  `POST /api/influencer-payouts/create`.
- ✅ UI **Payouts** (`PayoutsPage.jsx`, `/payouts`): pending commissions w/ Approve, approved-payable
  grouped per creator w/ Create-payout, payout history.
- **Verified live:** approve → create payout (paid) → commission flips to `paid`; history shows batch.

### Test outcome (2026-07-31)
Full stack brought up (Postgres + DAO 8443 + BFF 18081 + Vite 5173), Phase 0 migration applied.
**API E2E: 27/27 pass. UI E2E (Playwright): 17/17 pass.** Fixes required — see the E2E report
`docs/coupon-attribution-e2e-report.md`.

### Phase 6 (later) — Real marketplace + real payout adapters
- Real `ShopigyProvider` (and/or Shopify/Woo) against live API + credentials.
- Stripe Connect / PayPal Payouts adapter.
- Envelope-encryption for credentials; harden webhook + egress TLS (§7).
- Optional agent_service use: LLM-suggested codes, attribution fraud/anomaly detection.

---

## 8b. Content creation — campaign brief + personalized landing pages

Decisions locked (2026-08-01):
- **Landing model = brand template + per-creator personalization** (one hosted page per coupon).
- **Scope = brief + hosted landing pages in-app** (InfluenCRM owns the whole conversion surface).

Guiding principle: **the brand owns the message and the frame; the creator owns the voice.**
Content splits into distinct surfaces with distinct owners:

| Surface | Author | Notes |
|---|---|---|
| Campaign **brief** (goals, dos/don'ts, assets, hashtags, FTC/ASA disclosure, talking points) | Brand | Creator consumes it |
| Creator's **channel posts** | Creator | Off-platform; approve via workflow board, don't author |
| **Landing page** (coupon `landingUrl` target) | Brand template + creator personalization slot | Hosted in-app; the conversion + click-attribution surface |
| **Coupon** | Brand | Already built (Phase 1) |

### Architecture

```
Campaign
  ├── campaign_brief (1:1)   — brand-authored rich content + assets
  └── landing_template (1:1) — ordered content blocks + theme
        └── per coupon: public_slug + creator personalization → hosted page
              /s/{templateSlug}/{creatorSlug} → renders template + creator identity + coupon code
              (public route; emits a click/landing attribution event → Phase 3 pipeline)
```

Key rules:
- **Block model, not freeform HTML** — a landing page is an ordered list of typed blocks
  (`hero`, `richText`, `image`, `productCta`, `couponBlock`, `legal`). Rendered server-side through
  strict sanitization. Never store-and-echo raw HTML (public page = XSS/brand-safety surface).
- **Token personalization** — the renderer fills `{{creator.name}}`, `{{creator.photo}}`,
  `{{coupon.code}}`, `{{discount}}`, plus a creator-editable `personalBlurb` + optional `embedUrl`.
- **Attribution tie-in** — the hosted page captures the **click/landing** half of the funnel
  (upgrades the dashboard from purchases-only to clicks → applies → purchases).

### Data model additions (new migration `2026_08_01_content_creation.sql`)
- `campaign_briefs` — `id, user_id, campaign_id (unique), content(jsonb: rich text/talking points),
  assets(jsonb), hashtags(jsonb), disclosure_text, status, created_at, updated_at`.
- `landing_templates` — `id, user_id, campaign_id (unique), public_slug (unique), name,
  blocks(jsonb ordered), theme(jsonb), status('draft'|'published'), created_at, updated_at`.
- Coupon personalization: add `public_slug, personal_blurb, embed_url, personalization_status`
  to `influencer_campaign_codes` (additive) — each coupon resolves to a personalized page.
- `landing_page_views` (Phase 2 attribution tie-in) — `id, user_id, campaign_code_id, occurred_at,
  referrer, user_agent` for the click/landing funnel step.

All `user_id`-scoped; jsonb fields use `@JdbcTypeCode(SqlTypes.JSON)` and enum-free text columns
(learned from the Phase 3 E2E: the DAO JDBC URL now carries `?stringtype=unspecified`).

### Phased delivery (each phase: build → regression-test UI+API → fix → restart → retest)

**Content Phase 1 — Campaign brief. ✅ DONE (2026-08-01).** Schema (`campaign_briefs`, unique per
campaign) + DAO entity/repo/controller + BFF `CampaignBriefsController` (jsonb stringify/parse) +
`campaignBrief` shaper + UI `ContentPage.jsx` (`/content`, nav link): campaign picker, summary/goals/
dos/donts/talking-points, hashtags, brand assets (label|url), disclosure, status; create↔update.
**Verified:** API regression 36/36 (27 prior + 9 brief); UI regression 20/20 (Playwright). No
regressions. jsonb round-trips correctly; duplicate-brief-per-campaign rejected by unique constraint.

**Content Phase 2 — Landing template + hosted public page. ✅ DONE (2026-08-01).**
`landing_templates` + `landing_page_views` + coupon personalization columns (DAO entities/repos/
controllers). `LandingService`: upsert template per campaign (auto public_slug + assigns per-coupon
public_slug), resolve `slug/creator → coupon → template`, render **sanitized** HTML with token fill
(`{{creator.name}}`, `{{coupon.code}}`, `{{discount}}`), record a `landing_page_views` row.
`LandingController`: brand-auth'd `GET /api/landing-templates` + `POST /save`, **public**
`GET /s/{slug}/{creator}` (no auth), `GET /api/landing-page-views`. Block types: hero/richText/image/
couponBlock/productCta/legal. UI **landing builder** on `ContentPage` (block add/reorder/edit,
per-creator preview links). **Verified:** API 45/45 (36 prior + 9 landing); UI 21/21. Public page
renders personalized, tokens resolved, HTML-escaped, click recorded, unknown slug → 404.
**Fix during test:** added missing BFF `GET /api/landing-page-views` pass-through (404 → fixed →
BFF recompiled/restarted → green).

**Content Phase 3 — Creator personalization + approval. ✅ DONE (2026-08-01).** BFF
`POST /api/coupons/{id}/personalize` (blurb/embed → status `pending`) +
`POST /api/coupons/{id}/personalization/{approve|reject}` (ownership-checked). `LandingService`
renders the blurb **only when `personalizationStatus === 'approved'`**. UI: per-coupon
personalization panel on `CouponsPage` (status pill, blurb input, submit, approve/reject when pending).
**Verified:** API 51/51; UI 23/23. Key security property confirmed live — unapproved creator content
never renders on the public page; approved does; invalid decision → 400.

**Content Phase 4 — LLM draft assist (agent_service). ✅ DONE (2026-08-01).** agent_service
`POST /content/draft` (OpenAI via existing `OpenAIAdvisor`; **deterministic heuristic fallback** when
the model is unavailable). BFF `AgentMappingClient.draftContent` + `POST /api/content/draft` proxy
(auth-gated). UI "✨ Draft with AI" button on `ContentPage` fills the brief form from the draft.
**Verified:** API 55/55; UI 24/24. LLM returned real drafts (source=llm, e.g. "Step Into Comfort
with Acme Sneakers"); invalid kind → 400. **Note:** confirmed the committed `OPENAI_API_KEY` in
`.env` is a LIVE working credential — flagged in the E2E report for rotation.

### Content-phase test summary (2026-08-01)
Full stack (Postgres + DAO 8443 + BFF 18081 + agent_service 8000 + Vite 5173). Content migration
applied. Every content phase build → regression-tested (API + Playwright UI) → fixed → restarted →
retested green. Cumulative final: **API 55/55, UI 24/24.** One product fix during testing (missing
BFF `GET /api/landing-page-views`); two test-harness timing fixes. No product regressions across the
original coupon/marketplace/attribution/dashboard/payout suite.

---

## 9. Open questions to resolve before Phase 1

1. **Shopigy** — is this a real marketplace with a public API, or a placeholder name? Confirms
   whether Phase 6 targets a real spec.
2. **Coupon ↔ creator cardinality** — enforce one coupon per (campaign, creator) pair, or allow
   many (e.g. per-channel variants)? (Plan assumes many, keyed by `channel`.)
3. **Commission source of truth** — reuse existing `creator-workflow-payments`, or make
   `influencer_payouts` the new canonical payout store? (Plan proposes the latter.)
4. **Charting library** — pick one for the dashboard (Recharts is the common React choice).
5. **Attribution window** — default hold/refund window before a commission is payable (e.g. 14/30d)?

---

## 10. First execution step (when we start)

Phase 0 migration + DAO scaffolding for the four new tables, plus the ownership-filter helper —
it unblocks every later phase and is low-risk/additive. Everything after can proceed against the
Mock adapter without any external dependency.
