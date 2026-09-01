> **ARCHIVED 2026-08-19 — superseded by [MASTER-ROADMAP.md](../../../MASTER-ROADMAP.md).**
> Kept for its reasoning, not its status. Every status claim below is stale, and several
> were wrong when written — the code had moved past them. **Do not schedule from this**
> **document.** Still worth reading for: §0 "the asymmetry" — strong where buyers cannot see, absent where they look first. The single best paragraph in the repo.

# Product Gap Analysis — InfluenCRM

**Date:** 2026-08-06
**Companion to:** [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) (strategic input) · [STRATEGIC-ROADMAP.md](STRATEGIC-ROADMAP.md) (what to do about it)
**Method:** every claim below is grounded in a repo document or in source read directly. Where docs and code disagreed, code won — and the disagreements are called out.

---

## 0. The asymmetry, stated first

This is the finding that should govern every prioritisation decision, and it is uncomfortable.

**InfluenCRM is strong precisely where buyers cannot see, and absent precisely where they look first.**

| | Backend guarantees | Commercial layer |
|---|---|---|
| **Examples** | Tenant isolation, per-brand negotiated rates, RBAC with separation of duties, immutable audit trails, ArchUnit-enforced boundaries | Billing, invitations that actually send, client reporting, exports, a second brand you can create in the UI |
| **State** | Built, live-verified, genuinely excellent | Absent, dead-coded, or mocked |
| **When a buyer discovers it** | Month six, if ever | **Minute four of the first demo** |
| **What it wins you** | Renewal, trust, agency compliance sign-off | **The right to have a month six at all** |

Six migration phases produced a platform where `ANALYST` cannot mutate anything via direct API calls ([docs/ddd-roadmap.md](docs/ddd-roadmap.md) Phase 3 completion record), where `MANAGER` can approve a commission but not settle it, and where two competing brands under one agency hold the same creator at different rates with proven isolation. That is real engineering and it is rare.

It is also invisible in a demo. What a prospect sees in the first ten minutes is: a page that cannot be published to their domain, an invitation that produces a token they must email themselves, a dashboard that covers one brand, no way to export anything, and no way to pay. **The depth is real and the shopfront is not.**

The rest of this document is that thesis, itemised.

> **Inference, labelled as such:** the sequencing that produced this asymmetry was rational. [docs/architecture-migration-plan.md](docs/architecture-migration-plan.md) §9 recommends Phases 0–3 "unconditionally" because they fix live security defects, and it was right — you cannot multi-tenant on top of a system where `RequestUserResolver` honoured a caller-supplied `userId` (§2.2). The security floor genuinely had to come first. The gap is not that the wrong thing was built; it is that the commercial layer was never scheduled after it.

---

## 1. Built and verified

Claims in this section are backed by live-test records, not by "the code exists".

### 1.1 Tenancy and access control

| Capability | Evidence |
|---|---|
| Multi-brand tenancy with proven isolation | [docs/ddd-roadmap.md](docs/ddd-roadmap.md) Phase 2 completion record: `Alpha creators / Beta → 1 / 1`; `Brand from another account → 403` |
| **Per-brand negotiated rates for a shared creator** | Phase 2 record: `@shared_star → Alpha → 200 (preferred_rate 5000)` / `→ Beta → 200 (preferred_rate 2000)`. Confirmed in source: `preferred_rate` is a column on the per-brand `creators` row (`InfluencerDAO/.../creator/domain/Creator.java:148`) |
| RBAC, 6 roles × 31 permissions, one matrix | Phase 3 record: `RolePermissions` — "the matrix lives in exactly one file"; 38 endpoints guarded |
| Separation of duties | Phase 3 record, verified live: `MANAGER` create-payout → **403**; `FINANCE` write-creator → **403** |
| Security floor | Phase 0 record: pre-change `GET /creators?userId=…` with no credential returned **200 with data**; post-change **401** |
| Cross-tenant probes in CI | `CrossTenantIsolationTest`, 8 cases |

