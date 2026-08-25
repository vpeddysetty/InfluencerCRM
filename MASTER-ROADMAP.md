
# InfluenCRM — Master Roadmap

**Date:** 2026-08-19
**Status:** the single scheduling authority. Supersedes six earlier roadmap documents (Appendix B).
**Context:** pre-revenue startup, zero subscribers, one founder-engineer, Claude-assisted.
**Sizes:** dev-days.

---

## 1. How to read this document

### 1.1 Precedence — why this document can be trusted

Eight roadmap documents accumulated between 2026-07 and 2026-08. They disagreed with each other and,
more often, with the code: 135 commits landed after 2026-08-01 while most of those documents are
dated 2026-08-06/07. Several described as "not built" things that exist, are tested, and are running.

When sources conflict, resolve in this order:

> 1. **The code.** A class on disk or a passing test beats every document.
> 2. **`PENDING-WORK-ROADMAP.md`** — the newest, and the only one written by checking code.
> 3. **`EXECUTION-ROADMAP.md`** — sequencing only, where the code is silent.
> 4. **`STRATEGIC-ROADMAP.md` / `UI-OPPORTUNITIES-ROADMAP.md`** — intent and rationale only.

`UI-OPPORTUNITIES-ROADMAP.md`'s documented exception (U2 outranking EXEC on sequencing) is folded in
below, and **retires with this consolidation.**

### 1.2 The `Verified by` contract

Every `SHIPPED` row names a **path that proves it**. An empty `Verified by` on a `SHIPPED` row is a
defect in this document, not a formatting choice. This column is the whole reason the status ledger
is believable — it is what the eight predecessors lacked.

`Was claimed` records where a superseded document said something different. It is an audit trail for
this consolidation and **should be deleted after one release cycle** — it exists so you can trust the
migration, not forever.

### 1.3 What "SHIPPED" means

Code on disk **and** a test, or a live-verified deployment. Not "written", not "endpoint exists".
Where something is built but switched off or unreachable, the status is `PARTIAL` and the blocker is
named.

### 1.4 ID scheme

| Prefix | Domain |
|---|---|
| `PR-` | Product — customer-facing features |
| `IN-` | Infrastructure — environments, state, pipeline |
| `AD-` | Admin console — Tejdux operator tooling |
| `AG-` | Agents — scanners and digests |
| `RV-` | Revenue tooling |
| `OP-` | Operational readiness |

IDs are **stable forever and never renumbered.** Old IDs (`M4.2`, `U2`, Tier/Phase numbers) appear
throughout git history — see the crosswalk in Appendix A.

### 1.5 Test counts

**This document deliberately contains no test counts.** They were irreconcilable across all eight
predecessors (fourteen different totals) precisely because they rot on every commit. Run the suites
instead: `mvn -o test` per module, `npm test` in the UI.

---

## 2. Where we actually are

### 2.1 The honest headline

**The product is much closer to charging money than the paperwork suggested.** Roughly 45–50 of
EXECUTION-ROADMAP's 56 ungated dev-days are already shipped. The remaining gap is commercial, not
technical — PRODUCT-GAPS.md §0 put it best, and it still holds:

> InfluenCRM is strong precisely where buyers cannot see, and absent precisely where they look first.

Billing is code-complete and switched off. There is no public pricing page. Nothing greets a new
signup.

### 2.2 Shipped

