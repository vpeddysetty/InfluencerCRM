> **ARCHIVED 2026-08-19 — superseded by [MASTER-ROADMAP.md](../../../MASTER-ROADMAP.md).**
> Kept for its reasoning, not its status. Every status claim below is stale, and several
> were wrong when written — the code had moved past them. **Do not schedule from this**
> **document.** Still worth reading for: the milestone sizing method, the two-act demo script, and "the tax nobody scheduled" (§1).

# Execution Roadmap — InfluenCRM

**Date:** 2026-08-07
**Supersedes for scheduling:** [STRATEGIC-ROADMAP.md](STRATEGIC-ROADMAP.md) §Phases 0–4 — the strategy there holds; this restructures it into shippable increments
**Grounded in:** [PRODUCT-GAPS.md](PRODUCT-GAPS.md) · [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) · code read directly 2026-08-07
**Does not replace:** [roadmap.md](roadmap.md) — the original MVP build order, whose Phase 1 is shipped

---

## How this differs from STRATEGIC-ROADMAP.md

That document answers *"what must be true to charge money?"* and answers it well. It has three structural problems that make it hard to execute against:

1. **No sizing.** Phase 1 (a one-field form) and Phase 3 (arbitrary-SNI TLS termination) are presented in identical table format. With one contributor, that difference decides whether the plan is real.
2. **Phases are too large to ship.** Nothing reaches a user until a whole phase lands. Phase 3 as scoped is larger than Phases 0+1+2 combined.
3. **Its own validation signals are unmeasurable.** §4's falsification table depends on product analytics that do not exist.

This document keeps every strategic call and restructures the work into **eight milestones, each independently shippable and independently valuable.**

### Assumptions, stated so they can be corrected

- **One full-time engineer**, Claude-assisted. All estimates assume this. Two engineers does not halve the calendar — M2, M4 and M6 have external dependencies that no headcount compresses.
- **Sizes are dev-days**, excluding review queues and DNS propagation.
- **Nothing here is a hard date.** Sequence and relative size are the deliverable; absolute dates are the user's to set.

---

## 0. The critical path in one diagram

```
M0 Instrument + unblock (5d) ─┬─► M1 Demo-ready (8d) ─► M2 Take money (10d) ─► M3 Real Shopify (12d)
                              │                                    │
                              └─► [app registrations: 4–8 WEEKS calendar, 0 dev-days] ──┐
                                                                                        │
M3 ─► M4 Activation (7d) ─► M5 Own-domain hosting (10d) ─► M6 Real metrics (15d) ◄──────┘
                                                     │
                                                     └─► M7 Custom domains (20d, GATED)
                                                     └─► M8 Agency depth (12d, GATED)
```

**Two things run in parallel from day one and must not be conflated:**

| Track | What it is | Who blocks it |
|---|---|---|
| **Build track** | M0 → M8, sequential | Engineering capacity |
| **Calendar track** | Meta/TikTok app review | Nobody. It is a form. It has not been submitted. |

The calendar track costs **zero dev-days** and gates M6 by **4–8 weeks**. Every day it is not submitted is a day added to M6. This is the single highest-leverage action in the document and it is not code.

---

## Progress log

**2026-08-07 — M0 and M1 built, except deployment.**