**This is the strongest part of the product and the hardest for a competitor to retrofit.** [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §4 identifies per-brand rates as the one capability with no documented competitor equivalent — and unlike most differentiation claims, this one is live-verified rather than asserted.

### 1.2 Landing page builder

280 E2E assertions across ten suites, zero failures ([docs/E2E-CONSOLIDATED-REPORT.md](docs/E2E-CONSOLIDATED-REPORT.md)).

| Capability | Evidence |
|---|---|
| GrapesJS visual builder, 7 custom blocks, 3 device widths | Phase A, commit `dd5ef3f` |
| Append-only version history; restore creates a new version and returns `draft` | A4–A5d — restore never silently republishes |
| XSS allow-list sanitisation on render, not save | A7–A13; 7 payload classes stripped, legitimate markup preserved |
| Brand ↔ creator co-editing, gated on a confirmed identity link, **publish never granted** | Phase G, commit `e5e62bd`, 29 assertions |
| Bidirectional Kanban ↔ page-stage sync with a transition map and idempotency | Phase D, commit `07a98b4`, 36 assertions |
| Asset library behind `AssetStoragePort`; content type from magic bytes; SVG refused | Phase B, 25 assertions |

The security work here is better than the feature warrants, which is a compliment: defect #6 in the consolidated report — `placeCard` had **no authorization at all** — was found by reading rather than testing.

### 1.3 Coupon attribution and payout workflow

Per [docs/coupon-attribution-plan.md](docs/coupon-attribution-plan.md) §8, Phases 0–5 all marked DONE with live verification:

- Attribution pipeline with dedupe on `(orderId, orderLineId)`, refund → clawback, paid commissions never clawed (Phase 3, verified 2026-07-31)
- Revenue dashboard: `$300 sale → $30 commission (10%) → ROI 10.0×` (Phase 4)
- Payout workflow: approve → batch → paid, with the approval gate (Phase 5)
- API E2E 27/27, Playwright UI 17/17

**Caveat that belongs here, not in a footnote:** this entire pipeline is verified against `MockMarketplaceProvider`. See §2.4.

### 1.4 Creator onboarding, vetting and health

| Capability | Evidence |
|---|---|
| Per-brand vetting rules; rules may reject and advance, **never approve** | Phase C2, `ab09ffc`, 31 assertions |
| Every decision recorded with the rule that fired (`vetting_events`) | C2.5 — makes "why was I rejected?" answerable |
| Health monitoring: append-only snapshots, per-brand thresholds, alerts that never revoke | Phase C3, `2623503`, 30 assertions |
| LLM classifies niche/themes/risk but **never emits a metric** | C5 — "the classification block contains no metric fields at all" |
| Unresolvable handle still creates the lead, follower count **absent, not zero** | C8, C8c |

C8c deserves note: writing `0` as a stand-in would make `follower_count < 5000 → reject` silently reject every creator whose lookup failed. That is the kind of detail that distinguishes a considered system from a demo.

---

## 2. Built but mocked or stubbed — the dangerous middle

**Why this category is dangerous:** these features pass their tests, appear complete in the UI, and are described as "Shipped" in the roadmap. A demo runs through them without error. But nothing crosses the network boundary, and the moment a real customer supplies a real domain or a real storefront, the feature does not degrade — it produces a confident wrong answer.

To the project's credit, **every mock declares itself.** The social gateway stamps `metrics_source = 'mock'` and the registrar returns `provider: "mock"`, both by deliberate design ([docs/E2E-LANDING-BUILDER-REPORT.md](docs/E2E-LANDING-BUILDER-REPORT.md): "a mock that claimed to be a real provider would put a simulated verification in front of a brand as though DNS had genuinely been checked"). That honesty is why this analysis is possible at all. It does not make the features shippable.

### 2.1 Platform metrics adapters — Instagram / TikTok / YouTube

**What "mocked" means concretely.** `MockSocialProfileGateway` (`InfluencerWebExperience/src/main/java/com/influencer/webe/creator/infrastructure/MockSocialProfileGateway.java`) is the **only** implementation of `SocialProfileGateway`. It:

- Derives every metric from an FNV-1a hash of the handle string. `followers = 1_000 + Math.floorMod(seed, 499_000L)`. Deterministic, so demos are stable — and entirely invented.
- **Ignores the `platform` argument** except to echo it back, defaulting to `instagram`. There is no per-platform branching anywhere.
- Returns **hardcoded, identical audience demographics for every creator** (`"18-24", 0.38`, `"female", 0.62`, `"US", 0.44`).
- Selects one of four canned caption sets by substring match on the handle (`casino`/`bet` → risk text, `fit`/`gym`, `beauty`/`glow`, else generic).

**What unmocking requires** (verified against source, not estimated):

1. Three adapters plus a **dispatcher** — `SocialProfileGateway.fetch(platform, handle)` is a single-bean interface with no per-platform routing. Needs a registry mirroring `PayoutProviderRegistry`.
2. A general outbound HTTP client. The BFF has `DaoHttpClientFactory` for mTLS-to-DAO only; no general egress client bean exists.
3. Per-creator OAuth token acquisition and storage. **No schema exists** — the OAuth config in `application.properties` covers Google/Facebook *login* only, not metrics scopes.
4. Response mapping, including `averageViews`, which **no platform returns directly**, and Instagram audience insights, which require a Business account and a separate endpoint.
5. Rate limiting, caching, quota handling, and a scheduler — `CreatorHealthController` notes refresh is manual today; there is no `@Scheduled` refresh anywhere.
6. **Non-code, and the actual blocker:** Meta App Review (2–4 weeks, **resets if a reviewer requests changes**) and TikTok (5–10 business days).

**The status tracker in [docs/platform-app-registration.md](docs/platform-app-registration.md) is completely empty** — every cell of every row. That document is dated 2026-08-02 and titled "Action Required". Four days later, nothing has been submitted. This is the single longest lead time in the entire roadmap and it has not started.

> One prerequisite *has* quietly landed: Meta review requires a public privacy policy and terms URL, and both are now live at `www.tejdux.com` ([docs/infrastructure/README.md](docs/infrastructure/README.md)). The blocker to starting is now genuinely just the act of starting.

### 2.2 Domain registrar — the differentiator that isn't

This is the most consequential entry in this document, because [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §4 names the landing-page builder **"most distinctive"** and §7.5 says "unmock the registrar before pitching landing pages."

**What "mocked" means concretely.** `MockDomainRegistrar` is the only implementation of `DomainRegistrarPort`. Reading the source:

- **DNS verification is a substring check on the domain name.** No DNS lookup, no `InitialDirContext`, no `dnsjava`. The `expectedToken` parameter is **never compared against anything retrieved**:
  ```java
  if (name.contains("unverified")) { return new Verification(false, ...); }
  return new Verification(true, provider(), "TXT record found and matches (simulated).");
  ```
  **Any domain not containing the literal string `unverified` verifies instantly — including a domain the caller does not own.**
- **SSL issuance is a boolean stub.** No CSR, no key, no ACME order, no challenge, no certificate bytes. `Certificate` is `record Certificate(boolean issued, String provider, String detail)`. There is no column for a certificate or private key in `2026_08_06_phase_e_domains.sql`.
- **The CNAME target is an unresolvable placeholder.** `hostingTarget` defaults to `pages.influencrm.example` — an RFC 2606 reserved name that cannot resolve. A brand following the on-screen instructions points their domain at nothing.
- **There is no custom-domain serving path at all.** The only public HTML routes are `/s/{slug}` and `/s/{slug}/{creator}` in `LandingController`. Repo-wide there is no `Host`-header resolver, no `X-Forwarded-Host` read, no `@RequestMapping(headers=...)`, and no lookup from an incoming hostname to a `brand_domains` row. `BrandDomainService.domainForPage()` resolves *page → domain* for display; nothing resolves *Host → page*.
- **No CDN, no deploy pipeline, no rollback-to-published-version.** Roadmap steps E.6 and E.7 have no corresponding methods on the port. Assets serve from local disk via `FilesystemAssetStorage`.

**Net: Phase E is a database state machine plus a string formatter.** Even a domain showing "verified, SSL active" in the UI serves nothing.

**What genuinely is built here** — and it is not nothing: the verification token is a real `SecureRandom` 24-byte value; the two-month hosting window is correctly implemented (set once on **first publish**, not signup; `extendHosting` extends from `max(now, current)`); expiry returns **410 Gone**, not 404; and expiry unpublishes without deleting (E9). The state machine is sound. It is the entire external half that is absent.

**What unmocking requires:** a Cloudflare or Route 53 adapter; a real DNS resolver; an ACME client **plus new schema** for certificates and keys (no columns exist); a **Host-header router** and `findByDomainName` lookup; TLS termination able to serve arbitrary SNI; a real hosting target; and — the item usually forgotten — **the application deployed somewhere at all**. Per [docs/infrastructure/README.md](docs/infrastructure/README.md): *"The application tiers … are not yet deployed to AWS."* Only the static legal site is live. **You cannot host a customer's domain from localhost.**

### 2.3 Payment and payout rails

**What "mocked" means concretely.** `ManualPayoutProvider` is the only `PayoutProvider`. The complete payment implementation:

```java
public PayoutResult pay(String creatorId, BigDecimal amount, String currency, String note) {
    // No external call; generate a human-traceable reference.
    String ref = "manual-" + creatorId.substring(0, Math.min(8, creatorId.length()));
    return PayoutResult.paid(ref);
}
```

`amount`, `currency` and `note` are **all ignored**. It unconditionally returns `paid`. The reference is a truncated UUID prefix, so **two payouts to the same creator produce an identical reference** — it is not usable as an idempotency key or a reconciliation handle.

Consequence: `result.isSuccess()` is always true, so the failure branch in `PayoutService.createPayout()` is **dead code** with the current provider set. Nothing has ever exercised a failed payout.

**Fair framing:** "manual" is a legitimate v1 for accounts-payable to influencers. [docs/coupon-attribution-plan.md](docs/coupon-attribution-plan.md) §5 argues this deliberately — most small brands do pay via PayPal by hand, and shipping the approval → batch → settle workflow with zero external integration is the right call. **This is the least alarming of the three mocks.** The SPI is also genuinely drop-in: `PayoutProviderRegistry` auto-discovers via `List<PayoutProvider>` injection.

**What unmocking requires:** a Stripe Connect / PayPal Payouts / Wise adapter (mechanically easy); creator payout-account onboarding and **KYC — no schema exists**; real idempotency keys; webhook handling for async `processing → paid` (the `PayoutResult` type has only `paid()` and `failed()` factories, so no code path can produce `processing`); and currency/FX handling (`currency` is defaulted to `"USD"` and passed through unused).

### 2.4 Marketplace connector — a mock not flagged in the market analysis

`MockMarketplaceProvider` is the **only** `MarketplaceProvider`. There is no Shopify adapter, no Shopigy, no WooCommerce.

This matters more than its absence from [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §5 suggests. **Coupon attribution is the product's core mechanic** — it is how the CRM proves ROI, and [roadmap.md](roadmap.md) Phase 2 is built entirely on Shopify integration. Today the whole attribution → commission → payout chain is verified only against an in-memory store that returns `ConnectionResult.ok("mock-acct-" + shop, ...)`.

An SMB/DTC brand's first question is "does it connect to my Shopify store?" The answer today is no. [docs/coupon-attribution-plan.md](docs/coupon-attribution-plan.md) §9 even records the open question *"Shopigy — is this a real marketplace with a public API, or a placeholder name?"* — still unanswered.

**A related demo hazard:** `DashboardPage.jsx` ships a **"Simulate an order (test / demo)"** form on the main dashboard, posting into the attribution pipeline with `providerKey: 'mock'`. It is the right tool for E2E testing and the wrong thing for a prospect to see on the primary revenue screen. It should be gated behind a debug flag before any external demo.

**Recommendation: add this to the market analysis's mocked list.** It is arguably ahead of the registrar in commercial urgency, because attribution is the value proposition and landing pages are the differentiator — you need the first to sell at all.

---

## 3. Designed but not built — endpoints without a usable path

### 3.1 Member invitation — the docs are stale in both directions

[docs/ddd-roadmap.md](docs/ddd-roadmap.md) Phase 3 says: *"Member invitation … is not built; memberships are currently provisioned directly. The endpoints and permissions exist, the UI flow does not."*

**That is now out of date.** Both exist:
- Endpoints: `POST /api/brands/members/invite`, `/invitations`, `/accept`, `/{id}/revoke`, `PUT|DELETE /api/brands/members/{userId}` ([ENDPOINTS.md](ENDPOINTS.md))
- UI: `InfluencerUI/src/pages/MembersPage.jsx` (259 lines), wired at `App.jsx:1684`
- Test: `tests/e2e_member_invitations.sh`

**But the flow does not complete.** The button reads "Send invitation". Nothing is sent. It renders the one-time token on screen with the note *"Send this to the invitee."* Confirmed by repo-wide search: **there is no email capability anywhere in the codebase** — no `JavaMailSender`, no `spring-boot-starter-mail`, no SendGrid, Postmark, Mailgun or SES. The only match for any of those terms in the entire repo is inside [docs/LandingPageBuildPRD.md](docs/LandingPageBuildPRD.md).

The security design is genuinely good — `MemberInvitationService` uses a 256-bit CSPRNG token, persists only the SHA-256 hash, enforces a 7-day TTL, refuses to grant `OWNER`, and checks the invited email matches the accepting user. The product experience is: *copy this string, open your own email client, paste it, and hope your colleague trusts a bare token from you.*

**There is also no accept-invitation screen.** `acceptInvitation()` is exported at `InfluencerUI/src/api/core.js:188` and has **zero call sites** in any UI component. So even if the token reaches the invitee, they have no way to redeem it through the product. The flow is broken at both ends.

**Gap: one transactional email provider, plus one redemption screen.** Everything else is done. This is among the cheapest high-visibility fixes available.

### 3.2 Creating a second brand — the agency story has no front door

`createBrand` exists as an API client function at `InfluencerUI/src/api/core.js:160`, and `POST /api/brands` exists on the BFF.

**No UI component calls it.** Verified: searching every `.jsx`/`.js` under `InfluencerUI/src` for `createBrand` returns only the definition. It is dead code.

The brand *switcher* is wired (`App.jsx:651`), so an account that already has two brands can move between them. But an agency signing up **cannot create its second brand through the product at all** — it requires a direct API call.

This is the demo-credibility gap in its purest form: the hardest part (isolation, per-brand roles, switching, the tenancy spine) is built and live-verified, and the fifteen-minute part (a form with one field) is missing, which makes the whole capability undemonstrable. **The multi-brand story cannot be told in a demo today.**

### 3.3 Deferred schedulers

Per [docs/E2E-CONSOLIDATED-REPORT.md](docs/E2E-CONSOLIDATED-REPORT.md), the endpoints exist and are tested; nothing calls them on a cadence:

| Missing scheduler | Consequence |
|---|---|
| C3 tiered metric refresh | Health monitoring never fires on its own — decline detection is manual-only |
| E expiry warnings (30/7/1 days) | A brand's page expires **with no warning**; the roadmap specified warnings and they do not run |
| D nightly reconciliation | Page/card divergence self-heals only in theory |

The expiry one is a live customer-harm risk the moment anyone publishes: the product silently stops serving a page it promised to warn about.

---

## 4. Not started

| Gap | State in repo | Note |
|---|---|---|
| **Billing** | **`accounts.plan` is a confirmed dead column.** Defined `nullable = false, default "free"`. Every Java read is pass-through into a DTO — no `if`, no `switch`, no enum, no `PlanPolicy`. Zero hits repo-wide for `entitlement`/`quota`. There are *two* inert plan columns: `accounts.plan` and `users.plan` | No Stripe Billing, no subscription entity, no invoice, no payment method, no price config, no seat/usage cap of any kind. **There is no way to charge anyone.** |
| **White-label / client reporting** | Nothing. Repo-wide search for `text/csv`, `application/pdf`, `exportCsv`, `generatePdf` → **zero hits** | No export of any kind, in any format, from any screen. Agencies cannot give a client anything |
| **Cross-brand rollups** | `AnalyticsController.influencerRevenue` resolves a single brand and **ignores its own `brandId` parameter**. `accessibleBrandIds` exists only for auth scoping, never aggregation | Deliberate per [docs/architecture-migration-plan.md](docs/architecture-migration-plan.md) §3.4 — the `creator_identity` projection was explicitly deferred. An agency cannot see all clients at once |
| **Social publishing (Phase F)** | Confirmed absent: no `SocialPublisherPort`, no token store, no scheduled posts | Blocked on the same unstarted app registrations. Also the most security-sensitive item in the roadmap — posting to a creator's personal account |
| **Creator discovery** | Confirmed absent: no search, no external database, no marketplace | Brands must already know their creators. The `import-batches/discover` endpoints are **spreadsheet column discovery**, not creator discovery — easy to misread |
| **Application deployment** | Only the static legal site is on AWS ([docs/infrastructure/README.md](docs/infrastructure/README.md)) | Prerequisite for domains, webhooks, OAuth callbacks, and any trial at all |
| **Welcome package (C2.7)** | The `CreatorApproved` trigger exists; the package does not | Needs email (§3.1) |
| **Rate limiting on public signup** | "needs infrastructure the platform lacks" | Public unauthenticated endpoint |

---

## 5. Deliberately declined — and why the reasoning holds

### Authenticity / fake-follower scoring

**Declined**, analysed at length in [docs/group2-build-vs-buy.md](docs/group2-build-vs-buy.md).

**The reason is data access, not cost.** Fake-follower detection works by examining the *followers* — account age, profile completeness, posting history, engagement behaviour. **Instagram exposes no follower-list endpoint.** Not gated behind review, not expensive — absent. TikTok and YouTube are comparable.

The three available routes and why each fails (§1):

| Route | Verdict |
|---|---|
| Official APIs | The data does not exist in them. Not a budget problem |
| Scraping | Breaches platform terms and risks the developer app that Phases C **and** F depend on |
| Ask creators to connect | Legitimate, but vetting matters *before* a relationship exists — and the creator inflating their numbers is exactly the one who will not connect |

The economics confirm it even in the counterfactual: buy costs $3,600–16,200/yr; a Claude-assisted build costs ~$18k–31k year one **and only if the data existed**, with $15–25k/yr maintenance. **Build loses even in the fantasy where the data is free.**

**This is a correct and well-argued decision, and the strongest piece of product reasoning in the repo.** Three things make it durable rather than merely defensible:

1. **`AudienceAuthenticityPort` is specified up front** (§5.3) with three implementations — no-op, in-house, vendor — so a trial costs a config change, not a rules-engine rewrite. The cheapest time to make something swappable is before you need to swap it.
2. **The trigger is measurable.** `creator_quality_reports` (C2.8, built — 2 rows in test data) records what our own signal said at the time of a complaint, turning each dispute into a labelled example. Threshold set in advance: three complaints in a quarter, **or one on a creator our signal rated clean**.
3. **It has an expiry**, not a permanent verdict (§6).

**Commercial consequence, which must be stated plainly:** no parity claim with Influencity or HypeAuditor is available. Any positioning implying authenticity scoring is a claim the product cannot back. The honest framing is the one the doc itself proposes — ship `"Engagement pattern: unusual"` (a flag from data we own), never `"Audience quality: 34/100"` (a score requiring data we don't have).

---

## 6. Competitive gap table

Competitor claims are sourced from [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §2–§3. "First demo" means a prospect notices in the initial evaluation; "month six" means it surfaces only in sustained use.

| Gap | Competitor ships it? | Demo or month six? | Cost of not having it |
|---|---|---|---|
| **Billing / self-serve checkout** | All of them | **First demo** | **Blocks revenue absolutely.** No mechanism to charge exists. Also forfeits the no-lock-in positioning §7.2 calls cheap to win — self-serve is the *proof* of no lock-in, and self-serve requires checkout |
| **Real Shopify connector** | Levanta, Trackier, Partnero, Grin (heavily) | **First demo** | Kills the core ROI mechanic. "Does it connect to my store?" is question one for a DTC brand, and the answer is no |
| **Deployed application** | All of them | **First demo** | Cannot run a trial, receive a webhook, complete an OAuth callback, or host a domain. Blocks everything else in this table |
| **Domain registrar (real)** | No influencer CRM; Carrd/ThriveCart do it better and cheaper | **First demo** | The "most distinctive feature" is a demo, not a differentiator. Worse than absent: DNS "verifies" for domains you don't own and the CNAME points at `.example` |
| **Invitation email** | All of them | **First demo** | A collaboration product where inviting a colleague means manually emailing a token undercuts the whole team story |
| **Create a second brand in the UI** | Truleado, IMAI, Influencity, Intellifluence | **First demo** | Makes the multi-brand capability — genuinely built and isolation-verified — **impossible to demonstrate** |
| **Client reporting / export** | Truleado, IMAI, Storyclash, Kleepa, Meltwater (white-label) | **First demo** for agencies | Agencies must show clients something. Zero export of any kind is disqualifying for the agency segment |
| **Creator discovery database** | Upfluence, Modash, Influencity, Grin | **First demo** for discovery-led buyers | Excludes buyers whose primary job is *finding* creators. Not fatal for the spreadsheet-replacement wedge — those brands already know their creators |
| **Platform metrics (real)** | All of them | First demo (shallow), month six (deep) | Follower counts are hash-derived. Vetting rules and health monitoring evaluate invented numbers. Honestly labelled, but a prospect who checks one handle against Instagram sees it immediately |
| **Cross-brand rollup** | Truleado, Storyclash, IMAI | Month six | Agencies ask "how are all my clients doing?" in week two of real use, not in the demo |
| **Authenticity scoring** | HypeAuditor, Modash, Influencity | Month six | Declined with sound reasoning (§5). Costs a parity claim, not a deal — *unless* a buyer requires a named vendor's audit |
| **Social publishing** | Grin, Later, most mid-market | Month six | Nice-to-have. Also the most security-sensitive unbuilt item |
| **Real payout rails** | Grin, Levanta, Partnero | Month six | Manual is a defensible v1 for SMB. Least urgent of the mocks |
| **Per-brand rates for shared creator** | **None documented** | Month six — **and that's the problem** | Our one uncontested differentiator is invisible in a demo. See §7 |

---

## 7. Prioritised gaps

Ordered by the brief's criteria: **(a) blocks revenue, (b) blocks a credible demo, (c) blocks the differentiation claims [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) says to lead with.**

### Tier 1 — blocks revenue outright

1. **Billing.** `accounts.plan` is a dead column; there is no charging mechanism of any kind. Nothing else on this list generates a dollar until this exists. *(a)*
2. **Deploy the application.** Only a static legal site is live. Blocks trials, webhooks, OAuth callbacks, and domain hosting — every other item is gated on this. *(a, b)*

### Tier 2 — blocks a credible first demo

3. **Real Shopify connector.** The core ROI mechanic runs on an in-memory mock. First question a DTC brand asks. *(a, b)*
4. **Create-a-brand UI.** One form field standing between a built, isolation-verified capability and an undemonstrable one. Highest ratio of demo value to effort in this document. *(b, c)*
5. **Invitation email.** One provider integration completes a flow that is otherwise finished. *(b)*
6. **Client-facing export.** Any format. Zero exists. Disqualifying for agencies. *(b)*

### Tier 3 — blocks the differentiation claims

7. **Real registrar + custom-domain serving + deploy pipeline.** [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §7.5: unmock before pitching landing pages. Larger than it looks — needs ACME, new schema, Host-routing, and SNI termination, not just a Cloudflare adapter. *(c)*
8. **Start the platform app registrations.** Zero code, 2–4 weeks of calendar, tracker completely empty, and the privacy-policy prerequisite is now satisfied. **Every day of delay is a day added to Phase C, C2, C3 and F.** *(c)*
9. **Surface per-brand rates in the product.** Our one uncontested differentiator is a database property nobody sees. It needs a screen that makes it visible, not more engineering. *(c)*

### Tier 4 — real but not now

10. Expiry-warning scheduler (live customer-harm risk once anyone publishes, but nobody has published)
11. Cross-brand rollup · 12. Real payout rails · 13. Social publishing · 14. Creator discovery

---

## 8. Three things that surprised me

Worth recording separately, because each contradicts something a reader would otherwise assume.

1. **The docs understate progress in one place and overstate it in another.** [docs/ddd-roadmap.md](docs/ddd-roadmap.md) says member invitation UI does not exist — it does. The landing-page roadmap says Phase E "Shipped" — it is a state machine with no external half. **Trust the code.**

2. **`createBrand` is dead code.** The function is written and the endpoint exists; no component calls it. This is the clearest instance of the asymmetry: the tenancy spine took four migration phases and works; the form that exercises it was never built.

3. **The mock registrar verifies domains you do not own.** A substring check standing in for a DNS lookup is fine in a test. But `MockDomainRegistrar` is active by `matchIfMissing = true`, and `web-experience.domains.provider` is **not set in any properties file** — so the mock is not opt-in, it is the default with no configuration expressing that choice. Same for `web-experience.creators.social-provider`. If this ever reaches an environment someone treats as real, "verified" means nothing. Setting both properties explicitly, even to `mock`, would make the current state a decision rather than an accident.