| ID | Item | Verified by | Was claimed |
|---|---|---|---|
| `PR-20` | Billing path: checkout, portal, webhooks, replay guard | `webe/billing/`, `identity/application/BillingWebhookService.java`, `V32__m2_subscriptions_billing.sql` | — |
| `PR-21` | Plan entitlements enforced (402 at four creation points) | `identity/application/PlanPolicy.java`, `EntitlementService`, `shell/plan.js` | EXEC M2.3 "accounts.plan never read" |
| `PR-22` | Marketplace credential envelope encryption | `marketplace/CredentialCipher.java`, `CredentialProtector.java` | — |
| `PR-23` | Real DNS domain verification (TXT at `_influencrm-verify`) | `content/infrastructure/DnsDomainRegistrar.java` | STRAT Phase 3 placed it 3 phases later |
| `PR-24` | Hosting expiry warnings (30/7/1) | `content/application/HostingExpiryScheduler.java`, `V33` | GAPS Tier 4 "nobody has published" |
| `PR-25` | Creator record page `/creators/:id` | `pages/CreatorRecordPage.jsx` | UI-OPP U1: "zero detail routes, no useParams anywhere" |
| `PR-26` | Metrics provenance badges | `V34`, creator drawer + record page | UI-OPP U4 "hard gate on M6" |
| `PR-27` | Social metrics infrastructure + real YouTube adapter | `creator/infrastructure/DispatchingSocialProfileGateway.java`, `YouTubeProfileAdapter.java`, `shared/infrastructure/OutboundHttpClient.java`, `V34` | EXEC M6 table: "XL 15d, gated entirely on approvals" |
| `PR-28` | Payout idempotency | payout id is the idempotency key | EXEC M8.3, gated |
| `PR-29` | Order ingestion authenticated + idempotent | `attribution/api/WebhookController.java`, `V35` | GAPS: "no dedupe" |
| `PR-30` | M1 demo-credibility set: preferred rate, create-a-brand, email port, invite+accept, CSV export | `shared/application/EmailPort.java`, `identity/application/InvitationEmail.java`, `MembersPage.jsx` | DDD: "member invitation is not built" |
| `PR-31` | Legal pages, consent capture, deletion requests | `docs/legal/`, `V36`, `V37` | — |
| `PR-32` | Zero-trust chain: workload tokens, `X-App-Id`, tenant scoping | `shared/workload/`, `dao/security/TenantScopeFilter.java` | — |
| `OP-01` | Email `from` duplicate-declaration fix | `shared/EmailFromPropertyTest.java` | **found 2026-08-19** |
| `PR-33` | Provider-enforced trials + annual billing | `billing/BillingProvider.java` (`BillingInterval`, `expiresTrials`), `SubscriptionService.subscribe`, `BillingWebhookService` `trial_will_end` | **added 2026-08-21.** `trial_ends_at` existed since `V32` and nothing read it, so a trial granted paid limits forever |
| `PR-36` | Consent evidence: URL, hash and immutable snapshot of the accepted text | `V39`, `identity/infrastructure/ConsentEvidenceWriter.java`, `shared/infrastructure/AwsSigV4.signS3Put`, `consent-evidence.tf` | **added 2026-08-23.** `V36` recorded a version string; the published page is mutable, so the label pointed at text nobody kept. Object Lock COMPLIANCE 7y |
| `PR-37` | Deletion requests received, approved and executed | `V40`, `deletion-intake.tf`, `identity/application/DeletionRequestPolicy.java`, `DeletionRequestService.java`, `dao/identity/api/DeletionRequestController.java` | **added 2026-08-23.** `V37` built the table and nothing wrote to it — the only writer was in `InfluencerIdentityService`, which serves no traffic |
| `PR-38` | DPA and published sub-processor list | `docs/legal/dpa.html`, `docs/legal/subprocessors.html` | **added 2026-08-23.** Privacy policy promised both "on request" and neither existed; Art. 28(3) makes the DPA mandatory, not optional. **DPA needs counsel review before it is offered** |
| `OP-12` | Consent snapshot overwrite guard | `ConsentEvidenceWriter.existingDigest`, `AwsSigV4.signS3Head` | **found 2026-08-23 by causing it.** Editing a policy without bumping its version wrote new text under the old version key; Object Lock preserved the original, but the key had two meanings |

### 2.3 Blocked on procurement only — start the clocks

| ID | Item | Owner | Note |
|---|---|---|---|
| `PR-04` | Stripe live keys | you | Code is done. Test-mode catalogue built 2026-08-21 (`TejDux Pro`/`TejDux Agency`, 4 price ids); **live** keys still need applying for, and have a lead time |
| `OP-06` | SES domain verification + sandbox exit | you | **Blocks every email feature and both agents.** See `docs/ses-setup.md` |
| `PR-27b` | Meta app review (Instagram) | Meta | ~2 days. Deliberately **not** on the critical path |
| `PR-10b` | Shopify Partner app | you | Only needed when `PR-10` starts |

### 2.4 Claims retired by this document

The eight predecessors contained 27 documented conflicts. These are the ones that changed a decision:

| Claim | Where | Reality |
|---|---|---|
| "M6 is XL/15d, gated entirely on approvals" | `EXECUTION-ROADMAP.md:320` | ~9d shipped; EXEC's own progress log at `:92` already contradicted its table |
| "zero detail routes, no `useParams` anywhere" | `UI-OPPORTUNITIES-ROADMAP.md:69` | `/creators/:id` shipped the same day |
| "member invitation UI is not built" | `docs/ddd-roadmap.md:564` | `MembersPage.jsx`, 259 lines |
| "Phase E domains — Shipped" | `landing-page-builder-roadmap.md:59` | State machine only; the external half was absent. **Now** genuinely real via `DnsDomainRegistrar` |
| "accounts.plan is set, stored, echoed, never read" | `EXECUTION-ROADMAP.md:196` | `PlanPolicy` + `EntitlementService` enforce it |
| Expiry scheduler placed in Phase 0 / M5.6 / Tier 4 | STRAT / EXEC / GAPS | Shipped; the placement argument is moot |
| Free tier = 3 seats | `PENDING:207,290` | **1 seat** — 3 gave away the thing worth charging for |
| "do not invent a price" vs a published price table | EXEC/STRAT vs `PENDING:287` | Resolved in §6 Decision 1 |
| Fargate/ECS/ALB deployment topology | `infrastructure/README.md` | **Stale history.** Live is Docker Compose on one EC2 Spot instance behind Caddy |

---

## 3. The goal: ready for paid subscription

### 3.1 Definition of done