| Item | State | Note |
|---|---|---|
| M0.1 registrations | **Done (non-code)** | Meta requested; YouTube key obtained; TikTok deferred by decision with a paste-ready package in [platform-app-registration.md](docs/platform-app-registration.md) §2.1 |
| M0.2 analytics | **Done** | Provider port + **7 of 8** events wired. See [product-analytics.md](docs/product-analytics.md) |
| M0.3 deploy | **Owner: user** | Not built here. [hosting-topology-decision.md](docs/infrastructure/hosting-topology-decision.md) carries the M0 SNI decision it depends on |
| M0.4 provider flags | **Done** | `domains.provider`, `creators.social-provider`, `hosting-target` now explicit |
| M0.5 simulator gate | **Was already done** | `VITE_ENABLE_ORDER_SIMULATOR` at [DashboardPage.jsx:188](InfluencerUI/src/pages/DashboardPage.jsx#L188). 0.5d banked |
| M1.1 preferred_rate | **Done** | **Root cause was the BFF, not the UI** — see below |
| M1.2 create-a-brand | **Done** | Rail control, gated on `brand:create`, switches into the new brand |
| M1.3 email port | **Done** | `EmailPort` + `LoggingEmailSender` + `InvitationEmail` |
| M1.4 invite + accept | **Done** | `/accept-invitation` route reachable signed-out; UI no longer claims delivery that did not happen |
| M1.5 CSV export | **Done** | Creators, campaigns, attribution, commissions |

**Test counts after:** BFF 103 (was 90), DAO 22, UI 64 (was 36). All green.

### Two corrections to this document, found by building it

**1. M1.1 was mis-diagnosed here as a pure UI task.** This document said the grep returning zero UI
references meant three screens were missing. The actual break was one layer down:
`preferredRate` was **absent from the `pick()` allow-list in `ResponseShapeService.creator()`**, so
the BFF stripped it from every response. No UI could have displayed it however many screens were
added. The projection's own Javadoc warns about exactly this failure mode — *"Any feature reading a
creator's metrics has to be added here as well as to the schema; that is easy to miss because the DB
and the DAO both look correct."* [CreatorProjectionTest](InfluencerWebExperience/src/test/java/com/influencer/webe/shared/application/CreatorProjectionTest.java)
now guards it. **Also: "three surfaces" is two** — the edit drawer *is* the detail panel, opened by
row click.

**2. A prerequisite this document did not know about.** The BFF did not compile. Six untracked
share-links files sat in `webe.controller` / `webe.service` / `dao.model` / `dao.repository` /
`dao.controller` — **packages the ArchUnit boundary tests explicitly ban** ("the flat
controller/service/client packages were replaced by per-context modules"). Relocated into their
context modules; no logic changed. Nothing in M0–M1 could have been deployed or verified until this
was fixed, and no document listed it.

### Ungated M6 work that this document sizes as gated

With the YouTube key in hand, **~5 of M6's 15 dev-days are buildable now**: 6.1 outbound HTTP
client, 6.2 per-platform dispatcher, 6.4 YouTube adapter, 6.5 quota handling. M6 is sized here as a
single XL block gated entirely on approvals; that is now only half true. Building the YouTube slice
would turn Meta approval from "start a 15-day project" into "drop in an adapter" — and put at least
one real, non-hash-derived follower count on screen. Not scheduled; recorded so the option is not
lost.

---

## M0 — Instrument and unblock

**Size: S (5 dev-days)** · **Blocks: everything** · **Ship to: nobody (internal)**

**Goal:** make the rest of the roadmap measurable, and start the clocks that no engineering speed can compress.

### Scope

| # | Item | Size | Why now |
|---|---|---|---|
| 0.1 | **Submit Meta + TikTok app registrations** | 0d (non-code) | [docs/platform-app-registration.md](docs/platform-app-registration.md) tracker is empty. Meta is 2–4 weeks and **resets on reviewer changes**; TikTok 5–10 business days. Privacy policy + terms are already live at `www.tejdux.com`. There is no remaining prerequisite |
| 0.2 | **Product analytics** (PostHog or equivalent) | 2d | **Every validation signal in every document below is unmeasurable without this.** Events: signup, import-completed, card-moved, coupon-created, order-attributed, export-clicked, publish-clicked, domain-bind-clicked |
| 0.3 | **Deploy the application tiers** | 2d | Only the static legal site is on AWS. Blocks trials, webhooks, OAuth callbacks, domain hosting. Not hardened — reachable |
| 0.4 | **Set mock provider flags explicitly** | 0.5d | `web-experience.domains.provider` and `.creators.social-provider` are unset; mocks active via `matchIfMissing = true`. Write `mock` down. Converts an accident into a decision |
| 0.5 | **Gate the "Simulate an order" tool** | 0.5d | [DashboardPage.jsx:186](InfluencerUI/src/pages/DashboardPage.jsx#L186) posts `providerKey: 'mock'` on the primary revenue screen. One `if (debugFlag)`. Embarrassing if seen before it lands |

### Decide during M0, not later

**The SNI story.** M7 needs TLS termination for names you do not own. That constrains the hosting topology chosen in 0.3. Deciding it in M7 means redoing 0.3.

### Definition of done

- Every app-registration row has an owner and a submitted date.
- A public URL receives a webhook and completes an OAuth callback.
- A signup and an import appear in an analytics dashboard.
- Both provider properties explicitly set.

### Why 0.5 and not 0.2 for the simulate-order gate

It is genuinely one line. The half-day is testing that the E2E suites still pass with the flag off.

---

## M1 — Make what exists demonstrable

**Size: M (8 dev-days)** · **Ship to: five prospect demos**

**Goal:** close the demo-credibility gap. Almost nothing here is new capability — it is exposing capability that is built, tested, and invisible.

### Scope, in build order

| # | Item | Size | Notes |
|---|---|---|---|
| 1.1 | **Surface `preferred_rate` end to end** | 2d | **Do this first.** Grep of `InfluencerUI/src` returns **zero references** to `preferred_rate` or `preferredRate` — it is not on the creator form, the list, or the detail page. [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §4 calls this the one capability with no documented competitor equivalent, and it is invisible. Three surfaces: create/edit form, list column, detail panel |
| 1.2 | **Create-a-brand UI** | 1d | `createBrand` exists at [core.js:159](InfluencerUI/src/api/core.js#L159) with **zero call sites** — verified. `POST /api/brands` works. A one-field form makes the entire multi-brand story demonstrable |
| 1.3 | **Transactional email port + provider** | 2d | Postmark or SES behind a port, with a log-only local impl matching the `AssetStoragePort` / `PayoutProvider` pattern already in the codebase. Unblocks 1.4, M4, and the welcome package |
| 1.4 | **Invitation email + accept screen** | 1.5d | `acceptInvitation` exists at [core.js:188](InfluencerUI/src/api/core.js#L188) with **zero call sites** — verified. The flow is broken at both ends: nothing sends, and nothing redeems |
| 1.5 | **CSV export** — creators, campaigns, attribution, commissions | 1.5d | Zero export of any kind exists in any format, repo-wide. CSV is what agencies paste into client decks. **Not PDF** — that is M8 |

### Explicitly not here

- No white-label/branded PDF. Parity feature, not a wedge. M8.
- No cross-brand rollup. M8.
- **No new backend endpoints.** If an item needs one, it is in the wrong milestone.

### The demo script — two acts

STRATEGIC-ROADMAP.md's demo script is entirely administrative: create a brand, invite a colleague, show isolation, export. A brand does not buy tenancy. It also leads with multi-brand, which §3 of that same document explicitly says not to lead with.

**Act 1 — the wedge (60% of demo time).** This is the "why not Excel?" answer and it is *already built*:

> Sign up → import a real spreadsheet → creator list replaces the sheet → move a card through the Kanban → generate a coupon → an order attributes → ROI appears on the dashboard.

**Act 2 — the depth (40%).** This is the closing argument, not the opening one:

> Create a second brand → add the same creator to both at different rates → show each brand cannot see the other's → invite a colleague who receives a real email and redeems it → export to CSV.

Every step through the UI. No API client, no database access, no "imagine this button."

### Validation signal

**Which question do they ask next?** Instrumented via M0.2.

| Question | Meaning | Response |
|---|---|---|
| "How much is it?" | Demo cleared the bar | M2 is correctly next |
| "Does it connect to my Shopify?" | Demo cleared the bar | M3 is correctly next |
| "How do I find creators?" | **The wedge is wrong** | Stop. Reconsider discovery before spending more |

---

## M2 — Take money

**Size: M (10 dev-days)** · **Ship to: the first paying customer**

**Goal:** be purchasable. Self-serve, month-to-month, no contract, no sales call.

**Why split from M3:** STRATEGIC-ROADMAP.md bundles billing with the real Shopify connector into one Phase 2. They have different risk profiles and different dependencies, and bundling means neither ships until both do. Checkout has no external blocker; Shopify needs OAuth review and webhook infrastructure.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 2.1 | **Stripe Checkout + customer portal** | 3d | One flat price. Hosted checkout, hosted portal — do not build billing UI. **The cancel button is the product feature**; it makes "no lock-in" checkable rather than claimed |
| 2.2 | **Subscription entity + webhook handling** | 3d | `checkout.session.completed`, `customer.subscription.updated/deleted`. Subscription state must survive a webhook replay |
| 2.3 | **Make `accounts.plan` load-bearing** | 3d | Today it is set, stored, echoed, and **never read** — zero hits repo-wide for `entitlement` or `quota`. Needs a `PlanPolicy` with real checks. Note there are *two* inert columns: `accounts.plan` and `users.plan` — resolve which is authoritative |
| 2.4 | **Pricing page** | 1d | Transparent and published. IMAI, Storyclash, Kleepa and Meltwater are all demo-gated; not doing that is free differentiation |

### Pricing posture

Per [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §7.6 — **price against spreadsheets, not against Grin.**

- Clear a **"why not free"** bar, not a "why not Grin" bar. The comparison is $0.
- **Do not anchor on Truleado's $99+$29/client.** That is agency pricing for a positioning §3 says not to lead with.
- Flat tiers. **No usage metering** — metering is what Grin churned on across two 2026 overhauls; a simple price is a competitive advantage against that.
- No annual discounts, no enterprise tier, no sales-assisted motion. Each reintroduces the friction being differentiated against.
- **Set the number from M1 demo conversations.** A price invented in a document is unfounded.

### Definition of done

A stranger finds the pricing page, subscribes with a card, and cancels in-app without contacting anyone. The subscription state is correct after a replayed webhook.

### Validation signal

**Does anyone pay without a discount, a call, or a custom term?**

Secondary and equally important: **watch cancel rate honestly.** No-lock-in means churn surfaces immediately rather than deferred to renewal. That is the cost of the positioning and it must not be quietly re-litigated the first month churn looks bad.

---

## M3 — Real Shopify

**Size: L (12 dev-days)** · **Ship to: paying customers with real stores**

**Goal:** the core ROI mechanic runs against a real storefront. This is the first question a DTC brand asks.

**Why after M2:** a real integration is worth building for people who can pay for it. Also — 2.4's encryption work is cheaper to justify once revenue exists.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 3.1 | **Envelope-encrypt marketplace credentials** | 2d | Credentials are JSON-serialized as-is into `credentials_encrypted` today. **Must land before 3.2, not after.** Real credentials through an unencrypted column is the shortcut that becomes an incident |
| 3.2 | **Shopify OAuth + connection flow** | 3d | Depends on M0.3's public URL |
| 3.3 | **`ShopifyMarketplaceProvider`** | 4d | Verified: the SPI is genuinely drop-in — `MarketplaceProviderRegistry` auto-discovers via `List<MarketplaceProvider>` injection and requires no wiring. Implement `connect`, `createCoupon`, `updateCoupon`, `deactivateCoupon`, `fetchOrders`, `verifyWebhook`, `normalizeOrderEvent` |
| 3.4 | **Webhook signature verification + ingestion** | 2d | `verifyWebhook` is already on the SPI. HMAC validation, replay tolerance |
| 3.5 | **Reconciliation fallback** | 1d | `fetchOrders(since, connection)` + `POLL_ORDERS` capability. Webhooks get missed |

### Resolve before starting

**"Shopigy."** [docs/coupon-attribution-plan.md](docs/coupon-attribution-plan.md) §9 asks whether it is a real marketplace or a placeholder name. **Still unanswered.** This is a five-minute question, not a work item — answer it and either delete the reference or scope an adapter.

### Explicitly not here

- No other marketplace adapters. **Shopify only.** Prove one real integration first.
- Not Stripe Connect for payouts. That is money *out* to creators — a different integration, M8. Do not conflate the two Stripe projects.

### Definition of done

A brand connects a **real** Shopify store, generates a coupon that appears in that store's admin, drives a real order, and sees it attributed with commission accrued. The whole attribution → commission → payout chain, verified today only against `MockMarketplaceProvider`, runs against real data.

---

## M4 — Activation

**Size: M (7 dev-days)** · **Ship to: every new signup**

**Goal:** shorten the distance between signup and first value.

**Why this milestone exists at all:** it is absent from STRATEGIC-ROADMAP.md entirely, and against a free incumbent **activation is the product**. The import flow is the wedge and it is built — but there is no empty state, no guided first run, no "you're set up" moment. This is the most common way spreadsheet-replacement products fail, and it was not on the roadmap.

**Why here and not earlier:** before M2 there is nobody to activate. Before M3 the payoff at the end of onboarding is a mock number.

### Scope

| # | Item | Size |
|---|---|---|
| 4.1 | **Guided first run** — import → first campaign → first coupon, with progress | 3d |
| 4.2 | **Empty states on every primary screen**, each with the action that fills it | 2d |
| 4.3 | **Welcome email sequence** (uses M1.3's port) | 1d |
| 4.4 | **Demo seed script** — extend `tests/seed_demo_accounts.sh` to produce an agency with two brands, a shared creator at two rates, and attributed revenue | 1d |

### Definition of done

Instrumented: **% of signups that complete an import within 24h**, and **% that move a Kanban card within 7 days.** Both measurable because of M0.2. Set the baseline here; every later milestone is judged against it.

### Validation signal

This is the milestone that answers [roadmap.md](roadmap.md)'s original question — *"do imported brands come back in week two and move cards?"* It has never been answerable.

---

## M5 — Own-domain hosting

**Size: L (10 dev-days)** · **Ship to: customers publishing landing pages**

**Goal:** landing pages genuinely ship, on `pages.<yourdomain>`. **No custom domains, no registrar, no per-customer certificates.**

**Why this split matters.** STRATEGIC-ROADMAP.md's Phase 3 bundles seven items, three of which (host routing, arbitrary-SNI TLS, deploy pipeline) are new infrastructure rather than adapter swaps. That bundle is larger than M0+M1+M2 combined, and its own falsification test — *"do brands actually bind domains?"* — can only be run **after** paying for all of it.

Splitting inverts that. M5 captures most of the differentiator value with a **wildcard certificate and no registrar at all**. M7 is the expensive half, and M5 generates the data that decides whether M7 is worth building.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 5.1 | **A real hosting target** | 2d | `hostingTarget` defaults to `pages.influencrm.example` — an **RFC 2606 reserved name that cannot resolve**. A brand following the on-screen instructions today points their domain at nothing |
| 5.2 | **Wildcard cert for `*.pages.<domain>`** | 1d | One certificate. No ACME-per-domain, no SNI complexity — that is M7 |
| 5.3 | **Subdomain routing** (`<brand>.pages.<domain>`) | 2d | A `Host` → brand lookup. Repo-wide there is **no** `Host`-header resolver, no `X-Forwarded-Host` read, no hostname → `brand_domains` lookup today. `BrandDomainService.domainForPage()` resolves page → domain for *display*; nothing resolves Host → page |
| 5.4 | **Real DNS verification** | 2d | **Ship regardless of M7.** Today it is a substring check: any domain not containing the literal string `unverified` verifies instantly — *including domains the caller does not own*. This is a security defect, not a missing feature. Either fix it or disable the domain UI |
| 5.5 | **Asset serving + deploy pipeline** | 2d | Assets serve from local disk via `FilesystemAssetStorage`. Keep the port; give it a real target |
| 5.6 | **Expiry-warning scheduler** (30/7/1 days) | 1d | Endpoints are built and tested; **nothing calls them.** Moved here from STRATEGIC-ROADMAP.md's Phase 0 — its own justification is "live customer harm the moment anyone publishes," and nobody can publish until this milestone. It also needs M1.3's email port, which Phase 0 predates |

### Do not rebuild

The hosting-window logic is correct and well-reasoned: two months from **first publish** (not signup), `extendHosting` extends from `max(now, current)`, expiry returns **410 Gone** rather than 404, and expiry unpublishes without deleting. Leave it alone.

### Sequencing risk

The two-month hosting window is a **commercial trigger with no payment behind it** — `extendHosting` takes a raw `days` int and its Javadoc says "a promotion, or payment." M2 must land first, or the first expiry is a support ticket rather than a conversion.

### Validation signal — this decides M7

**Instrument the "connect your own domain" button.** Count clicks. If customers publish to `pages.<domain>` and never ask for their own, **M7 does not get built** and the roadmap saved 20 dev-days of infrastructure.

---

## M6 — Real platform metrics

**Size: XL (15 dev-days)** · **Gated on: M0.1 approvals — 4–8 weeks calendar**

**Goal:** follower counts are real. Today every metric is FNV-1a hash-derived from the handle string.

**Why the size is XL and not L:** this is presented in STRATEGIC-ROADMAP.md as "three adapters." Verified against source, it is three adapters *plus* four pieces of missing infrastructure.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 6.1 | **Outbound HTTP client** | 1d | **None exists.** The BFF has `DaoHttpClientFactory` for mTLS-to-DAO only; there is no general egress client bean |
| 6.2 | **Per-platform dispatcher** | 2d | `SocialProfileGateway.fetch(platform, handle)` is a single-bean interface with **no per-platform routing**. `MockSocialProfileGateway` ignores the `platform` argument except to echo it. Needs a registry mirroring `MarketplaceProviderRegistry` |
| 6.3 | **Creator OAuth token storage** | 2d | **No schema exists.** The OAuth config in `application.properties` covers Google/Facebook *login* only, not metrics scopes |
| 6.4 | **Instagram / TikTok / YouTube adapters** | 6d | Including `averageViews`, which **no platform returns directly**, and Instagram audience insights, which need a Business account and a separate endpoint |
| 6.5 | **Rate limiting, caching, quota handling** | 2d | |
| 6.6 | **C3 tiered metric-refresh scheduler** | 2d | Refresh spends API quota, hence tiered cadence. No `@Scheduled` refresh exists anywhere today |

### Definition of done

A creator's follower count on screen matches their actual profile, and `metrics_source` reads `platform_api` rather than `mock`. **Until that string appears in the database, this milestone is not done regardless of what is deployed.**

### If Meta review is refused

M6 and social publishing are both dead. Fallback: make manual metric entry first-class, and revisit a licensed data vendor. This is why 0.1 goes first — **rejection is information; silence is delay.**

---

## M7 — Custom domains (GATED)

**Size: XL (20 dev-days)** · **Gate: M5.7 shows customers actually want this**

**Do not build this on schedule. Build it on evidence.**

| # | Item | Notes |
|---|---|---|
| 7.1 | Cloudflare adapter behind `DomainRegistrarPort` | Verified: correctly a port. The adapter is the smallest part |
| 7.2 | ACME issuance + renewal **+ new schema** | `Certificate` is `record Certificate(boolean issued, String provider, String detail)`. There is **no column for a certificate or private key** in `2026_08_06_phase_e_domains.sql` |
| 7.3 | Host-header routing for arbitrary domains | The gap most likely to be underestimated |
| 7.4 | TLS termination with arbitrary SNI | Infrastructure, not application code. Terminating certificates for names you do not own. **Topology decided in M0** |
| 7.5 | Rollback-to-published-version | Roadmap steps E.6/E.7 have no corresponding port methods |

**Keep decision #9: no domain reselling.** The brand buys on their own registrar account. This removes a reseller agreement, markup logic, and the question of who owns a domain when a brand leaves. It is one of the best decisions in the repo.

### Definition of done

A brand connects a domain they own; DNS verification performs a **real** lookup and fails until the record genuinely exists; a real CA issues a certificate; the page serves over HTTPS. **Then: a domain the brand does not own fails verification.** That last clause is the real test — the current implementation passes the first and fails the second.

---

## M8 — Agency depth and renewal (GATED)

**Size: L (12 dev-days)** · **Gate: paying customers in the agency segment**

| # | Item | Notes |
|---|---|---|
| 8.1 | **Cross-brand rollup** | `AnalyticsController.influencerRevenue` resolves a single brand and **ignores its own `brandId` parameter**. Needs the deferred `creator_identity` projection — derived, never written to directly, so it can land any time without touching the tenancy invariant |
| 8.2 | **White-label branded reporting** | Parity with Truleado/IMAI/Storyclash. Parity, not wedge — hence last |
| 8.3 | **Real payout rails** | Stripe Connect behind the existing SPI. Also needs creator KYC (**no schema**), real idempotency keys (the current `providerRef` is a truncated UUID and **not unique per payout** — two payouts to the same creator collide), and webhook handling for async `processing → paid` (the `PayoutResult` type has only `paid()` and `failed()` factories, so **no code path can currently produce `processing`**) |
| 8.4 | **Welcome package (C2.7)** | The `CreatorApproved` trigger exists; the package does not |

---

## 1. The tax nobody scheduled

[docs/E2E-CONSOLIDATED-REPORT.md](docs/E2E-CONSOLIDATED-REPORT.md): all traffic routes through the DAO monolith, **every affected landing/workflow class exists twice**, and both copies must be edited in lockstep.

STRATEGIC-ROADMAP.md names this "a real ongoing tax on every phase" and then never schedules it. A named tax with no owner is paid in slippage.

**Recommendation: collapse the duplication between M4 and M5 (≈4 dev-days).** M5 and M7 both touch these classes heavily; paying the tax before them is cheaper than paying it during them. This is not architectural decomposition — [docs/ddd-roadmap.md](docs/ddd-roadmap.md)'s stop-and-reassess gate correctly says stop on that. This is removing a tax.

---

## 2. Sizing summary

| Milestone | Size | Dev-days | Gate | Cumulative |
|---|---|---|---|---|
| M0 Instrument + unblock | S | 5 | — | 5 |
| M1 Demo-ready | M | 8 | — | 13 |
| M2 Take money | M | 10 | — | 23 |
| M3 Real Shopify | L | 12 | — | 35 |
| M4 Activation | M | 7 | — | 42 |
| *Duplication cleanup* | *S* | *4* | — | *46* |
| M5 Own-domain hosting | L | 10 | — | 56 |
| M6 Real metrics | XL | 15 | **App approvals** | 71 |
| M7 Custom domains | XL | 20 | **M5 demand signal** | 91 |
| M8 Agency depth | L | 12 | **Agency customers** | 103 |

**~56 dev-days to a complete, purchasable, real-integration product.** ~11 working weeks for one engineer at full utilisation — realistically 14–16 with interruptions.

**~47 further dev-days are gated on evidence**, and two of those gates may never open. That is the point: M7 and M8 are 32 dev-days that get built only if customers ask.

---

## 3. What is deliberately NOT in this roadmap

Unchanged from [STRATEGIC-ROADMAP.md](STRATEGIC-ROADMAP.md) §3, which argues each correctly. Summarised so this document stands alone:

| Not building | Why | Revisit if |
|---|---|---|
| **Creator discovery database** | Requires a licensed vendor or scraping; scraping risks the developer app M6 depends on. Different product category, different buyer | M1 demos keep ending in "how do I find creators?" |
| **Authenticity / fake-follower scoring** | The data cannot be legitimately obtained — Instagram exposes no follower-list endpoint. Not a budget problem. Ship `"Engagement pattern: unusual"`, never `"34/100"` | Three complaints in a quarter, or one on a creator our signal rated clean |
| **Social publishing (Phase F)** | Blocked on the same app registrations; most security-sensitive item in the roadmap | Two paying customers ask |
| **More architectural decomposition** | Seven services, 189 tests, ~22k LOC, **one contributor**. The stop-and-reassess gate requires a named driver and none is present. *"It would be cleaner" is not a driver* | Independent scaling, separate teams, or a compliance boundary |
| **Enterprise sales motion** | Contradicts the positioning. Self-serve with no contract is the answer to Grin's most-cited complaint | — |
| **Multi-brand as the headline** | Truleado owns that pitch at $99+$29/client. **Keep the capability** — M1.2 makes it demonstrable — decline the headline | — |

---

## 4. What would falsify this plan

| Signal | Meaning | Response |
|---|---|---|
| M1 demos end in "how do I find creators?" | The wedge is wrong | Stop. Reconsider discovery — probably licensed data, not build |
| M2 converts nobody without a discount | Spreadsheets are winning the "why not free" test | Re-examine price, or whether attribution alone is enough value |
| M4 activation stays below ~30% import-completion | The wedge is right and the funnel is broken | Fix onboarding before building anything further |
| Nobody clicks "connect your own domain" in M5 | The landing builder is not the differentiator assumed | **Never build M7.** Bank the 20 days |
| Meta review refused outright | M6 and social publishing are dead | Manual metric entry as first-class; revisit a data vendor |
| A competitor ships per-brand rates | The one uncontested differentiator is contested | Fall back to the *combination* bet; compete on execution speed |

**The load-bearing idea, from [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md) §4:** a distinctive combination of individually-copyable features is a positioning strategy, not a moat. Grin could close any single gap in 1–2 quarters. Defensibility comes from execution speed and vertical integration — one data model feeding every surface.

**Which is the argument for this sequence.** The backend depth is genuine and rare. It only becomes defensible if the product is purchasable long enough for anyone to reach month six.
