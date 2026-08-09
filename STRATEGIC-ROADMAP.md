# Strategic Roadmap — InfluenCRM

**Date:** 2026-08-06
**Operationalizes:** [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md)
**Grounded in:** [PRODUCT-GAPS.md](PRODUCT-GAPS.md)
**Sits above:** [roadmap.md](roadmap.md) — the MVP build order, which this does not replace

---

## 0. Relationship to the existing roadmap

[roadmap.md](roadmap.md) is the **product build order**. This document is the **commercial layer above it**. They answer different questions and both remain in force.

| | [roadmap.md](roadmap.md) | This document |
|---|---|---|
| Question | What do we build, in what dependency order? | What must be true to charge money and win a demo? |
| Organizing bet | "prove small brands will abandon their spreadsheet" | Same bet — this makes it sellable |
| Unit | Features | Phases gated on revenue and demo credibility |

### What this document leaves intact

- **The organizing bet.** *"Prove small brands will abandon their spreadsheet."* [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §6 independently confirms it: 80%+ of influencer marketers use spreadsheets, sometimes because there is no software budget. The competition is Excel. That bet was right and stays.
- **Phase 1 in full.** Auth, spreadsheet import, import undo, creator list/detail, Kanban, manual entry. All shipped per the "Current UI implementation milestone".
- **Sequencing principles** (§"Sequencing principles"). "Don't start a phase until the previous one's core bet is validated by real usage" and "prefer additive, reversible changes; never break the tenant filter" both hold. The second has been honoured through six migration phases.
- **Phase 3 — the relationship moat.** UGC library, auto-enriched profiles, repeat-partnership prompts. Untouched and still correct as a later destination.

### What this document extends

- **Phase 2's Shopify integration** is upgraded from a feature to a **Tier-1 commercial blocker**. [roadmap.md](roadmap.md) treats it as the natural follow-on to Phase 1; [PRODUCT-GAPS.md](PRODUCT-GAPS.md) §2.4 shows `MockMarketplaceProvider` is still the only implementation, which means the ROI dashboard — the thing that "makes the CRM earn its keep" — has never seen a real order.
- **"Auth + workspace — one user = one workspace. No teams yet."** Superseded by delivery, not by decision. Six migration phases produced full multi-brand tenancy and RBAC ([docs/ddd-roadmap.md](docs/ddd-roadmap.md)). The roadmap's own principle — *"keep the schema ahead of the UI where it's cheap, but don't build UI for it until needed"* — was followed correctly. **The UI is now needed.** That is Phase 1 below.

### What this document supersedes

Nothing is deleted. One thing is **reordered**: [roadmap.md](roadmap.md) implies validate-then-monetize, with Phase 1 retention proving the bet before Phase 2 earns the subscription. That ordering assumed a product that could take money once the bet held.

It cannot. There is no billing of any kind — `accounts.plan` is a dead column ([PRODUCT-GAPS.md](PRODUCT-GAPS.md) §4). **Retention cannot be measured on a product nobody can buy, and willingness-to-pay cannot be inferred from free usage** — especially against a free incumbent. Monetization moves alongside validation rather than after it.

### One correction to the existing roadmap

[roadmap.md](roadmap.md) Phase 3 lists *"auto-enriched creator profiles — paste a handle, auto-fetch follower count, engagement rate, recent posts, contact email."*

**This is built, and it is mocked.** Phase C shipped the handle-paste flow with an LLM classifier, and every metric is FNV-1a hash-derived ([PRODUCT-GAPS.md](PRODUCT-GAPS.md) §2.1). It should be struck from Phase 3 as unbuilt work and tracked instead as an unmocking dependency on platform app registration.

---

## 1. The strategy in one page

Five findings from [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) drive everything below.

| Finding | Consequence for this roadmap |
|---|---|
| **The real competitor is spreadsheets** (80%+ usage), not Grin | Price against free. Every phase must survive "why not just use Excel?" |
| **Contract lock-in is Grin's most-cited weakness** | Self-serve, month-to-month, no contract. Cheap to build, cheap to say, directly targets the complaint. **But self-serve requires checkout** — this is why billing is Phase 2, not Phase 6 |
| **Per-brand rates for a shared creator has no competitor equivalent** | Lead with it. It is live-verified. It is also invisible today — it needs a *screen*, not more engineering |
| **The landing builder is most distinctive but the registrar is MOCKED** | Do not pitch it until it is real. It is a demo, not a differentiator |
| **Multi-brand agency positioning is TAKEN** by Truleado ($99+$29/client) | Do not lead with it. Keep the capability (it is built and it is good), sell it as depth once someone is already interested |

**The organizing principle of the sequence: fix the shopfront before deepening the warehouse.**

[PRODUCT-GAPS.md](PRODUCT-GAPS.md) §0 establishes the asymmetry — backend depth is excellent and invisible; the commercial layer is absent and is all a buyer sees. Every phase below is ordered by how directly it converts existing invisible strength into visible, chargeable product. **Almost nothing here is new capability. Most of it is exposing capability that already exists.**

```
Phase 0  Deploy + start app registrations ── unblocks everything, mostly not code
Phase 1  Make what's built demonstrable ──── forms and exports over existing APIs
Phase 2  Take money ───────────────────────── billing + real Shopify
Phase 3  Make the differentiator real ─────── registrar, hosting, custom domains
Phase 4  Earn the renewal ─────────────────── real metrics, rollups, reporting
```

---

## Phase 0 — Unblock the calendar

**Goal:** remove the two dependencies where waiting costs more than working, and where the work is mostly not code.

### Scope

1. **Start the platform developer app registrations — day one, before anything else.** [docs/platform-app-registration.md](docs/platform-app-registration.md) has step-by-step instructions and a status tracker that is **completely empty**. Meta review is 2–4 weeks and **resets if a reviewer requests changes**; TikTok is 5–10 business days. The privacy-policy and terms-URL prerequisite is already satisfied — both are live at `www.tejdux.com` ([docs/infrastructure/README.md](docs/infrastructure/README.md)). **There is no remaining reason not to have started.**
2. **Deploy the application tiers.** Only the static legal site is on AWS. The BFF, services, UI and Postgres run on localhost. This blocks trials, webhooks, OAuth callbacks and domain hosting — it is a prerequisite for Phases 2 and 3, not a parallel task.
3. **Set the mock provider flags explicitly.** `web-experience.domains.provider` and `web-experience.creators.social-provider` are unset in every properties file; the mocks are active via `matchIfMissing = true` ([PRODUCT-GAPS.md](PRODUCT-GAPS.md) §8). Write them down as `mock`. This converts an accident into a decision and makes the eventual switch a one-line diff.
4. **Wire the expiry-warning scheduler** (30/7/1 days). The endpoints are built and tested; nothing calls them. A page expiring with no warning is live customer harm the moment anyone publishes.

### Explicit non-goals

- No new features. No registrar work. No UI work.
- Not a full production-grade deployment — a real, reachable environment, not a hardened one.
- Not the C3 metric-refresh scheduler; there are no real metrics to refresh yet.

### Definition of done

- Every row of the app-registration tracker has an owner and a submitted date.
- The app is reachable at a public URL, can receive a webhook, and can complete an OAuth callback.
- Both provider properties are explicitly set.
- A page published 61 days ago produced a warning at 30, 7 and 1 days.

### Validation signal

Meta review either progresses or comes back with a specific objection. **Either is a win** — the failure mode is not rejection, it is discovering in October that nobody submitted in August. Rejection is information; silence is delay.

### Sequencing risk

**This is the critical path and it is invisible.** No code depends on it today, so it will be deprioritised against work that produces visible progress. It should be started by someone on day one and tracked weekly. Everything in Phase 4 blocks on it, and no engineering speed compresses a review queue.

---

## Phase 1 — Make what is already built demonstrable

**Goal:** close the demo-credibility gap without building new capability. Every item here exposes something that already exists and is already tested.

**Why first (after Phase 0):** this is the highest-leverage work in the document. The backend is genuinely strong; a prospect cannot see any of it. These are forms and exports over live, verified APIs.

### Scope

| # | Item | Why it's cheap |
|---|---|---|
| 1.1 | **Create-a-brand UI** | `createBrand` already exists at `InfluencerUI/src/api/core.js:160` and `POST /api/brands` works. **Nothing calls it.** A one-field form makes the entire multi-brand story demonstrable |
| 1.2 | **Invitation email + accept screen** | The invite flow is built and well-secured; it ends by printing a token on screen. Needs one transactional email provider (Postmark/SES) **and a redemption screen** — `acceptInvitation()` exists at `core.js:188` with zero call sites, so an invitee currently cannot redeem a token through the product at all |
| 1.3 | **CSV export** — creators, campaigns, attribution, commissions | Zero export exists in any format. CSV first: it is trivially cheap and it is what agencies actually paste into client decks |
| 1.4 | **A screen that surfaces per-brand rates** | The differentiator. Show the same creator held by two brands at different rates, with each brand blind to the other. Today this is a database property with no UI |
| 1.5 | **Demo seed script** | `tests/seed_demo_accounts.sh` exists. Extend it to produce an agency with two brands, a shared creator at two rates, and attributed revenue — so a demo is reproducible rather than assembled by hand |
| 1.6 | **Gate the "Simulate an order" tool** | It sits on the main dashboard and posts with `providerKey: 'mock'`. Correct for E2E, wrong for a prospect's first look at the revenue screen. Hide behind a debug flag |

### Explicit non-goals

- **No white-label/branded PDF reporting.** CSV first; branded reports are Phase 4. Truleado and IMAI already ship white-label — this is a parity feature, not a wedge, and it is not worth delaying revenue for.
- No cross-brand rollup (Phase 4).
- No new backend endpoints. **If an item needs one, it does not belong in this phase.**

### Definition of done

A single unassisted demo run, by someone who did not build the product:

> Sign up → create a second brand → invite a colleague who receives a real email → add the same creator to both brands at different rates → show each brand cannot see the other's rate → export the creator list to CSV.

Every step through the UI. No API client, no database access, no "imagine that this button exists."

### Validation signal

Run this demo in front of five prospects. **The signal is which question they ask next.** If it is "can I connect my Shopify store?" or "how much is it?", the demo cleared the credibility bar and Phase 2 is correctly ordered. If it is still "how do I find creators?", the wedge is wrong and discovery needs reconsidering before more is spent.

### Sequencing risk

1.2 introduces the first external service dependency (email). Keep it behind a port with a log-only implementation for local development, matching the pattern already used for `AssetStoragePort` and `PayoutProvider`.

---

## Phase 2 — Take money

**Goal:** be purchasable, self-serve, with no contract. This is where the Grin-lock-in positioning becomes real rather than rhetorical.

**Why here:** nothing before this produces a dollar. [roadmap.md](roadmap.md) implies validate-then-monetize; §0 explains why that inverts — willingness to pay cannot be inferred from free usage against a free incumbent.

### Scope

| # | Item | Notes |
|---|---|---|
| 2.1 | **Stripe Billing + self-serve checkout** | Month-to-month, cancel in-app, no contract, no sales call. **The cancel button is the product feature** — it is what makes "no lock-in" checkable rather than claimed |
| 2.2 | **Make `accounts.plan` load-bearing** | Today it is set, stored, echoed and never read. Needs a `PlanPolicy` with real entitlement checks. No enforcement exists anywhere — zero hits repo-wide for `entitlement` or `quota` |
| 2.3 | **Real Shopify connector** | `MockMarketplaceProvider` is the only `MarketplaceProvider`. The SPI is genuinely drop-in (auto-discovery via `List<MarketplaceProvider>`), so this is one adapter — plus OAuth, webhook signature verification, and the credential encryption [docs/coupon-attribution-plan.md](docs/coupon-attribution-plan.md) §7 flagged and Phase 2 deferred |
| 2.4 | **Envelope-encrypt marketplace credentials** | Currently JSON-serialized as-is into `credentials_encrypted`. Named as a Phase 6 prerequisite in the coupon plan. **Non-negotiable before real Shopify credentials flow through** |
| 2.5 | **Resolve "Shopigy"** | [docs/coupon-attribution-plan.md](docs/coupon-attribution-plan.md) §9 asks whether it is a real marketplace or a placeholder. Still unanswered. Answer it before building an adapter for it |

### Pricing posture

[MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §7.6: **price against spreadsheets, not against Grin.**

- Must clear a **"why not free"** bar, not a "why not Grin" bar. A brand comparing us to Excel is comparing to $0.
- **Do not anchor on Truleado's $99+$29/client.** That is agency pricing for a positioning we are told not to lead with (§3 below).
- Transparent and published. Demo-gating is the friction IMAI, Storyclash, Kleepa and Meltwater all impose; not doing it is free differentiation.
- **Not modelled here.** [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §1 declines to estimate SOM with zero customers, and a price point invented in this document would be equally unfounded. Set it from Phase 1 demo conversations.

### Explicit non-goals

- **No usage metering or seat-based pricing.** Flat tiers. Metering is what Grin's two 2026 overhauls churned on; a simple price is a competitive advantage against that.
- No annual contracts or discounts. Undermines the entire positioning.
- No enterprise tier, no sales-assisted motion.
- No other marketplace adapters. Shopify only. Prove one real integration first.

### Definition of done

A stranger finds the pricing page, subscribes with a card, connects a **real** Shopify store, generates a coupon that appears in that store's admin, drives a real order, sees it attributed with commission accrued — and can cancel in-app without contacting anyone.

### Validation signal

**Does anyone pay?** Specifically: does a brand that completed a Phase 1 demo convert without a discount, a call, or a custom term? That is the bet — that a spreadsheet-replacement with real attribution clears the "why not free" bar.

Secondary and equally important: **watch the cancel rate honestly.** No-lock-in means churn is visible immediately rather than deferred to renewal. That is the cost of the positioning and it must not be quietly re-litigated the first month churn looks bad.

### Sequencing risk

- 2.3 depends on Phase 0's deployment (Shopify webhooks need a public URL).
- 2.4 must land **with** 2.3, not after. Real credentials through an unencrypted column is the kind of shortcut taken under deadline that becomes an incident.
- Stripe Connect for *payouts* is explicitly **not** here — that is money out to creators, and manual remains a defensible v1 ([docs/coupon-attribution-plan.md](docs/coupon-attribution-plan.md) §5). Do not conflate the two Stripe integrations.

---

## Phase 3 — Make the differentiator real

**Goal:** turn the landing-page builder from a demo into a shipped differentiator. [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §7.5: *"Unmock the registrar before pitching landing pages."*

**Why after billing:** it is the most distinctive feature but not the most commercially urgent. A brand pays for spreadsheet replacement plus attribution; landing pages are what makes them choose us over the next spreadsheet-replacement. Also — per [docs/landing-page-builder-roadmap.md](docs/landing-page-builder-roadmap.md) Phase E — this is the highest-external-risk phase and the only one that cannot be fully tested locally.

### Scope

| # | Item | Notes |
|---|---|---|
| 3.1 | **Real registrar adapter** | Cloudflare behind the existing `DomainRegistrarPort`. Correctly a port — the adapter is the smallest part of this phase |
| 3.2 | **Real DNS verification** | Today it is a substring check on the domain name; the token is never compared against anything retrieved. **Any domain not containing the string `unverified` verifies instantly, including one the caller does not own** |
| 3.3 | **ACME certificate issuance + renewal, and schema for it** | The stub returns a boolean. There is **no column for a certificate or key** in `2026_08_06_phase_e_domains.sql`. New schema required |
| 3.4 | **Host-header routing** | **The gap most likely to be underestimated.** The only public routes are `/s/{slug}` and `/s/{slug}/{creator}`. There is no `Host` → `brand_domains` lookup anywhere. Even a fully "verified, SSL active" domain serves nothing today |
| 3.5 | **TLS termination with arbitrary SNI** | Infrastructure, not application code. Serving customer domains means terminating certificates we issued for names we do not own |
| 3.6 | **A real hosting target** | `hostingTarget` defaults to `pages.influencrm.example` — an RFC 2606 reserved name that **cannot resolve**. A brand following the on-screen instructions today points their domain at nothing |
| 3.7 | **Deploy pipeline + rollback** | Roadmap steps E.6/E.7 have no corresponding port methods. Assets serve from local disk via `FilesystemAssetStorage` |

**Do not underestimate this phase.** The roadmap presents Phase E as shipped; [PRODUCT-GAPS.md](PRODUCT-GAPS.md) §2.2 shows the state machine is sound and the entire external half is absent. 3.4, 3.5 and 3.7 are not adapter swaps — they are new infrastructure.

**What genuinely is done and should not be rebuilt:** the hosting-window logic (two months from *first publish*, `extendHosting` from `max(now, current)`), 410-on-expiry, and expiry-unpublishes-without-deleting. That work is correct and well-reasoned.

### Explicit non-goals

- **No domain reselling.** Decision #9 — the brand buys on their own registrar account. This removes a reseller agreement, markup logic, and the question of who owns a domain when a brand leaves. **Keep this decision; it is one of the best in the repo.**
- No S3/presigned uploads unless bandwidth forces it. The filesystem adapter works and the port is the durable decision.
- No Phase F social publishing.
- No Yjs simultaneous editing (G.6) — defer until users report losing each other's work.

### Definition of done

A brand connects a domain they actually own; DNS verification performs a **real** lookup and fails until the record genuinely exists; a certificate is issued by a real CA; and the page serves over HTTPS on their domain. Then: a domain the brand does *not* own **fails** verification.

That last clause is the real test. The current implementation would pass the first and fail the second.

### Validation signal

Do brands connect real domains? A published page on a customer's own domain is a switching cost and a visible endorsement. If brands publish to `/s/{slug}` and never bind a domain, the differentiator is not one — and the two-month free window is doing the work instead.

### Sequencing risk

- Hard-depends on Phase 0 deployment. Cannot host a customer domain from localhost.
- The **two-month hosting window is a commercial trigger with no payment behind it.** `extendHosting` takes a raw `days` int; its Javadoc says "a promotion, or payment" and no payment code exists. Phase 2 must land first or the first expiry is a support ticket, not a conversion.
- 3.5 may require a different hosting topology than Phase 0 chose. **Decide the SNI story during Phase 0**, not here.

---

## Phase 4 — Earn the renewal

**Goal:** convert the backend depth into visible month-six value. Everything here is what a customer discovers after they have already bought.

**Gate:** do not start until Phase 2 has paying customers. [roadmap.md](roadmap.md)'s principle — *"don't start a phase until the previous one's core bet is validated by real usage"* — applies with full force.

### Scope

| # | Item | Depends on |
|---|---|---|
| 4.1 | **Real platform metrics adapters** | Phase 0 approvals. Three adapters **plus a dispatcher** — `SocialProfileGateway.fetch(platform, handle)` has no per-platform routing today. Plus creator OAuth token storage (**no schema exists**) and a general outbound HTTP client (**none exists**) |
| 4.2 | **C3 metric-refresh scheduler** | 4.1. Tiered cadence, because refresh spends API quota |
| 4.3 | **Cross-brand rollup** | The deferred `creator_identity` projection ([docs/architecture-migration-plan.md](docs/architecture-migration-plan.md) §3.4). Derived, never written to directly, so it can be added at any point without touching the tenancy invariant |
| 4.4 | **White-label client reporting** | Branded PDF/scheduled reports. Parity with Truleado/IMAI/Storyclash |
| 4.5 | **Welcome package (C2.7)** | Email from 1.2. The `CreatorApproved` trigger already exists |
| 4.6 | **Real payout rails** | Stripe Connect behind the existing SPI. Also needs creator KYC (**no schema**), real idempotency keys (the current `providerRef` is a truncated UUID and **not unique per payout**), and webhook handling for async `processing → paid` |

### Explicit non-goals

- **Authenticity scoring stays declined.** Revisit only on the [docs/group2-build-vs-buy.md](docs/group2-build-vs-buy.md) §5 trigger: three complaints in a quarter, or one on a creator our signal rated clean. `creator_quality_reports` is already capturing the evidence.
- No creator discovery database (see §3 below).
- No Phase F social publishing until a customer asks twice.

### Definition of done

A creator's follower count on screen matches their actual profile, and its provenance reads `platform_api` rather than `mock`. An agency exports a branded report covering all its brands.

### Validation signal

Renewal, and whether support volume falls once metrics are real. Also: does `metrics_source = 'platform_api'` actually appear in the database? Until it does, 4.1 is not done regardless of what is deployed.

---

## 2. Dependencies and sequencing risks

```
Phase 0 ──┬──────────────────────────────────────────────► Phase 4 (app approvals: 4-8 weeks)
          │
          ├─► Phase 1 ─► Phase 2 ─┬─► Phase 3 (needs deploy + billing)
          │                       │
          └─── deployment ────────┴─► Phase 4
```

| Risk | Why it bites | Mitigation |
|---|---|---|
| **App registrations never started** | No code depends on them, so they lose every prioritisation contest. The tracker has been empty since 2026-08-02 | Named owner, day one of Phase 0, weekly check. Treat an empty tracker cell as a blocker, not a to-do |
| **Phase 3 underestimated** | The roadmap says "Shipped"; the external half does not exist. Host-routing and SNI are new infrastructure | Scope 3.4/3.5/3.7 as infrastructure work, not adapter swaps. Decide the SNI story in Phase 0 |
| **Billing deferred "until we have users"** | Circular: no billing means no signal about willingness to pay, especially against a free incumbent | Phase 2 gates Phase 3. Do not reorder |
| **Free hosting expires with no way to pay** | The two-month window is a commercial trigger with no payment behind it | Phase 2 before Phase 3, non-negotiable |
| **Real Shopify credentials before encryption** | Credentials are JSON-serialized as-is today | 2.4 ships **with** 2.3 |
| **Backend refactoring crowds out commercial work** | It is more enjoyable and the codebase invites it. Phases 5–6 of [docs/ddd-roadmap.md](docs/ddd-roadmap.md) are complete and there is no named driver for more | The stop-and-reassess gate already says stop. It still applies |
| **Two copies of every landing/workflow class** | [docs/E2E-CONSOLIDATED-REPORT.md](docs/E2E-CONSOLIDATED-REPORT.md): all traffic routes through the DAO monolith; every affected class exists twice and both must be edited in lockstep | Real ongoing tax on every phase. Budget for it; consider collapsing the duplication before Phase 3 |

---

## 3. What we are NOT building, and why

Stated plainly, because a roadmap that adopts everything is not a plan.

### Not building: creator discovery database

**Why:** it is the defining feature of a different product category (Upfluence, Modash, Influencity) and requires either a licensed data vendor or scraping. Scraping risks the developer app that Phases C and F depend on — the same argument that killed authenticity scoring ([docs/group2-build-vs-buy.md](docs/group2-build-vs-buy.md) §1).

**What we give up:** buyers whose primary job is *finding* creators. That is a real segment and we are ceding it.

**Why that is acceptable:** the wedge is brands who **already know their creators** and are managing them in a spreadsheet. [roadmap.md](roadmap.md)'s bet targets exactly those brands, and 80%+ of the market is in a spreadsheet today. Discovery is a different buyer.

**Revisit if:** Phase 1 demos keep ending with "but how do I find creators?" That is the signal the wedge is wrong — and it is the specific thing the Phase 1 validation signal is designed to detect.

### Not building: authenticity / fake-follower scoring

**Why:** the data cannot be legitimately obtained. Instagram exposes no follower-list endpoint; TikTok and YouTube are comparable. This is not a budget or velocity problem — Claude compresses ~75% of the code and 0% of the data problem.

**Already decided and well-argued** in [docs/group2-build-vs-buy.md](docs/group2-build-vs-buy.md). `AudienceAuthenticityPort` and `creator_quality_reports` are already in place so the decision can be reversed cheaply.

**What we give up:** any parity claim with Influencity or HypeAuditor. Never publish a numeric "audience quality score" — ship `"Engagement pattern: unusual"` (a flag from data we own), never `"34/100"` (a score requiring data we don't).

### Not leading with: multi-brand agency positioning

**Why:** the pitch is taken. Truleado is purpose-built for agencies at $99/mo + $29/client with a client portal, RBAC and unlimited seats, and markets itself explicitly against Grin/Aspire/Upfluence as "single-brand tools." Their positioning copy is near word-for-word what we would have said. IMAI, Storyclash, Influencity and Intellifluence all ship per-client workspaces ([MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §3).

**Note the distinction:** we are **not** deprioritising the multi-brand *capability*. It is built, isolation-verified, and Phase 1.1 makes it demonstrable. We are declining to make it the **headline**. Sell per-brand rates and no-lock-in; multi-brand is depth that closes the deal once someone is already interested.

### Not building: social publishing (Phase F)

**Why:** blocked on the same unstarted app registrations, and it is the most security-sensitive item in the roadmap — posting to a creator's *personal* account requires per-brand consent, separate token stores, and creator pre-approval of content ([docs/landing-page-builder-roadmap.md](docs/landing-page-builder-roadmap.md) Phase F).

**Revisit if:** two or more paying customers ask. Not before.

### Not building: more architectural decomposition

**Why:** Phases 5–6 of [docs/ddd-roadmap.md](docs/ddd-roadmap.md) are complete — seven services, a federated remote, 189 tests passing. The stop-and-reassess gate requires a named driver: independent scaling, separate teams, or a compliance boundary. **None is present.** ~22k LOC, one contributor.

**The gate's own words:** *"It would be cleaner" is not a driver.* That judgement was correct when written and is correct now.

**The one exception worth considering:** collapsing the duplicated landing/workflow classes between `InfluencerDAO` and the extracted services. That is not decomposition — it is removing a tax that every future phase pays.

### Not building: enterprise sales motion

**Why:** it contradicts the positioning. Self-serve, no contract, transparent pricing is the answer to Grin's most-cited complaint. An enterprise tier reintroduces exactly the friction we are differentiating against — and Grin's own tier design (the real CRM gated behind $500/mo) implies they do not believe SMB supports a full-featured low-price product. **Being the counter-example is the bet.**

---

## 4. What would falsify this plan

Recorded so the strategy has an expiry rather than a permanent verdict.

| Signal | What it means | Response |
|---|---|---|
| Phase 1 demos keep ending in "how do I find creators?" | The wedge is wrong; buyers want discovery, not spreadsheet replacement | Reconsider discovery — probably licensed data, not build |
| Nobody converts in Phase 2 without a discount | Spreadsheets are winning the "why not free" test | Re-examine price, or whether attribution alone is enough value |
| Brands publish to `/s/{slug}` and never bind a domain | The landing builder is not the differentiator the analysis assumed | Descope Phase 3; the free window is doing the work |
| A competitor ships per-brand rates | Our one uncontested differentiator is contested | Fall back to the *combination* bet ([MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §4) and compete on execution speed |
| Meta review is refused outright | Phase 4.1 and Phase F are both dead | Manual metric entry as first-class; revisit a data vendor |
| Three follower-quality complaints in a quarter | The authenticity decision has hit its stated trigger | Trial HypeAuditor Basic at $299/mo against the complained-about creators |

**One thing to internalise from [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §4:** a distinctive combination of individually-copyable features is a positioning strategy, not a moat. Grin could close any single gap in 1–2 quarters if motivated. Defensibility comes from execution speed and vertical integration — one data model feeding every surface — not from any single feature.

**Which is the real argument for this sequence.** The backend depth is genuine and rare. It only becomes defensible if the product is purchasable long enough for anyone to reach month six.