1. A stranger finds a public pricing page with a real number on it.
2. They subscribe with a card, without a call or a discount.
3. They cancel in-app, without contacting anyone.
4. Subscription state is correct after a **replayed** webhook.
5. Transactional email actually arrives (and passes DMARC).
6. A new signup reaches first value without a guided tour from you.
7. The environment they land on has tested backups and cannot lose their data.

Items 1–4 are Stage 1. Item 5 is `OP-06`. Item 6 is `PR-02`. Item 7 is Stage 2.

### 3.2 Deliberately out of scope

See §5 Stage 4. The short version: ~45 dev-days of scale, polish and integration work is deferred
until a paying customer asks for it. **Every one has a named trigger.**

---

## 4. Cost discipline

Production targets **~$95–115/month**. Anything above that needs a paying customer behind it. The
current environment is ~$40/mo. The full HA design in `infrastructure/prod/README.md` is $250–350/mo
and is **not** being built yet — see §5 Stage 2 for what is cut and why.

---

## 5. Execution plan

Sequenced by *what blocks taking money, safely* — not by size, not by document order.

### Stage 0 — Hygiene (3d) — IN PROGRESS

| ID | Item | Size | Status |
|---|---|---|---|
| `OP-01` | Duplicate `email.from` declaration | 0.25 | ✅ Done — `EmailFromPropertyTest`, 345 BFF tests green |
| `PR-00` | This document + crosswalk | 2 | ✅ Done |
| `IN-01a` | Archive superseded roadmaps | 0.25 | ✅ Done — `docs/archive/roadmaps/`, bodies byte-match git |
| `IN-01b` | Rename misleading scripts; account-id assertion in test Terraform | 0.5 | ✅ Done — `account-guard.tf` |
| `IN-00` | **Remote Terraform state** — S3, versioned, **native locking** | 0.75 | ✅ Done — required Terraform 1.10+; upgraded 1.9.8 → 1.15.9 |
| `OP-06` | SES verification runbook | 1 | ✅ Written (`docs/ses-setup.md`) — **console/DNS steps are yours** |

**Stage 0 findings worth carrying forward:**

- **`use_lockfile` needs Terraform ≥ 1.10.** The repo comment promising "no DynamoDB table needed"
  was aspirational — on 1.9.8 `init` fails with *"An argument named use_lockfile is not expected
  here"*. Upgraded to 1.15.9 and proved the plans identical before and after. `required_version` is
  now `>= 1.10.0` so the next person gets a version error, not a backend error. **No DynamoDB table
  exists or is needed.**
- **The account guard had to be a `precondition`, not a `check` block.** A failed `check` is only a
  *warning* — Terraform prints it and applies anyway. A precondition errors and stops.
- **A stale `image_tag` is a silent rollback.** Planning with `-var image_tag=v1.0.19` while live ran
  `v1.0.21` produced a plan that would have rolled all 12 services back two versions, showing zero
  deletes and zero replaces — just two innocuous "update" lines. **Read the rendered diff, not the
  action counts.**
- **SES is worse than assumed:** sandbox *and* zero verified identities, so it would fail every send.
  Also no SPF record, while Google Workspace owns the MX. See `docs/ses-setup.md`.

### Stage 1 — Take money (~10d)

*Gate: can a stranger pay us?*

| ID | Item | Size | Note |
|---|---|---|---|
| ~~`PR-01`~~ | Public pricing page — **written 2026-08-21**, `docs/legal/pricing.html`. Prices and limits cross-checked against the live Stripe catalogue and `PlanPolicy`. **Not deployed**: needs an S3 upload to the legal bucket and a `/pricing/` route, same as `/terms/` | 0.5 | Every competitor is demo-gated; publishing a price is free differentiation |
| ~~`PR-35`~~ | **AI campaign-page generation** — goal-first authoring: brief → 2–3 drafts → compare → edit in the builder. **PARTIAL: the AI path is switched off.** Screens 1-6 are all built — brief → drafts → compare → section-level rewrite → regenerate-one-variant → schedule publish. `web-experience.landing.generation.provider` defaults to `template`, so the shipped default is the deterministic generator, not a model. **Blocker: Anthropic account credit.** A key is issued and wired — stored as `influencrm-prod/page-generation-api-key`, injected as `WEBE_PAGE_GENERATION_API_KEY`, and the `page_generation_provider` variable added (deployed 2026-08-24). It authenticates but the account balance is zero, so selecting `anthropic` would make every generation a failing call that falls back to the template drafts users already get instantly — strictly worse than leaving it off. Once credit is purchased the cutover is `page_generation_provider = "anthropic"`: one variable, no rebuild. Drafts convert to the existing `blocks` shape, so a chosen draft saves and renders through the landing-template path that already existed. **Verified by:** `content/application/PageGenerationPort.java`, `CampaignPageGenerationService.java`, `content/infrastructure/TemplatePageGenerator.java`, `AnthropicPageGenerator.java`, `content/api/CampaignPageGenerationController.java`, `CampaignPageGenerationTest` (10 tests), `CampaignPageGenerationControllerTest` (5 tests, the BFF's first `@WebMvcTest`), `CampaignPageEditingTest` (7), `BriefEnricherTest` (4), `ScheduledPublishTest` (8), `content/application/BriefEnricher.java`, `ScheduledPublishScheduler.java`, `schema/flyway/V41__scheduled_landing_publish.sql`, `shell/contentRemoteCopies.test.mjs`. **Design spec:** `docs/AI-Campaign-Page-Authoring-Design-Spec.md` | 6 | **Two unconverged AI paths.** `agent_service/` (Python) already had `POST /content/draft` with a `kind=landing` branch, on **OpenAI** (`gpt-4.1-mini`) — it backs the brief form's existing "Draft with AI" button and returns a single `{hero, body, cta}`, which cannot express the compare-variants flow. This row's generator is a **second** integration, in Java, on Anthropic. Kept separate deliberately (decided 2026-08-23): converging is a new `PageGenerationPort` `@Component` calling a variant-shaped `agent_service` endpoint, not a rewrite. Cost until then: two prompt locations, two vendors, two API keys. **Note for anyone auditing AI usage: grepping the Java tree and `*.properties`/`*.yml` finds nothing for `agent_service` — it is Python, and is how this duplication arose.** |
| `PR-39` | **Curated section editor** — replace GrapesJS with fixed, professionally-designed section types the brand fills in, plus 8 built-in templates and brand-saved custom templates. **Piece A landed 2026-08-24** (schema + renderer): `schema/flyway/V42__landing_sections.sql` adds `sections jsonb` to `content.landing_templates` AND `landing_template_versions`, and `LandingService.renderSections` renders typed sections server-side with nothing brand-authored reaching the page as markup — 14 tests in `InfluencerWebExperience/src/test/java/com/influencer/webe/content/application/LandingSectionRenderTest.java` pin the `sections` -> `document` -> `blocks` precedence and the escaping. The rendered page uses the approved design (Swiss/minimal, Newsreader/Inter, terracotta) and every colour pair was contrast-checked against WCAG AA on all three section grounds — two tokens moved as a result. Also landed alongside: `LandingStageService.requirePublishable` now counts `sections` as content (a section-only page was previously refused as empty), a `GET /api/landing-pages/{id}/publish-readiness` advisory that warns when a page has no coupon and therefore no sales attribution, and UTM tagging on outbound CTAs so a coupon-less page still attributes VISITS to campaign and creator. Fingerprinting/attribute-matching was considered and rejected: personal data on an anonymous page with no consent gate, and guesswork that mis-attributes revenue. **Pieces B, C and D landed 2026-08-24.** B: `InfluencerUI/src/shell/sectionTypes.js` is the single source of truth for the 8 types and their 13 variants, all styled and render-verified at 390/1280. C: `InfluencerUI/src/components/SectionEditor.jsx` — canvas previewing the REAL server renderer in an iframe, one context panel, up/down reorder (not drag-and-drop: keyboard- and touch-reachable), four preview widths incl. Laptop 1024, panel drops below the canvas under 900px, token highlighting that flags unrecognised tokens, and per-section AI rewrite mapped onto the existing `/api/campaign-pages/sections/rewrite`. D: 8 built-in templates in `shell/pageTemplates.js` (6 mapped to the brief form's campaign types + Photo-led/Story-led), switching keeps the words and warns BEFORE discarding; `V43__brand_page_templates.sql` + `/api/brand-page-templates` for saved templates, metered as `PlanPolicy.Resource.SAVED_TEMPLATE` (free 2 / pro 20 / agency unlimited), with creator identity stripped server-side. The flag `web-experience.landing.editor` is now REAL: read by `LandingController.editor` and honoured by the UI, defaulting to `builder`. Tests: 497 BFF, 48 DAO, 266 UI. **Still PARTIAL** — the flag ships `builder`, so no brand sees the new editor until it is flipped to `sections`, and GrapesJS stays in the bundle until a clean release on `sections`. The flag `web-experience.landing.editor` is NOT yet read anywhere — precedence alone decides the render path, which is safe only because nothing writes `sections` today; wiring the flag belongs with piece C. The founder's report was that the builder "looks cheap", and the evaluation found that is a consequence of a free-form box canvas rather than of its theme: page quality becomes a function of the user's design skill, and the user is a brand manager with none. Three facts decided it, all verified in code — the stack is already section-shaped (`PageGenerationPort.Section`, `rewriteSection`, `renderLegacyBlocks`), the AI's typed sections are currently flattened to an HTML blob on entry so `rewriteSection` has nothing to point at, and `LandingDocumentSanitizer`'s own header records that the typed-block renderer was "safe by construction" and a visual builder "inverts that". Also deletes **951 KB** (261 KB gzipped), the largest asset in the app, vendored twice. Ships behind `web-experience.landing.editor` (`sections` or `builder`, default `builder`) so rollback is a variable flip; GrapesJS is removed only after a clean release on `sections`. **Evaluation:** `docs/Landing-Editor-Framework-Evaluation.md` **Plan:** `docs/Curated-Section-Editor-Implementation-Plan.md` | 16-23 | Prototypes approved 2026-08-24 (page render, editor interaction, template gallery). Migration is additive — `sections` → `document` → `blocks` precedence rewrites no rows, so the live published page is never at risk; there is deliberately **no HTML-to-section parser**, since that is an unbounded heuristic run against customer pages to save minutes of hand-rebuilding one. Saved templates need their own table: `uq_landing_templates_campaign` enforces one page per campaign and V24 records that as deliberate, so reusing that table would weaken the invariant for every real page. **The honest counter-argument is in the plan's §6** — this is a rewrite on an aesthetic complaint, and restyling GrapesJS in 1-2 days is the cheaper option that raises the floor without changing the ceiling |
| `PR-04` | Stripe cutover — products, keys, webhook secret, `provider=stripe` | 1 | Config, not code |
| `PR-34` | Apollo enrichment + lead-to-workspace workflow | 3 | Commercial enablement: sync Apollo accounts/contacts into Tejdux as brand or agency records, assign tier + owner, create onboarding tasks and first campaign brief. Apollo is the source of qualified leads, not the creator database or source of truth; keep it out of the operational creator workflow. |
| ~~`OP-11`~~ | Stripe Tax — **done 2026-08-21 (test mode)**. Head office set, `txcd_10103001` on both products, `automatic_tax` + `tax_id_collection` on checkout. **Zero registrations by choice**: no nexus at zero revenue, so it computes $0 and collects nothing until one is added. UK/EU deferred — see Decision 8 | 0 | Was: VAT is owed from the first EU/UK sale; retrofitting onto issued invoices is painful |
| `PR-02` | **Activation** — guided first run, empty states, welcome email, demo seed | 7 | The highest-value product work remaining. Against a free incumbent, activation *is* the product |

### Stage 2 — Production environment (~9d)

*Gate: can we run it without losing their data?* Detail in §7.

`IN-02` Organizations account → `IN-02b` remote state in the new account **before first apply** →
`IN-03` lean Terraform (written fresh, not copied) → `IN-03b` DAO cert SAN + IAM role split →
`IN-04` build pipeline → `IN-05` cutover rehearsal including a **restore drill**.

### Stage 3 — Operate it (~14d)

*Gate: can one person run this?*

`RV-01..03` revenue tooling (3d) → `AG-01` AWS posture scanner (2d) → `AG-02` Gmail support digest
(3d) → `AG-03` single daily owner email (1d) → `AD-01..05` admin console (8d, overlapping).

### Stage 4 — Deliberately NOT now

| Item | Old ID | Size | Trigger to revisit |
|---|---|---|---|
| Server-side pagination | U2 | 10 | First customer with >1,000 creators |
| Shopify marketplace provider | M3.2–3.5 | 8 | A prospect names it in a demo |
| Rate intelligence, saved views, global search, ⌘K | U3/U5/U6/U7 | 17 | First paying cohort says which |
| Duplication cleanup | EXEC §1 | 4 | Do it inside other stories, never as a sprint |
| Instagram/TikTok adapter bodies | M6.4 | 6 | Meta approval |
| Custom domains / per-brand subdomains | M7 | 20 | `domain-bind-clicked` shows demand — design in §9.2 |
| Product-analytics pipeline | M0.2 ext. | 1+ | When there are retained users to measure |
| Social publishing | LPB-F | — | Declined in all four predecessors |

**U2 is the clearest defer in the backlog:** a quarter of the remaining product budget on scale work
for zero users. Nobody has enough rows to page.

**Critical path to first paying subscriber: ~35–40 dev-days.**

---

## 6. Decisions log

1. **Price: $49 Pro / $149 Agency monthly; $470 / $1,430 yearly (20% off). Free = 1 brand /
   25 creators / 1 seat. 30-day trial on Agency only.** Revised 2026-08-21 from $79/$199.

   Grounded in MARKET-ANALYSIS: the contested SMB band is $49–798, and Grin gates its actual CRM
   behind $500/mo capped at 100 creators. The revision moves to the **floor** of that band because
   §6 of that document names the real competitor — *"80%+ of influencer marketers report using
   spreadsheets… the competition to beat is Excel, not Grin. Any pricing must clear a 'why not
   free' bar."* $49 is a tool a team buys without budget approval; $79 is one that needs a
   conversation.

   Agency at $149 flat is set against Truleado's published $99 + $29/client — flat wins from two
   clients up, and metered pricing would undercut the no-lock-in positioning that is our cheapest
   differentiation against Grin's most-cited complaint.

   The 30-day trial is Agency-only because the free tier allows **one brand**, so a multi-client
   agency cannot evaluate the tier that matters to them; a single-brand Pro buyer can.

   **Confidence: low.** MARKET-ANALYSIS §6 records no data on agency software budgets and
   recommends surveying 30–50 agencies before underwriting GTM. These are anchored on the one solid
   published comparable (Truleado) and should be treated as a starting position to test, not a
   settled figure.

   This does **not** conflict with EXEC/STRAT's "do not invent a price" — that rule was about not
   *hardcoding*. Prices live in Stripe and the pricing-page copy only; the test keeping prices out
   of the codebase stays. Live test-mode catalogue: `TejDux Pro` / `TejDux Agency`, four price ids,
   verified 2026-08-21.
2. **Lean production, single instance, deliberately.** See §7.
3. **Separate AWS account** for production, under Organizations.
4. **Do NOT rename the existing environment.** See §7.1 — this one will look wrong without its
   reasoning.
5. **Admin console is a separate application**, not a role inside the customer app.
6. **Instagram token: alarm only.** The 60-day expiry surfaces in the daily digest; auto-refresh
   deferred (`instagram-token.py` already holds the exchange logic).
7. **Automate the build; gate the apply.** No pipeline may run `terraform apply` or
   `start-instance-refresh` unattended.
8. **US-only at launch; no UK/EU sales until VAT is registered.** Decided 2026-08-21. Stripe Tax is
   active with **zero registrations**, which is the correct state for a seller with no nexus: it
   computes $0 and collects nothing. The roadmap's original `OP-11` note — *"VAT is owed from the
   first EU/UK sale"* — is right, and the answer is not to collect it speculatively but to not sell
   there yet. Registering a jurisdiction in Stripe starts collection with **no code change**, so
   this is reversible the day the obligation is real. The trigger to revisit is the first EU/UK
   inbound lead, not a date.

---

## 7. Production environment design

### 7.1 The naming collision — do not rename test

`infrastructure/test/terraform/variables.tf:11` defaults `environment = "prod"`. Every live resource
is `influencrm-prod-*`, and that environment serves the real `tejdux.com` with real credentials.

**Renaming would force replacement of nearly all 139 resources** — RDS identifier, ASG, ECR repos,
Secrets Manager secrets — on the live site. That is an outage plus a credential re-bootstrap, and it
violates the stop-on-delete/replace rule roughly a hundred times over. The benefit is cosmetic.

**The separate account already removes the collision** — resource names are account-scoped. Instead:
a README header stating the directory's `environment` is `prod` for historical reasons; an
**account-id assertion** so applying test config against prod hard-fails (*this* is the real safety
property — the name never provided it); and renaming the two misleading `.bat` scripts.

### 7.2 Lean vs HA

`infrastructure/prod/README.md` is correct that Redis → 2nd instance → ALB → drop-the-EIP is **one
change, not four**. Lean prod **declines that entire bundle** rather than pretending to do a cheap
version of it.

| Concern | HA ($250–350) | Lean (chosen) |
|---|---|---|
| Instance | ≥2 On-Demand, multi-AZ | **1 On-Demand** — removes random Spot reclamation. The one upgrade worth paying for |
| Sessions | ElastiCache Redis | JVM heap — cut; only meaningful with 2 instances |
| Load balancer | ALB | Caddy on-instance — cut ~$17 |
| RDS | Multi-AZ + cross-region | **Single-AZ, 30-day backups, deletion protection ON, cross-region snapshot copy.** Never cut backups: Multi-AZ is *availability*, backups are *durability*. Pre-revenue you survive downtime, never data loss |
| NAT | NAT + private subnets | Public subnet, tight SGs — cut ~$32 |
| WAF | AWS WAF | Cut; revisit at real traffic |
| DAO cert SAN | Fixed | **Must fix** — BFF→DAO TLS verification is currently off. Non-negotiable |
| IAM | Per-task roles | Split where compose allows — one role currently reads every secret |

Build the HA path as `variable "high_availability" { default = false }` gating ALB, ASG `max_size`
and ElastiCache — a variable flip, not a rewrite.

**`OP-17` is a prerequisite on that flip:** scheduling is plain Spring `@Scheduled` with no ShedLock,
so a second instance double-sends every expiry warning and every digest.

### 7.3 Every apply follows the standing rule

`terraform plan -out` → enumerate every non-no-op from `terraform show -json` → **stop on any delete
or replace** → diff DNS, CloudFront aliases, bucket policies and IAM against the **live API** → read
the Outputs diff → report before applying. Port `preflight.sh` and the
`iam simulate-principal-policy` pattern rather than reinventing them.

---

## 8. Operational readiness

**P0 — inside this plan:** `OP-01` email fix ✅ · `IN-00` remote state ✅ · `OP-06` SES ·
`OP-02` Instagram token alarm · `OP-03` backup **restore** drill · `OP-04` retire the
`AdministratorAccess` static key (the two least-privilege policies in `infrastructure/iam/` are
written and **not attached** — the work is half-done).

**P1 — before the first paying customer:** `OP-07` GDPR/CCPA response clocks (a missed statutory
deadline is a fine, not a bug) · `OP-08` AWS Budgets · `OP-09` uptime monitor + status page ·
`OP-10` dunning (`past_due` exists; nothing emails anyone) · `OP-12` store the ToS version with
consent.

**P2 — post-revenue:** log retention/PII · data-retention policy · PCI SAQ-A documentation (`V32`
already stores no card data — that design *is* eligibility) · incident runbook and real alarms ·
`OP-17` multi-instance double-send.

**The two most likely to be underestimated:** SES, because it fails *silently* and invalidates both
agents, and the Instagram token, because it fails on a *timer* ~60 days after launch with no code
change to blame.

---

## 9. Landing-page environments and hosting (added 2026-08-21)

Raised after publishing the first real landing page to production (`Trailhead Collection`, campaign
`Gifting`, slug `c-a781eb6b`). 9.1 and 9.2 are **design only — nothing is built.** 9.3 and 9.4 are **live defects** found while
publishing that page.

### 9.1 `IN-06` — Environment ladder for landing pages

**Ask:** author a page locally, promote it to a test deployment, then to prod.

**Why it is not `stage`.** `LandingStageMachine.STAGES` is an eight-value *editorial* workflow
(`draft → review → approved → creator_assigned → content_needed → ready_to_publish → published →
performance_tracking`). It records where a page sits in its **approval** process. It carries no
environment dimension, and overloading it with one would make "published" ambiguous between
*approved by a human* and *deployed to prod* — two states that must be independently true.

**The blocker is that there is no second environment.** `infrastructure/test/terraform` **is prod**
(§7.1: `variables.tf:11` defaults `environment = "prod"`, serving real `tejdux.com`). So the ladder
has nowhere to promote *from*. `IN-02` (Organizations account) is a hard prerequisite — until a
genuinely separate account exists, "promote to test" has no target.

**Shape, once `IN-02` lands:**

| Piece | Design |
|---|---|
| Column | `landing_templates.environment` — `local` / `test` / `prod`. NOT a rename of `stage`; both columns coexist |
| Uniqueness | `uq_landing_templates_campaign` becomes `(campaign_id, environment)`. Today it is campaign-only, so one campaign cannot hold a per-environment copy |
| Promotion | Copy-forward, never move: promoting `test → prod` writes a new row and leaves test intact, so a bad promotion is not also a deletion |
| Identity | `public_slug` must be stable across environments or every promotion breaks live creator links. Slug belongs to the campaign, not the row |
| Direction | Promotion is one-way. Demotion is a *new promotion from the lower environment*, mirroring `PUBLISHED` never returning to `DRAFT` |
| Auth | Cross-environment writes need a workload token, not a browser cookie — see §7 and the zero-trust chain (browser → DPS → BFF → DAO) |

**Size: 5d** — 1d schema/uniqueness, 2d promotion + copy-forward, 1d UI, 1d tests. **Excludes**
`IN-02`, which it depends on entirely.

**Open question to settle before building:** is `local` real, or does the ladder start at `test`?
A local environment implies each developer's DB is a promotion source, which needs an export/import
path rather than a column. Recommend `test → prod` only for v1.

### 9.2 Per-brand subdomains (`trailhead.tejdux.com`)

**Ask:** serve each brand's landing page from a branded subdomain.

**What already exists:** `BrandDomainController` — connect / verify / disconnect, DNS records
returned for the brand to create, cert issued on verification. **Decision #9 is the catch:** the
platform deliberately never buys domains — the brand connects one *they already own*. That feature
is built for `landing.brandsite.com` pointing at us, which is the opposite direction from
`trailhead.tejdux.com`.

**Subdomains of our own domain are a different, unbuilt thing:**

- Wildcard DNS `*.tejdux.com` and a wildcard ACM cert (us-east-1, for CloudFront)
- Host-based routing so `trailhead.tejdux.com` resolves brand → slug
- A reserved-name list — `www`, `api`, `app`, `mail` must never be claimable by a brand
- Tenancy check: a brand claiming another's name is an impersonation vector, not a naming clash

**This is a Terraform change on the live distribution**, so §7.3 applies in full: plan, enumerate
non-no-ops, stop on any delete/replace, diff CloudFront aliases against the live API. That
distribution has already produced one routing surprise (§9.3), which is reason for more care.

**Size: 20d**, tracked as the existing Stage 4 `Custom domains` row. Recommend keeping it deferred:
it is presentation, and no user has asked for it outside this session.

### 9.3 `IN-07` — `/s/*` is not routed on the apex (**live defect, not design**)

A published page renders at `https://api.tejdux.com/s/{slug}/{creator}` but **not** at
`https://tejdux.com/s/...`, which returns 200 and serves the SPA marketing shell instead. The
builder UI displays the path as `/s/c-a781eb6b/northbound25`, which reads as the apex — so **the
link a brand copies and sends to a creator is dead**, while looking alive.

Same class as the `/privacy/` and `/terms/` alias conflict: the route exists server-side (`LandingController`,
`SecurityConfig` line 42 permits `/s/**` anonymously) but CloudFront never routes it there.

**Size: 0.5d.** Either add a `/s/*` cache behaviour to the origin, or make the UI show the URL that
actually works. **The second is 10 minutes and stops brands sending dead links today** — recommend
doing that first regardless of which fix lands.

### 9.4 `PR-38` — CTA attribution on the legacy block path

Builder-path pages can carry attribution in the CTA href, because tokens substitute into literal
HTML — done live on the Trailhead page:
`?code={{coupon.code}}&utm_source={{channel}}&utm_medium=creator`.

The **legacy typed-block path cannot**: `LandingService.renderBlock` case `productCta`
(`LandingService.java:400`) builds `href` from `coupon.landingUrl` verbatim, appending nothing. Any
page still using typed blocks therefore sends shoppers to the merchant with **no code and no
channel**, and the sale cannot be attributed.

**Size: 0.5d** — append the same params in `renderBlock`, plus a test asserting the rendered href
carries code and channel.

**Caveat:** the link being correct is only half. Per the open Shopify blockers, the
unauthenticated order webhook and non-idempotent ingestion are unresolved — attribution params
arriving does not mean a sale gets attributed.

---

## Appendix A — Old → new ID crosswalk

| Old | New | Item |
|---|---|---|
| M0.1 | `PR-27b` | Platform app registrations |
| M0.2 | `PR-33` | Product analytics (port only; no storage) |
| M0.3 | `IN-02`..`IN-05` | Deploy the application |
| M0.4 / M0.5 | `PR-30` | Provider flags, simulator gate |
| M1.1–1.5 | `PR-30` | Demo-credibility set |
| M2.1 / M2.2 | `PR-20` | Checkout, portal, webhooks |
| M2.3 | `PR-21` | Plan entitlements |
| M2.4 | `PR-01` | Pricing page |
| — | `PR-04` | Stripe cutover (config) |
| M3.1 | `PR-22` | Credential encryption |
| M3.2–3.5 | `PR-10` | Shopify provider (deferred) |
| M4.1–4.4 | `PR-02` | Activation |
| M5.1 | `PR-34` | Real hosting target (code done, deploy outstanding) |
| M5.2/5.3/5.5 | `PR-03` | Wildcard cert, subdomain routing, asset serving |
| M5.4 | `PR-23` | Real DNS verification |
| M5.6 | `PR-24` | Expiry warnings |
| M6.1–6.3, 6.5 | `PR-27` | Metrics infrastructure |
| M6.4 | `PR-27b` | IG/TikTok adapters (Meta-gated) |
| M6.6 | `PR-35` | Tiered refresh scheduler |
| M7.1–7.5 | `PR-11` | Custom domains (deferred) |
| M8.1/8.2/8.4 | `PR-12` | Agency depth (deferred) |
| M8.3 | `PR-28` | Payout idempotency |
| U1 | `PR-25` | Creator record page |
| U2 | `PR-13` | Pagination (deferred) |
| U3/U5/U6/U7 | `PR-14` | UI depth (deferred) |
| U4 | `PR-26` | Metrics provenance |
| LPB A–E, G, H | `PR-36` | Landing-page builder |
| LPB F | `PR-15` | Social publishing (declined) |
| DDD 0–6 | `PR-37` | Architecture migration (complete) |
| GAPS Tier 1 #1/#2 | `PR-20` / `IN-02` | Billing; deploy |
| EXEC §1 "the tax" | `PR-16` | Duplication cleanup (deferred) |

## Appendix B — Archived documents

Moved to `docs/archive/roadmaps/`, each with a header pointing here:
`EXECUTION-ROADMAP.md`, `STRATEGIC-ROADMAP.md`, `PENDING-WORK-ROADMAP.md`,
`UI-OPPORTUNITIES-ROADMAP.md`, `PRODUCT-GAPS.md`, `roadmap.md`.

**Kept in place, deliberately** — these are architecture and feature records, not schedules:

- `docs/ddd-roadmap.md` — the six-phase migration record
- `docs/landing-page-builder-roadmap.md` — Phases A–H; only F outstanding
- `infrastructure/prod/README.md` — the production architecture argument §7 builds on
- `docs/infrastructure/hosting-topology-decision.md` — the SNI/custom-domain decision
- `infrastructure/COMPOSE-MIGRATION.md` — why ECS was abandoned

**Nothing was deleted.** The reasoning in these documents is the most valuable prose in the repo;
what changed is that they no longer carry status.

### A naming hazard worth knowing about

This document is `MASTER-ROADMAP.md`, not `ROADMAP.md`, and the reason is not stylistic. The repo
already contained `roadmap.md` (the MVP build order). **Windows and macOS filesystems are
case-insensitive**, so creating `ROADMAP.md` at the root silently *overwrote* `roadmap.md` rather
than creating a second file — the original was recovered from git, but only because it was committed.

Git itself is case-*sensitive*, so this class of mistake is invisible in a diff on Linux CI and
destructive on a developer's laptop. When adding a root-level document, check for an existing file
whose name differs only by case.
