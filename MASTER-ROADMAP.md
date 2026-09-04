
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
| `OP-06` | SES domain verification + sandbox exit | you | **HALF CLEARED — re-checked against AWS 2026-09-02.** **Domain verification is DONE:** `tejdux.com` reports `VerifiedForSendingStatus: true` with `DkimAttributes.Status: SUCCESS` and 3 tokens, production already runs `WEBE_EMAIL_PROVIDER: ses` (not the `log` default), and a real send from `no-reply@tejdux.com` succeeded — message id returned, `EnforcementStatus: HEALTHY`. **The SANDBOX has NOT been exited:** `ProductionAccessEnabled: false`, quota 200/day at 1/sec. Proven rather than inferred — a send to an unverified gmail address was rejected with `MessageRejected: Email address is not verified`. **What that means in practice:** every email feature works today for VERIFIED recipients and silently fails for everyone else, which is the worse half — a signup by a real customer gets no welcome mail and nothing errors on their side. So this still blocks `PR-02`'s welcome email and both agents for real users, but it is now one AWS support request rather than DNS work. **Also open:** `inbox.tejdux.com` is `verified: false` / `dkim: FAILED` — unused by the send path, so it costs nothing today, but it should be finished or deleted rather than left failing. **DENIED 2026-09-01, case `178750875200560`** — and the likely reason is visible in the account: `get-account` returns `Details` with `MailType`, `WebsiteURL` and `ContactLanguage` set but **NO `UseCaseDescription` field at all**. The request was submitted without the one free-text answer the review actually reads, so the reviewer had nothing to evaluate and the boilerplate denial follows. **The account's own signals are clean**, which is what makes that the plausible cause rather than reputation: `EnforcementStatus: HEALTHY`, 42 sends / 1 bounce / 0 complaints over 30 days, 0 suppressed destinations, and the `influencrm-prod` configuration set live and tracking `BOUNCE`/`COMPLAINT`/`REJECT`/`RENDERING_FAILURE`. **Resubmit with the description filled in** (`docs/ses-setup.md` §4 has the wording) and name the three things the review looks for, all of which are true and verifiable: transactional only, recipients consented at signup with an immutable evidence snapshot (`PR-31`/`PR-36`), and bounces monitored through that configuration set. **Fix two things first:** `WebsiteURL` is `https://www.tejdux.com` while the product and privacy policy live at the apex, and `inbox.tejdux.com` sits in the account `verified: false` / `dkim: FAILED`. **Attempted 2026-09-02 and the API refuses:** `put-account-details` returns `ConflictException` while a DENIED review case is open, so the resubmission CANNOT be made by CLI — it has to go through the console or a support case that references `178750875200560`. Account state was left unchanged by the attempt. **Also corrected:** `inbox.tejdux.com` is NOT a loose end to delete — it is the GDPR deletion-request intake (`PR-37`), Terraform-managed in `deletion-intake.tf`, with a live MX to `inbound-smtp.us-east-1.amazonaws.com` and the active `influencrm-prod-inbound` rule set. Its `dkim: FAILED` is cosmetic: all three DKIM CNAMEs resolve correctly to `dkim.amazonses.com`, and DKIM signs OUTBOUND mail while this domain only receives. Deleting it to tidy a flag would have broken a legal obligation. The drafted use-case text, with every figure verified against the account, is in `docs/ses-setup.md` §4. All four legal URLs cited in it return 200. See `docs/ses-setup.md` |
| `PR-27b` | Meta app review (Instagram) | Meta | ~2 days. Deliberately **not** on the critical path. **Request the full permission set — including `instagram_content_publish` — in the *initial* submission**: the dossier's own guidance is that a second review round later costs another 2–4 weeks, and reviewers do not object to a coherent product asking for a coherent set. Open items are a category change and business verification |
| `PR-10b` | Shopify Partner app | you | Only needed when `PR-10` starts |
| `PR-27c` | **TikTok sandbox registration** | you | Added 2026-08-27. Arrives in hours, not weeks. Half a day to register and determine **empirically** which endpoints are reachable unaudited: `direct_post` (public) needs audit, but **`share/upload` delivers the asset and caption into the creator's drafts**, where they tap Post. That is not the same as useless SELF_ONLY posting — it removes the two worst mobile steps (download 8MB on mobile data, re-upload). Highest-value seam in the social half, and all three candidate designs collapsed it into a binary |

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

### Stage 1 — Take money (~10d) + the creator handoff (~25d, see §10.4 before committing)

*Gate: can a stranger pay us?*

**Payouts:** `§11` adds `PR-47`..`PR-56` and `OP-21` (payments and creator onboarding). Only `OP-21` (0.5d)
and `PR-47` (5d) belong before the agency conversations — **read §11.5 before scheduling the other ~29 days.**

`OP-18` and `PR-40`..`PR-44` are the creator-handoff plan, detailed in §10. They are listed here because `OP-18` repairs defects that are live in production **now** and should not wait behind a stage boundary. The rest of that block is sequenced but **not yet committed** — §10.4 records a real argument for delivering the same user-visible outcome in ~6 days instead of ~25, and that decision is open.

| ID | Item | Size | Note |
|---|---|---|---|
| ~~`PR-01`~~ | Public pricing page — **written 2026-08-21**, `docs/legal/pricing.html`. Prices and limits cross-checked against the live Stripe catalogue and `PlanPolicy`. **Deployed and live** — verified 2026-08-27: `https://tejdux.com/pricing/` and `https://www.tejdux.com/pricing/` both return 200 from `s3://tejdux-legal-static/pricing/index.html` via distribution `ESJ9LTY0C74G0`, and the served bytes are identical to `docs/legal/pricing.html` in the repo. The upload happened 2026-08-23; this row went stale rather than the work being outstanding. Prices on the page ($49/$149 monthly, $470/$1,430 yearly, free = 1 brand / 25 creators / 1 seat) match Decision 1; the cross-check against the live Stripe catalogue is the one recorded on 2026-08-21, not re-run here — there is no Stripe key on the deploy machine | 0.5 | Every competitor is demo-gated; publishing a price is free differentiation |
| ~~`PR-35`~~ | **AI campaign-page generation** *(now SHIPPED, not PARTIAL — see the status correction below)* — goal-first authoring: brief → 2–3 drafts → compare → edit in the builder. **The AI path is LIVE — this row's "switched off" status was stale and is corrected here (2026-08-31).** Screens 1-6 are all built — brief → drafts → compare → section-level rewrite → regenerate-one-variant → schedule publish. `web-experience.landing.generation.provider` still defaults to `template` **in `application.properties`**, but production sets `page_generation_provider = "anthropic"` (`infrastructure/test/terraform/prod.tfvars:61`), so the live default is the model. **The credit blocker is cleared.** The key is issued and wired — stored as `influencrm-prod/page-generation-api-key`, injected as `WEBE_PAGE_GENERATION_API_KEY`, `page_generation_provider` deployed 2026-08-24 and **cut over to `anthropic`**. The corroborating evidence is that generation is now METERED: `9f09e74` (2026-08-31) adds `AiGenerationAllowance` + `V48__ai_generation_usage.sql`, capping billed calls at 20/month free, 500 pro, uncapped agency — nobody meters a generator that is not running. `V48:65` counts `where generator <> 'template'`, so a fallback draft the user did not choose never consumes allowance. Drafts convert to the existing `blocks` shape, and `ContentPage.sectionsFromVariant` additionally maps them onto the curated section vocabulary so a draft opens in the section editor rather than as an opaque document. ~~**Two limits of the live path, both real and both cheap — see `PR-58`:** the model's tool schema is pinned to the five LEGACY block names while the editor has eight types, so `proof` and `creator` are UNREACHABLE by AI;~~ **The first limit was REMOVED by `PR-58` (shipped 2026-09-01) and this paragraph went stale — corrected 2026-09-04.** The enum now carries seven values including `proof` and `creator` (`AnthropicPageGenerator.java:374`, verified on disk); the cited line `:337` drifted and is now unrelated code. Read the row above this one before believing a limitation stated here. The remaining limit is real: the model never picks a `variant`, so every generated page lands on `variants[0]` and three "structurally varied" drafts render near-identically. **Verified by:** `content/application/PageGenerationPort.java`, `CampaignPageGenerationService.java`, `content/infrastructure/TemplatePageGenerator.java`, `AnthropicPageGenerator.java`, `content/api/CampaignPageGenerationController.java`, `CampaignPageGenerationTest` (10 tests), `CampaignPageGenerationControllerTest` (5 tests, the BFF's first `@WebMvcTest`), `CampaignPageEditingTest` (7), `BriefEnricherTest` (4), `ScheduledPublishTest` (8), `content/application/BriefEnricher.java`, `ScheduledPublishScheduler.java`, `schema/flyway/V41__scheduled_landing_publish.sql`, `shell/contentRemoteCopies.test.mjs`, `identity/application/AiGenerationAllowance.java`, `schema/flyway/V48__ai_generation_usage.sql`, `infrastructure/test/terraform/prod.tfvars:61`. **Design spec:** `docs/AI-Campaign-Page-Authoring-Design-Spec.md` | 6 | **Two unconverged AI paths.** `agent_service/` (Python) already had `POST /content/draft` with a `kind=landing` branch, on **OpenAI** (`gpt-4.1-mini`) — it backs the brief form's existing "Draft with AI" button and returns a single `{hero, body, cta}`, which cannot express the compare-variants flow. This row's generator is a **second** integration, in Java, on Anthropic. Kept separate deliberately (decided 2026-08-23): converging is a new `PageGenerationPort` `@Component` calling a variant-shaped `agent_service` endpoint, not a rewrite. Cost until then: two prompt locations, two vendors, two API keys. **Note for anyone auditing AI usage: grepping the Java tree and `*.properties`/`*.yml` finds nothing for `agent_service` — it is Python, and is how this duplication arose.** |
| ~~`PR-39`~~ | **Curated section editor** — ✅ **COMPLETE 2026-09-01, GrapesJS deleted.** — replace GrapesJS with fixed, professionally-designed section types the brand fills in, plus 8 built-in templates and brand-saved custom templates. **Piece A landed 2026-08-24** (schema + renderer): `schema/flyway/V42__landing_sections.sql` adds `sections jsonb` to `content.landing_templates` AND `landing_template_versions`, and `LandingService.renderSections` renders typed sections server-side with nothing brand-authored reaching the page as markup — 14 tests in `InfluencerWebExperience/src/test/java/com/influencer/webe/content/application/LandingSectionRenderTest.java` pin the `sections` -> `document` -> `blocks` precedence and the escaping. The rendered page uses the approved design (Swiss/minimal, Newsreader/Inter, terracotta) and every colour pair was contrast-checked against WCAG AA on all three section grounds — two tokens moved as a result. Also landed alongside: `LandingStageService.requirePublishable` now counts `sections` as content (a section-only page was previously refused as empty), a `GET /api/landing-pages/{id}/publish-readiness` advisory that warns when a page has no coupon and therefore no sales attribution, and UTM tagging on outbound CTAs so a coupon-less page still attributes VISITS to campaign and creator. Fingerprinting/attribute-matching was considered and rejected: personal data on an anonymous page with no consent gate, and guesswork that mis-attributes revenue. **Pieces B, C and D landed 2026-08-24.** B: `InfluencerUI/src/shell/sectionTypes.js` is the single source of truth for the 8 types and their 13 variants, all styled and render-verified at 390/1280. C: `InfluencerUI/src/components/SectionEditor.jsx` — canvas previewing the REAL server renderer in an iframe, one context panel, up/down reorder (not drag-and-drop: keyboard- and touch-reachable), four preview widths incl. Laptop 1024, panel drops below the canvas under 900px, token highlighting that flags unrecognised tokens, and per-section AI rewrite mapped onto the existing `/api/campaign-pages/sections/rewrite`. D: 8 built-in templates in `shell/pageTemplates.js` (6 mapped to the brief form's campaign types + Photo-led/Story-led), switching keeps the words and warns BEFORE discarding; `V43__brand_page_templates.sql` + `/api/brand-page-templates` for saved templates, metered as `PlanPolicy.Resource.SAVED_TEMPLATE` (free 2 / pro 20 / agency unlimited), with creator identity stripped server-side. The flag `web-experience.landing.editor` is now REAL: read by `LandingController.editor` and honoured by the UI, defaulting to `builder`. Tests: 497 BFF, 48 DAO, 266 UI. **Switched ON in production 2026-08-25**: `landing_editor = "sections"` in `prod.tfvars` (gitignored, so the value lives only on the deploy machine — `example.tfvars` documents it), applied on `v1.0.33` and rolled. Rollback is that line back to `builder` plus an instance refresh; the GrapesJS document is never cleared, so a page authored under `sections` reverts intact. **~~Still PARTIAL~~ COMPLETE 2026-09-01:** GrapesJS is deleted from the bundle — 951 KB, and the ContentUI's largest chunk is now 200 KB. The `web-experience.landing.editor` flag, its `GET /api/landing-templates/editor` endpoint, the terraform variable and the compose wiring went with it: the flag existed so `sections` could be reverted to the builder, and with the builder gone a config endpoint with one possible answer reads like a control that does nothing. Removed from BOTH UI trees, and `contentRemoteCopies.test.mjs` now asserts the endpoint is ABSENT so a reintroduced call fails there rather than 404-ing in production. The public renderer is untouched — precedence stays `sections` → `document` → `blocks`, so a page still holding either keeps serving; what is gone is editing one in the old canvas, which is safe because the founder confirmed there are no live users. Originally:  which waits on a clean release with no rollback — that removal is the 951 KB win and is the reward, not the milestone. Note the one published page (`c-3e3c2c38`) still renders from `blocks` and is untouched; there is deliberately no HTML-to-section parser, so it keeps serving until someone rebuilds it. **Switching it on surfaced a pre-existing bug worth recording**: the landing preview had never worked in any editor — `previewLandingTemplate` called `buildHeaders` as an undeclared global (never exported from `api/core.js`, never imported into `api/content.js`), so every call threw a ReferenceError into the caller's `catch` and did nothing. The old builder's Preview button was broken the same way; the section editor only made it visible by previewing continuously instead of on a button. Fixed in `8ad1e35`, verified live: the canvas renders the real server HTML with the creator's coupon substituted. The flag `web-experience.landing.editor` is NOT yet read anywhere — precedence alone decides the render path, which is safe only because nothing writes `sections` today; wiring the flag belongs with piece C. The founder's report was that the builder "looks cheap", and the evaluation found that is a consequence of a free-form box canvas rather than of its theme: page quality becomes a function of the user's design skill, and the user is a brand manager with none. Three facts decided it, all verified in code — the stack is already section-shaped (`PageGenerationPort.Section`, `rewriteSection`, `renderLegacyBlocks`), the AI's typed sections are currently flattened to an HTML blob on entry so `rewriteSection` has nothing to point at, and `LandingDocumentSanitizer`'s own header records that the typed-block renderer was "safe by construction" and a visual builder "inverts that". Also deletes **951 KB** (261 KB gzipped), the largest asset in the app, vendored twice. Ships behind `web-experience.landing.editor` (`sections` or `builder`, default `builder`) so rollback is a variable flip; GrapesJS is removed only after a clean release on `sections`. **Evaluation:** `docs/Landing-Editor-Framework-Evaluation.md` **Plan:** `docs/Curated-Section-Editor-Implementation-Plan.md` | 16-23 | Prototypes approved 2026-08-24 (page render, editor interaction, template gallery). Migration is additive — `sections` → `document` → `blocks` precedence rewrites no rows, so the live published page is never at risk; there is deliberately **no HTML-to-section parser**, since that is an unbounded heuristic run against customer pages to save minutes of hand-rebuilding one. Saved templates need their own table: `uq_landing_templates_campaign` enforces one page per campaign and V24 records that as deliberate, so reusing that table would weaken the invariant for every real page. **The honest counter-argument is in the plan's §6** — this is a rewrite on an aesthetic complaint, and restyling GrapesJS in 1-2 days is the cheaper option that raises the floor without changing the ceiling |
| `PR-04` | Stripe cutover — products, keys, webhook secret, `provider=stripe` | 1 | Config, not code |
| ~~`OP-23`~~ | **`BriefEnricher` reads a column that has never existed** — ✅ **SHIPPED 2026-09-01.** — `BriefEnricher.java:96` fills `brandTone` from `brand.tone`. There is no `tone` column: `V11__accounts_brands_memberships.sql:75-85` is `id, account_id, name, status, custom_attributes, created_at, updated_at`, `grep -rn "tone" schema/` returns NOTHING across every migration, and `dao/identity/domain/Brand.java` mirrors exactly those fields. So the line always no-ops, silently, and its own comment ("a better default than asking every campaign to restate it") describes behaviour that has never once occurred — brand tone reaches the model ONLY when a user retypes it into the form for every campaign, which is the exact thing the code believes it prevents. Persist tone into the `custom_attributes jsonb` that already exists rather than adding a column, and the existing enrichment path starts working. **Shipped:** `BriefEnricher.brandTone` parses `custom_attributes.tone`; malformed JSON costs the tone, not the generation, because the bag is user-writable and bad input is ordinary rather than exceptional. **Verified by:** five tests in `BriefEnricherTest` naming the real source — a test is the only thing that would have caught this, and reverting to `brand.tone` fails one of them. Found 2026-08-31 by reading the DDL, not the code — nothing in the stack logs or fails when an enrichment field is absent, which is why it survived. | 0.5 | Half a day to make a shipped AI path work as documented. Do it with `PR-58` |
| ~~`PR-57`~~ | **Surface the landing analytics that are already being collected** — ✅ **SHIPPED 2026-09-01.** `landing_page_views` records a row per public render (campaign code, referrer, user agent, timestamp) and `GET /api/landing-page-views` serves them — and **no `.js`/`.jsx` file in the repo calls that endpoint.** Zero matches, verified 2026-08-31. The `performance_tracking` stage is reachable with nothing behind it, so a brand who publishes a page has no way to learn whether anyone came. Build the panel over the endpoint that exists; no new capture, no new schema. **Deliberately NOT adding fields while doing it** — the table has no IP, geo, session or device parse, and that restraint is what lets a public page stay anonymous with no consent gate (the same reasoning that rejected fingerprinting in `PR-39`). Count what is already counted. **Shipped:** `content/application/LandingAnalyticsService.java` + `GET /api/landing-pages/analytics`, and `packages/ui/src/LandingAnalytics.jsx` mounted on a published page in both UI trees. **A new endpoint rather than wiring the UI to the old one** — `/api/landing-page-views` returns every raw row, unpaginated and undated, so a page doing well would answer with tens of thousands of records for the browser to add up; the raw route is left alone as a debugging tool. **The coupons are the join key and the tenancy boundary**: a view records a `campaign_code_id`, never a template id, so coupons are resolved per brand and per campaign first and a view outside that set is dropped rather than counted — the view log is one shared table across every brand. **Verified by:** `LandingAnalyticsServiceTest` (7 tests, pinning what the counts must REFUSE to do: count another campaign's coupon, count outside the window, or report zero for a page with no codes — "nothing to show" and "nobody came" are different claims), 593 BFF tests, 250 UI tests. `contentRemoteCopies.test.mjs` caught the `ContentPage` edit landing in only one tree, which is exactly what it exists for. | 1-2 | The measurement that tells you whether ANY of the AI authoring is working. §2.1 says the gap is commercial, not technical — this is the row that produces evidence either way |
| ~~`PR-58`~~ | **Let the generator speak the curated vocabulary** — ✅ **SHIPPED 2026-09-01.** Widen the tool-schema enum (`AnthropicPageGenerator.java:337`) from the five legacy block names to the editor's eight types, have the model emit a `variant`, and extend `ContentPage.sectionsFromVariant` to carry both across. Unblocks `proof` and `creator`, which AI cannot currently produce at all (see `PR-35`). **Reweight `ConversionScore` in the same change, or the badge starts lying:** the 0-100 figure is computed structurally in Java for both generators — points for CTA/coupon/proof/creator present — so widening the vocabulary mechanically raises every score without a single line of copy improving. **The curated-editor invariant holds unchanged:** the model gains section TYPES and designed VARIANTS, never a colour, font, size or position — `sectionTypes.js:10-13` and §10.3 both forbid that, and this row must not be read as reopening it. | 2 | The highest ratio in this block: a prompt-and-schema change that touches no stage machine, no `turn` column and no publish path, and makes the live AI reach the two best sections on the page |
| ~~`PR-59`~~ | **SEO metadata and OpenGraph on the public page** — ✅ **SHIPPED 2026-09-01.** All three render paths emit a `<title>` (the INTERNAL template name) and a viewport tag, and nothing else — no meta description, no `og:`, no Twitter card, no canonical, verified by grep 2026-08-31. A landing page whose entire purpose is to be shared from a creator's post currently previews as a bare URL on every platform that matters. Generate the metadata from the sections the model already wrote, which §10.3 correctly calls the best AI input in the product because it is already structured and already written. The OG **image** needs object storage and is the one part gated on `PR-45`; the text half is not, and ships first. | 2 | Cheap, and it fixes the page at the exact moment it does its job — the share |
| ~~`PR-60`~~ | **A front door for custom domains** — ✅ **SHIPPED 2026-09-02.** `BrandDomainService` (307 lines), `BrandDomainController`, `DnsDomainRegistrar` and the `brand_domains` schema all exist and are tested — `PR-23` is genuinely SHIPPED — but **there is no UI anywhere**: grepping both UI trees for `brand-domains`/`BrandDomain`/`customDomain` returns nothing, so no brand can reach any of it. Two honest limits to name rather than discover later: SSL issuance is an explicit stub deferred to ACME (`DnsDomainRegistrar.java:148`), and the public serving path never consults `brand_domains`, so connecting a domain today verifies and then serves nothing. Scope this row as the UI plus the serving lookup; ACME stays `PR-11`. **Shipped as the UI only, and the two limits are now stated ON SCREEN rather than discovered:** `packages/ui/src/CustomDomains.jsx` in Settings shows the TXT and CNAME records copyably (generated server-side, never stored, so they cannot drift from what verification checks), and a verified domain says plainly that ownership is confirmed while serving is not switched on — because a brand pointing DNS at us and finding nothing loads is the worse outcome. **The serving lookup is NOT done** and is the honest remainder: the public path still does not consult `brand_domains`. ACME remains `PR-11`. **Deployed in `v1.0.53`.** | 2-3 | Finishing something already paid for. Sequence it AFTER `PR-57` — a domain nobody can measure is worth less than measurement on the domain that already works |
| ~~`PR-61`~~ | **Lead capture as a ninth section type** — ✅ **SHIPPED 2026-09-02.** — a real form, its endpoint, storage, and a consent record. There is no form anywhere in the stack today, by decision rather than omission: the `signup` type is named "Closing call" precisely so the picker does not promise a field that is not there, `LandingService` refuses to render one ("a form here would collect personal data on an anonymous public page with nowhere to POST it"), and `LandingDocumentSanitizer` strips `<form>` even from the builder path. Those three guards are consistent and correct; this row removes them deliberately and replaces them with the consent machinery `PR-31`/`PR-36` already built. **Note the tension:** `sectionTypes.js` says "there is deliberately no way to author a ninth" — that comment guards against LAYOUT proliferation, not new field-bearing types, but it must be amended in the same commit rather than quietly contradicted — **it was**: that sentence guarded against LAYOUT proliferation, and a field-bearing type with no variants does not cross it. **Shipped:** `V51__lead_capture.sql`, `content.page_leads`, `PageLeadService`, a public `POST /api/public/landing/{slug}/leads`, and a `contact` section type ADDED rather than substituted for `signup` — a page that silently grew an email box where a Shop button was would surprise every brand already using it. **The third guard stays:** `LandingDocumentSanitizer` still strips `<form>` from brand-authored HTML, because a pasted form has no backing. **Consent before the row, and a refusal writes nothing at all** — not even a record that someone declined, which is the ordering an erasure request depends on. Rate limited per PAGE, and it REFUSES past the limit rather than degrading, because there is no lesser version of storing somebody's email. **Verified by:** `PageLeadServiceTest` (7 tests) and V51 applied against real Postgres — re-runs, allows a duplicate address, and the case-insensitive index finds a row typed in a different case, which is what an erasure request will do. **Live-verified in `v1.0.53`:** the public endpoint returns 400 without consent and 201 with it, and the form renders on a real published page with its consent box. | 4-5 | The stated competitive advantage (spec §8), and the only landing item that is a genuinely new subsystem. **Hold until a customer asks** — §10.4's argument applies with full force to a week spent on a feature nobody has requested |
| ~~`OP-24`~~ | **Three landing-page facts worth recording before someone builds on them** — ✅ **SHIPPED 2026-09-01.** (a) **`theme` is a dead column** — stored, version-snapshotted and restored on rollback (`LandingService.java:80,1090,1147`) and read by NO renderer; a future theme switcher built on it would appear to save and never render. (b) **The checked-in editor default disagrees with production** — `application.properties` defaults `web-experience.landing.editor` to `builder` while prod runs `sections` from a GITIGNORED `prod.tfvars`; a fresh environment, or a tfvars that goes missing, silently boots the old GrapesJS editor. That is the same failure mode as the user's standing infra rule about missing tfvars, and it is one line to align. (c) **The second AI path is LIVE and is NOT metered** — `agent_service/app.py:266` serves `POST /content/draft` (`kind=landing`) on OpenAI `gpt-4.1-mini`, returning a flat `{hero, body, cta}`. It is reachable and in use: `CampaignBriefsController.java:43` proxies it via `AgentMappingClient`, and it backs the brief form's "Draft with AI" button (`two-ai-paths-not-converged`). **Do not delete it** — an earlier reading of this row called it dead, which was wrong. The real finding is that `PR-35`'s new `AiGenerationAllowance` caps the Java/Anthropic path only, so the OpenAI path remains an uncapped billed call on the same account — the exact loop-and-spend hole `9f09e74` was written to close, still open on the other vendor. **Superseded in detail by `OP-25`, `OP-26` and `PR-62` (2026-08-31)** — and note `OP-27`: measured 2026-09-01, that path is **not spending anything**, because the agent has no usable key, which found the exposure is wider than metering: the path has four call sites, not one, and the uncapped one that matters (`classify`) runs per CREATOR while one of the others is reachable without authentication. | 0.5 | Documentation and one deletion. Each of these is a trap someone walks into exactly once, expensively |
| ~~`OP-25`~~ | **An unauthenticated endpoint spends AI money, and nothing throttles it** — ✅ **SHIPPED 2026-08-31.** — `CreatorOnboardingController.publicSignup` (`:93`, `POST /api/public/landing/{slug}/signup`) takes no `Authorization` and is reachable by anyone on the internet BY DESIGN — it backs the creator sign-up form on a published landing page. It calls `captureLead`, which calls `classify`, which is a billed OpenAI call (`OPENAI_API_KEY` is populated in production — confirmed by the founder 2026-08-31). So a stranger can bill the platform, repeatedly, by POSTing handles at any published page, and `AiGenerationAllowance` does not see it: `9f09e74` caps the account's OWN users on the Anthropic path, and this is neither. The endpoint is otherwise carefully written — it rebuilds the payload rather than trusting it, requires consent before the lead exists, and 404s an unpublished page — so the gap is specifically the missing throttle in front of an expensive call, not the endpoint's design. **Reuse `LoginAttemptLimiter` rather than inventing a mechanism**: it already solves the identical shape ("an unthrottled endpoint in front of deliberately expensive work is a denial-of-service amplifier"), and its in-memory/one-instance caveat and its keyed-on-the-target reasoning both transfer intact. Key on the slug, and prefer the heuristic classifier over refusal when the limit trips — a lead captured with a keyword niche is worth far more than a lead refused. **Shipped as `creator/application/PublicSignupRateLimiter.java`** (30 enrichments per page per hour, `web-experience.creators.public-signup-rate-limit-enabled`, default on). **`LoginAttemptLimiter` was NOT reused after reading it** — it counts FAILURES and locks an address out, which never trips here because every public submission is a legitimate success that spends money; the shape needed is a ceiling on the RATE of successes, so it is a separate class rather than the login one bent out of shape. The refused path sets `prefer_heuristic` on `/creators/classify`, which short-circuits BEFORE the model call rather than discarding its answer — discarding would already have paid for it. **Verified by:** `PublicSignupRateLimiterTest` (6 tests, incl. that the ceiling is per page so one abused page cannot silence another brand) and `tests/test_classify_prefer_heuristic.py` (4 tests, incl. one that fails loudly if the model is called at all). **Deployed 2026-09-01** in `v1.0.48`, and **verified END TO END against production the same day**, once `OP-27` made the model reachable — before that fix every classification was `heuristic` and the ceiling could not be observed switching off something already off. `tests/e2e/probe-public-signup-rate-limit.mjs` builds its own throwaway brand and page rather than pointing 33 submissions at a customer's live page (each creates a lead, an email and a consent row): submissions inside the ceiling come back `source: "llm"`, all three past it come back `heuristic`, and **all 33 were accepted** — the ceiling drops the model's opinion, never the lead. Probe pages unpublished afterwards and confirmed 404. | 1 | The only AI spend in the product reachable by someone who is not a customer. Independent of every capping decision below |
| ~~`OP-28`~~ | **The dashboard hid this evening's sales** — ✅ **FIXED AND LIVE-VERIFIED 2026-09-01.** Found by the brand-owner E2E journey failing against a live deploy, and it is a real product bug rather than a test artifact: the run asserted every order returned `outcome="attributed"`, then the dashboard rendered *"No sales attributed in the last 30 days"*. Rows are stamped in **UTC**; `shell/dateRange.rangeToParams` computed its bounds in **local** time. West of Greenwich the UTC date runs AHEAD after early evening, so an order placed at 20:23 in New York is stored as tomorrow and falls outside a `to` of local-today. **Measured, not reasoned about:** for the same order the live API returned 0 for `to=2026-09-01` and 1 for `to=2026-09-02`. The upper bound now reaches the UTC day when UTC is ahead; `from` deliberately does not, because widening the lower bound would add a day of genuinely older data to every window — the asymmetry is the point. **Why it hid:** it reproduces only in the evening, only west of UTC, and only when the newest data is today — exactly when a brand checks whether a launch worked. `toIsoDate`'s existing comment was correct that `toISOString()` is wrong for DISPLAY, and that correct reasoning is what made the bound look already handled. **Verified by:** `shell/dateRange.test.mjs` (6 tests incl. the timezone boundary), and the previously-failing journey now reporting REVENUE $450.50 / ORDERS 3 / ROI 10.00×. | 0.5 | The kind of defect a unit test cannot find and a live journey can — it needed real UTC storage, a real local clock, and data created minutes earlier |
| ~~`OP-29`~~ | **Two source-asserting tests were failing on CRLF, not on the code they guard** — ✅ **FIXED 2026-09-01, as a side effect of `OP-28`'s line-ending work.** `SignInConsentTest.signInSkipsTheConsentGate` and `DeletionWorkflowTest.publicPathsAreJustified` read a `.java` file and assert it CONTAINS a literal snippet spanning a newline — the repo's way of pinning a decision to the comment that explains it ("consent must be required for sign-up and skipped for sign-in"; "the reason the webhook is safe to expose must be written down"). With no `.gitattributes` and `core.autocrlf=true`, those files were checked out with CRLF, so a newline in the expected string never matched and both failed while the code they guard was entirely correct. Normalising the tree to LF fixed both with no change to the assertions or the source. **The lesson is about the class:** a test that asserts on source TEXT is coupled to the checkout's line endings, and on a Windows machine without `.gitattributes` that coupling is invisible — it reads as "someone edited `SecurityConfig` and the wording drifted", which is exactly what it was mistaken for earlier in this session. `.gitattributes` (commit `63c081b`) is what stops it recurring. | 0 | Cost nothing to fix and was misdiagnosed twice before the cause was found; worth the row so the next person reads it as an encoding problem rather than a drifted comment |
| ~~`OP-30`~~ | **`app.tejdux.com` rendered perfectly and could not sign in** — ✅ **FIXED AND LIVE-VERIFIED 2026-09-02.** `shell_serves_apex` makes the shell distribution answer on the apex, `www` AND `app.`; `DPS_ALLOWED_ORIGINS` listed the first two and the portal, but not `app.`. So every `/dps/session` call from that host failed preflight with **403** — the page loaded, looked entirely correct, and nobody arriving by that name could authenticate. Measured before and after: apex and www returned 200, `app.` returned 403, and all four return 200 now. **This is the THIRD instance of the same failure**, and the two comments already above that list describe the other two (`www`, then the creator portal at `PR-43`) — which is why the origin is now DERIVED from `local.apex_on_shell`, the same condition that creates the alias, rather than added by hand a fourth time. **Found by `OP-31`'s smoke check on its first run**, not by anyone using the site, and not by the check's own purpose: it was written to catch blank pages and caught a CORS misconfiguration because a browser reports both and `curl` reports neither. | 0.5 | The failure mode the file's own comments call out twice, arriving a third time — a list that must be kept in step by hand eventually is not |
| ~~`OP-31`~~ | **A UI deploy said "Done" over a page that did not run** — ✅ **SHIPPED 2026-09-02.** `deploy-ui.sh` proved the FILES were live — S3 accepted them, CloudFront was invalidated, `curl` returned 200 with a real `index.html` — and none of that says the app inside them starts. On 2026-09-01 the `PR-39` GrapesJS removal left three calls to a `setEditorMode` that no longer existed; Vite compiles a call to an undefined identifier without complaint, so the bundle built clean, uploaded clean, and the Content page threw on mount and rendered COMPLETELY BLANK in production for ~15 minutes. What eventually noticed was an E2E journey failing on a selector three steps later. `infrastructure/scripts/smoke-ui.mjs` now loads every deployed host in a browser and fails on an uncaught JS error or an empty root, wired into the end of `deploy-ui.sh` and scoped to whatever was deployed. **Non-fatal by design:** by the time it runs the files are already live, so a non-zero exit would neither undo that nor add information — it is loud instead, because the failure it catches is otherwise silent. Seconds, not the minutes the E2E suite costs. | 0.5 | The gap between "uploaded" and "works" was the one place nothing looked |
| ~~`OP-32`~~ | **Thirteen of the fourteen shell E2E suites could not sign up** — ✅ **FIXED 2026-09-02.** `PR-31` made `acceptedTerms` mandatory on `/api/auth/signup` and `/api/creator-portal/auth/signup`; the `tests/e2e_*.sh` suites were never updated, so each one got a 400, took an empty token, and then reported dozens of failures that were all the same missing field wearing different numbers. **That is the worst shape a broken test can take** — `e2e_creator_health` reported fourteen distinct failures about alerts and vetting, and not one of them was about alerts or vetting. Only `e2e_campaign_page_generation` was correct. Verified against production: both signups succeed with the field and are refused without it, and the claim endpoint one suite reported as a **500** answers **201** when called with real arguments. | 0.5 | A suite that fails for one reason in forty places trains you to stop reading it |
| ~~`OP-33`~~ | **The shell E2E suites cannot judge production, and their output invites the opposite conclusion** — ✅ **SHIPPED 2026-09-04.** Thirteen suites reach the database with `docker exec influencercrm-postgres psql`, so pointed at a deployment they compared a live API to an EMPTY LOCAL database and every such check failed, reading like a product defect. **Decision taken: refuse, not skip.** `tests/local_only_guard.sh` exits 2 with the target and the reason when the suite is aimed anywhere but localhost. Skipping the DB assertions was rejected because a suite that skips half its checks still prints a green summary, and a summary is a claim about what was verified. `E2E_ALLOW_REMOTE=1` runs the API-level checks and warns that everything else in the run is meaningless. Three suites hardcoded their target and so could not be redirected at all — now `${BFF:-…}`, which also brings them under the guard. `e2e_db_report.sh` takes no guard and says why: a report with no API target cannot be aimed wrongly. **Verified:** all 13 refuse a remote target, all 13 still run locally, escape hatch warns | 0.5 | Found by running them; recorded because the next person will read the same red output and reach for the product |
| `OP-34` | **The AI ceiling was live and counting nothing** — ✅ **SHIPPED 2026-09-04.** Found in the production logs, not by a test: every `classify` the BFF recorded came back **400** from the DAO — `"kind must be one of [rewrite, generate, regenerate]"`. `V48` defined those three, `V49` widened the CHECK constraint to six, and `AiGenerationUsageController:36` was left behind. **The database was never wrong** — it has accepted all six since V49 ran; only the hand-maintained mirror drifted. Nothing broke loudly because `AiGenerationAllowance.record()` catches and continues **by design** (losing a creator classification is worse than losing a meter reading), so the only evidence was a WARN nobody was reading — the **second** silent meter this class has shipped, different cause, same shape. The test now reads the accepted set **out of V49** rather than restating it, because a second hand-written list is a second thing to forget. **Verified:** fails against the old three-value constant, 68 DAO tests green. **Deployed v1.0.57 2026-09-04**, migrate clean, both endpoints healthy, zero `could not record a classify` since. (End-to-end proof of a counted `classify` still needs an authenticated call — the DAO is not publicly reachable, so the unit test plus the absent error is the evidence, not a live count.) | 0.5 | Live for 3 days. Free-tier enforcement was inert throughout; zero subscribers meant no billing consequence |
| ~~`OP-27`~~ | **Creator classification has been silently keyword-matching in production** — ✅ **FIXED AND LIVE-VERIFIED 2026-09-01.** — measured 2026-09-01, not inferred. Every classification returns `source: "heuristic"`: on the public sign-up path, on the AUTHENTICATED `resolve-handle` path (which consults no rate limiter), and on `/api/content/draft`, a second endpoint sharing the same `OpenAIAdvisor`. Two independent features falling back together means `is_available()` is false — the agent container has no usable `OPENAI_API_KEY` — rather than anything specific to one call site. **The wiring is correct**, which is why this survived: `openai-api-key` exists in Secrets Manager, `compose-ec2.tf:354` maps it, and the agent's compose block passes it through. The failure is the VALUE, and `compose-boot.sh.tftpl`'s `fetch_secret` handles an empty secret by logging a warning to `logger` and skipping the line, so the container starts happily with the variable simply absent and `_llm_classify` returns `None` on every call by design. Nothing errors, nothing 500s, and a brand sees a plausible niche produced by substring matching. **Two consequences worth separating.** Quality: creator vetting — niche and risk flags — is running on keywords, which the code itself calls "genuinely weaker than a model at reading intent." Cost: the uncapped-OpenAI-spend risk in `OP-24`(c) and `PR-62` is currently **theoretical**, because that path spends nothing; `PR-62` is still worth doing, but it is insurance rather than a live leak. **Fix:** confirm the secret holds a real key, then re-run `tests/e2e/probe-op25-verify-sources.mjs`, which distinguishes the two sources on stored rows. Doing so also unblocks the half of `OP-25` that could not be verified — a ceiling cannot be observed switching off a model that is already off. **Fixed:** the key was present in the gitignored `.env` and valid (checked against the OpenAI API before writing: `/v1/models` 200, `gpt-4.1-mini` replied), and NOT the exposed key the secret's description warns about — `git log --all -S` finds it in no commit and no tracked file, so that older key had already been rotated. Written with `infrastructure/scripts/set-openai-key.bat`, which guards on the account id and never echoes the value. **The reload needed its own script.** A secret's VALUE is not part of the launch template, so `refresh-test-instance.bat` correctly reported "already on the current template" and would have terminated the instance for no configuration change — 3-5 minutes and every signed-in user logged out. `compose-boot.sh.tftpl:140` had already recorded the cheaper path ("a secret rotation is picked up by `systemctl restart influencrm-secrets influencrm` with no redeploy"), now `infrastructure/scripts/reload-secrets.bat`. **Verified live, not assumed:** the env file went from 22 secrets to 23 with `OPENAI_API_KEY` present and no fetch warnings, and `resolve-handle` now returns `source: "llm"` with real niches on both platforms — `fitness`, `gaming`, `lifestyle` — where every call returned `heuristic` before. One trap worth recording: `grep -c` against `platform.env` immediately after the restart returned 0 because the file is truncated and rewritten in place, so a check run too early reads an empty file and looks exactly like a failed fetch. | 0.5 | Found by trying to verify `OP-25` and failing honestly. The alarm this needs is `OP-02`-shaped: a fallback that never errors is invisible until someone measures it |
| ~~`OP-26`~~ | **The one import endpoint with no ownership check** — ✅ **SHIPPED 2026-08-31.** — `ImportBatchesController.generateAgentColumnMapping` (`:70`) takes an id and fetches `/import-batches/{id}/columns` with **no `Authorization` parameter and no `requireOwnedImportBatch` call**. Every sibling in the file has both, and `columns` (`:59`) carries the comment that says why: an import batch's headers "describe the uploaded file, so this needs the same ownership check as the batch itself rather than being treated as harmless metadata." This endpoint is the one that does not follow it. Two consequences, and the first is the serious one: a caller passing another tenant's batch id gets **that tenant's column headers back in the response** (an IDOR on a surface that is, by definition, a customer's own spreadsheet), and each call also spends OpenAI budget. Fixed by adding `requireOwnedImportBatch` (the helper already existed and throws 403 on mismatch). **Verified by:** `campaign/api/ImportBatchOwnershipTest.java` (4 tests) — which asserts not only the 403 but that the refused path never reads the columns and never calls the agent, because a fix that returned 403 *after* paying for the mapping would look correct and still leak nothing while billing us. Confirmed the test catches the original defect by reverting the fix: 3 of 4 fail without it. **Deployed and live-verified 2026-09-01** in `v1.0.48`: the endpoint returns 401 to an unauthenticated caller where it previously served. | 0.5 | A tenancy leak, not a cost question. Fix it regardless of what is decided about metering |
| ~~`PR-62`~~ | **Meter creator classification, and deliberately NOT the spreadsheet import** — ✅ **SHIPPED 2026-09-01.** Extend `AiGenerationAllowance` to the OpenAI path with new `kind` values rather than building a second counter — `V48` already records a row per call precisely so the kinds can be told apart, and a parallel mechanism for the Python path is the mistake that schema was written to avoid. **Cap `classify`:** it runs once per CREATOR (`CreatorOnboardingService.java:97` on preview, `:139` on save) with no caching, so previewing a roster of fifty while deciding bills over a hundred uncounted calls — the per-row multiplier `9f09e74` exists to stop. **Do NOT count the spreadsheet import:** `AgentMappingClient.mapColumns` sends only the column HEADERS (`ImportBatchesController:82`), so a 10,000-row roster and a 10-row one cost exactly one call each — bounded by the number of imports, never by their size. Record it and exclude it from the count, exactly as `V48:65` already does for template fallbacks. **The reason this matters is the number, not the pennies:** free tier is 20/month and `9f09e74` chose 20 as "far more than authoring a campaign in good faith takes" — a budget shared with imports and classifications no longer means twenty drafts, and the cap would bite during the activation this feature exists to produce. Cap `content/draft` alongside the Anthropic drafts: same user action, different vendor. **Shipped:** `V49__ai_generation_openai_kinds.sql` widens the `kind` CHECK to `classify`/`brief_draft`/`column_mapping`, `AiGenerationEventRepository.countBilledSince` excludes `column_mapping`, and all three call sites record from the RESULT rather than the request — a handle that did not resolve never reached the classifier, and a heuristic fallback cost nothing, so charging for either would meter work that was never billed. `resolve-handle` is metered despite persisting nothing: "nothing is stored" and "nothing is spent" are different claims. **Verified by:** V49 applied against real Postgres (applies, re-runs clean, accepts the three new kinds, still rejects an unknown one), 586 BFF tests, 67 DAO tests. **Note the urgency inverted:** this row was written when the OpenAI path looked like a live uncapped leak. `OP-27` showed it was spending nothing, making this insurance — and then fixing `OP-27` turned the model on, which made it live again. | 2 | Closes the vendor asymmetry `OP-24`(c) names. Sequence AFTER `OP-25`/`OP-26` — those are live exposure, this is cost control |
| `PR-34` | Apollo enrichment + lead-to-workspace workflow | 3 | Commercial enablement: sync Apollo accounts/contacts into Tejdux as brand or agency records, assign tier + owner, create onboarding tasks and first campaign brief. Apollo is the source of qualified leads, not the creator database or source of truth; keep it out of the operational creator workflow. **The trap, recorded 2026-09-04:** the word `enrich` is already taken here. `CreatorClassificationClient` / `CreatorOnboardingService` enrich a CREATOR (handle → niche, via OpenAI, metered by `PR-62`, rate-limited by `OP-25`), and `BriefEnricher` enriches a campaign brief for `PR-35`. Neither touches leads, accounts, tiers or owners. Anyone grepping `enrich` to find where Apollo belongs lands in the creator pipeline first — one plausible step from wiring it into exactly the place the sentence above forbids. Apollo data describes people this platform is SELLING to; creator data describes people its customers PAY. Different consent story, different retention, and they must not meet. **Also blocked on you:** Apollo is a paid subscription and no API key exists in Secrets Manager or the repo. |
| ~~`OP-11`~~ | Stripe Tax — **done 2026-08-21 (test mode)**. Head office set, `txcd_10103001` on both products, `automatic_tax` + `tax_id_collection` on checkout. **Zero registrations by choice**: no nexus at zero revenue, so it computes $0 and collects nothing until one is added. UK/EU deferred — see Decision 8 | 0 | Was: VAT is owed from the first EU/UK sale; retrofitting onto issued invoices is painful |
| ~~`PR-02`~~ | **Activation** — guided first run, empty states, welcome email, demo seed. ✅ **SHIPPED 2026-09-02** (delivery of the welcome mail waits on `OP-06`). `shell/activation.js` derives a five-step checklist from what the workspace already has, rendered by `packages/ui/src/ActivationChecklist.jsx`. **On `/workflow`, not the dashboard** — `DEFAULT_ROUTE` is where a new signup actually lands, and its own comment says why ("the board they work out of, not a dashboard of zeros"); a checklist on a page they may not open for days is one nobody reads. **The ORDER is the opinion and is pinned by tests:** creator → campaign → coupon → page → store, with the store LAST because it is the only step depending on someone else's system and opening with it strands people on day one. **Derived, never stored** — a persisted checklist can disagree with reality (ticked while the creator it refers to was deleted) and would then be wrong on the first screen a new user trusts. It renders null once complete or once the workspace has attributed revenue. Duplicated into the workflow remote WITH a `remoteCopies.test.mjs` guard, because production serves the remote. **Verified by:** `shell/activation.test.mjs` (13 tests) and `tests/e2e/activation.spec.js` (2 tests against PRODUCTION — a fresh signup lands on '0 of 5 done' and the CTA reaches a working page). **Welcome email SHIPPED 2026-09-02** — `identity/application/WelcomeEmail.java`, sent best-effort at signup so a mail provider having a bad minute cannot fail an account that already exists. It lists the checklist's five steps in the checklist's order, and `WelcomeEmailTest` reads `shell/activation.js` to prove it rather than duplicating the list (verified by reversing the order: it fails). It DELIVERS only to verified recipients until `OP-06` clears, and logs when it does not — the silent half of that row. **The demo seed was considered and deliberately NOT built:** seeding sample creators spends a free tier's 25-creator budget, is indistinguishable from real data the moment it exists, and would tick the activation checklist's first step with a creator nobody added — the exact failure `activation.js` is built to avoid. Shipped instead: a **sample CSV** on the import path (`shell/sampleImport.js`, mirrored into the campaigns remote with a drift guard), whose columns are the ones `agent_service`'s mapper recognises so it maps with no correction step. **Empty states SHIPPED 2026-09-02** — an audit found `EmptyState` already used well where it matters (creators, campaigns, revenue all name the space and offer the action that fills it). Three were still bare facts and are now sentences that say what fills them; deliberately NOT converted to `EmptyState` components, because two sit directly beneath the form that fills them and a centred card would duplicate a control already on screen. **`PR-02` is COMPLETE except the welcome email's DELIVERY**, which is `OP-06`'s sandbox, not this row's work — though `EmptyState` is already used well across the app, so that last part is smaller than the row implies | 7 | The highest-value product work remaining. Against a free incumbent, activation *is* the product |
| `OP-19` | **The section editor was blank in production** — ✅ **FIXED AND DEPLOYED 2026-08-27.** `InfluencerContentUI/src/components/SectionEditor.jsx` had lost its entire import block while still referencing `useState`, `useEffect` and `SECTION_TYPES`. Vite built it without a word — bundlers do not resolve undefined globals — so opening the section editor on tejdux.com threw `ReferenceError` and rendered nothing. **Live since 2026-08-25 23:19**, the same evening PR-39 switched `landing_editor = "sections"` on, so this was the editor every brand got. **Confirmed against the deployed bundle rather than inferred:** in `ContentPage-Cj13Gjt3.js`, `SECTION_TYPES` had zero definitions and zero imports, and `useState` (x8), `useEffect` (x3) and `useMemo` (x1) all appeared bare and unminified — an identifier that survives minification unrenamed is one the bundler treated as a global. The shell's copy was correct, so it worked perfectly in local dev: exactly the failure `VITE_USE_REMOTES=true` creates and that CLAUDE.md §1 warns about. **`contentRemoteCopies.test.mjs` could not catch it** — it compares BELOW the header comment so each copy can explain its own duplication, and imports sit above that line; a second assertion now checks the remotes import what they reference, confirmed to fail when the imports are removed again. Deployed by hand (the script needs Terraform outputs that are not available locally) with the same two-pass cache headers and a targeted invalidation. **Verified live:** bare `SECTION_TYPES` 1 -> 0, `useState` 8 -> 0, editor still present. **Found only because PR-44 required reading that file closely enough to reuse it** — which is the first real cost of the duplicated-pages debt `@influencer/ui` is meant to repay | 0.5 | **A third copy of `SectionEditor` lands in PR-44.** That is the point at which the extraction stops being a tidy-up and becomes the fix for a class of bug that has now bitten once |
| `OP-22` | **The creator portal had never worked in production — two defects, both silent.** ✅ **FIXED 2026-08-30, shipped in `v1.0.39`.** **(a) No invitation could be redeemed.** `POST /public/creator-invites/redeem` failed on `null value in column "created_at" of relation "creator_identities"`, and the BFF collapses every DAO failure there into one deliberately vague 409 — *"This invitation is no longer valid. Ask the brand to send a new one."* — so the screen blamed the token, the brand re-sent a link that failed identically, and the cause never surfaced. **The third instance of the same trap** (`campaign_creators`, then `creators`, now the identity and its link): `created_at`/`updated_at` are `not null default now()`, but a Postgres default applies only when the column is **omitted** from the INSERT, and both are mapped fields Hibernate always names. Redemption was the ONE identity path that did not stamp them — `CreatorIdentityController`, the portal session, email verification and tenancy all already did, which is why every other way of creating an identity worked and this went unnoticed. All four tables on that path were audited, not just the one the error named. **The vague 409 stays** — distinguishing expired from used from never-existed would let someone probing tokens learn which were real — but it can hide a bug, which is worth knowing. **(b) Every invitation link the brand copied was dead.** `CollaboratorPanel` built it from `window.location.origin` — the **brand's** host — and `tejdux.com/invite` is not a route, so the SPA fell through to the marketing landing page and the token was never redeemed. Verified by loading it: it renders *"Tejdux Influencer CRM — Every creator, from first DM to final payout"*. The invitation **email** always built this correctly from `creatorPortalBaseUrl`; only the on-screen copy was wrong — which is the copy that matters, because SES is sandboxed and the panel's own comment says surfacing the link is the only way a brand can invite anyone today. Fixed in **both** `CollaboratorPanel` copies (`contentRemoteCopies.test.mjs` guards the pair), and `deploy-ui.sh` now writes an `.env.production` for the content remote: Vite inlines `import.meta.env` at BUILD time and production serves the **remote**, so a variable only the shell receives is undefined there — the fix would have been correct in dev and absent in production, the same class as `OP-19`. **(c) And a third behind (a), reachable only once the timestamps were fixed:** redemption then failed on `null value in column "creator_id" of relation "creator_identity_links"` — the same vague 409, the same intact token. This one is a mismatch between two deliberate decisions rather than an oversight. `creator_invites.creator_id` is **nullable** because `V46` exists precisely so a brand can invite someone it has no record of yet (the bootstrap circularity that migration was written to break), but `creator_identity_links.creator_id` is **NOT NULL** because that table's purpose is recording which brand-side creator row a login speaks for, and **two unique indexes are built on it** — relaxing the column would weaken both silently, since NULLs are distinct in a unique index. So redemption now RESOLVES the row: existing-first by the invited email, because an invitation usually follows an import by minutes and a second row would split one creator's fees and coupons across two records the roster cannot tell apart; only when the brand has nobody at that address is one created. Its handle is derived from the email and prefixed `invited:` — `creators.handle` is NOT NULL and an invitation carries no platform identifier, so it must be *something*, and must not read as a real Instagram handle someone could message. Identity reaches the creator context through `CreatorProvisioningPort`, the published port `campaign` already uses; ArchUnit passes. An invitation that **does** name a creator still uses it — resolving by email there would silently override the row the brand chose. **(d) The invite screen then signed up an account redemption had just created.** `RedeemRequest` carried no password and the BFF passed `null` to the DAO, so redemption produced an identity with **no credential** — and `InvitePage` followed it with `/auth/signup` to set one, against the email that had just been registered. The server answered *"An account with this email already exists"*, correctly, every time. It failed **after** the link was confirmed, so the creator saw a generic error on the last step of a relationship that had actually been created. The password now travels with the redemption, BCrypt-hashed in the BFF using the encoder `CreatorPortalService` verifies with; the screen then logs in. **Consent moved with it** — that signup call was also what recorded agreement to terms, and dropping it would have silently lost the consent record for every creator, on the surface where it matters most. Recorded on redeem now, *after* the redemption succeeds: consent stored against an expired invitation is a row nobody wants. **(e) The invite screen re-rendered over the session it had produced.** `App.jsx` reads the invite token fresh from the URL on every render and gives the invite route precedence over any session — correct, so a signed-in creator following a **second** brand's invitation lands on that invitation rather than their page list. But it could not distinguish *arriving with* an invitation from *having just accepted* one, so setting the session re-rendered the same screen and the creator was stranded on a form they had completed. The token is held in state and cleared on success, along with the query string — which also stops a spent single-use token sitting in the address bar where a refresh reopens an invitation that can no longer be accepted. **(f) And the handoff button could never appear for anyone.** It required stage `approved`, and **nothing in any UI calls the stage endpoint** — `LandingStageController` is reachable only by API, so every page sits at `draft` forever and the whole collaboration feature was gated on a state the product has no way to leave. `HANDOFF_STAGES` now includes `draft` and the stage machine allows `draft → creator_assigned`, the transition the handoff already performs. The original comment called handing off a first draft *"a mis-click, not a workflow"* — reasoning that assumed the brand could approve it first. `published` is still refused. Two tests asserted the old rule; the **property** each checks (a refused handoff leaves no orphaned grant) is unchanged, so `PageHandoffTest` moved to `published` rather than losing the assertion. **(g) The two invitations never met.** The panel's "Send invitation" creates an identity and a confirmed brand link; the **page collaborator grant** is a separate record, and nothing created it — so a creator could accept, sign in and see "Nothing yet" while the brand's panel said "No creator on this page yet" about someone who had accepted. The invitation already carried `landingTemplateId` (the panel sends it, the column has always stored it); redemption now uses it, delegating to `invite()` so both its guards still apply. Non-fatal by design: the identity, link and consent are written by then, and failing over a page grant would burn a single-use token on something recoverable. **(h) That fix deployed and never ran.** Its guard needed `landingTemplateId` on the redeem response, and the DAO returns the confirmed **link** row — brand, identity, status, nothing about the page. The condition was false every time, silently. The service now reads the invitation before redeeming (the last moment it can) and merges the page and inviter into the response. **The test fake was part of why this shipped:** `RecordingDao` echoed every POST body back, so `redeem` appeared to return the invitation with fields the real DAO has never sent — the fake was more generous than production. It now returns a link row for that path. **(i) And the shell was serving a stale copy of the fix.** `CollaboratorPanel` is duplicated, both copies were changed together and `contentRemoteCopies.test.mjs` passed — but only the **content remote** was redeployed, so the shell's fallback still gated on `approved` and rendered "Approve this page before handing it to a creator" over a page the API reported as `draft`. Verified by grepping both live bundles: the remote had ``draft`,`approved`,`creator_assigned`` and the shell did not. **The guard test compares source, not what is deployed.** **(j) The panel then showed a version behind.** The handoff returned 200 and the button went on offering "Hand over to creator": `onRefresh` reloaded the collaborator list, but whose-turn-is-it comes from `page.turn`. Both refresh now. **All ten found by driving the invite→handoff→edit flow for the demo capture**, not by a test — and each was only reachable once the one before it was fixed, which is the argument for driving the whole path rather than testing its parts. Every one of `PR-40`..`PR-44`'s unit tests passed throughout. **Verified by:** `dao/identity/api/CreatorInviteRedemptionTest.java` (5 tests, each confirmed red before its fix), `HandoffMachineTest`, `PageHandoffTest`; DAO 65 incl. ArchUnit, BFF 565, UI 250. Shipped in `v1.0.39` (a, b), `v1.0.40` (c), `v1.0.41` (d), `v1.0.42` (f), `v1.0.43` (g) and `v1.0.44` (h), with (e), (i) and (j) across six UI deploys. **Verified end to end in production 2026-08-31:** invite → redeem → sign in → hand off → the page reaches the creator's portal → the creator's editor opens on it, all six assertions green. **`deploy-ui.sh` had never included the portal** — it was deployed by hand, so backend fixes shipped while the app serving them stayed stale; it is in the script now | 2.5 | **The entire creator half of the product was unreachable.** `PR-40`..`PR-44` shipped the portal, the endpoints, the editor and the acknowledgement email — and no creator could get past the front door, nor could any brand hand a page over even if one had. Neither defect is in that code: one is a timestamp in the identity context, the other a URL origin in a shared component. Both are the kind only an end-to-end drive finds, which is the argument for `tests/e2e_handoff.sh` covering this path rather than the unit tests that already pass |
| `OP-20` | **Spreadsheet import returned 500 for the commonest mapping there is** — ✅ **FIXED 2026-08-29, awaiting deploy.** A creator roster has no campaign column in it: names, handles, emails, a fee, a notes column. Mapped the obvious way — `creator.name`/`handle`/`email` plus `campaign_creator.agreedFee` — every row built a plan whose `campaignValues` was **null**, because `HydrationRowPlan` nulls each value-group it found nothing for. `resolveCampaignId` then dereferenced that null on its first line; the DAO returned 500, the BFF turned it into **502**, and the user saw an import that simply refused. `resolveCreatorId` had the identical shape. Guarding those alone was **not sufficient**: the resolver legitimately returns null when the row names no campaign this brand already has, and that null reached `campaignRepository.existsById(null)`, which Spring Data rejects outright — so the guard has to sit before the call, not inside it. An unattachable relationship now skips the **link** rather than the row: the creator is still created, because dropping the row would discard the roster the user came to import. **Found by recording the demo against production**, not by a test — which is why the regression test drives the real mapping rather than a synthetic one, and why it was confirmed to fail with the exact production `NullPointerException` before the fix was restored. **One cosmetic issue left, and it is NOT the arithmetic bug it looks like:** the button reads *"Import 16 records"* for an 8-row file. `plannedOperationCount` counts *operations*, not records — 2 per row here (a creator and a campaign_creator), so 16 is correct — and the `0 new / 0 updated / 0 skipped` beside it is correct for a dry run, which by definition creates nothing. The defect is the word **"records"** in `InfluencerUI/src/pages/ImportPage.jsx`, which invites the reader to expect 16 creators from 8 rows. Left alone deliberately: changing user-facing copy is not something to fold into a hotfix deploy, and the number is right. **A SECOND defect sat behind the first**, reachable only once the crash was gone: the import then got as far as the database and was rejected there with *"null value in column `content_themes` of relation `creators` violates not-null constraint"*. `content_themes`, `risk_flags` and `vetting_status` arrived later than the rest of the columns and were never added to `CreatorProvisioningService.applyDefaults`. Each is `not null default ...`, which reads as though the database fills it in — it does not: **a Postgres default applies only when the column is OMITTED from the INSERT**, and every one is a mapped field Hibernate always names, so an unset field is written as an explicit NULL and rejected. Exactly the trap `CreatorProvisioningServiceTest` was written for on `campaign_creators`; the creator side had the identical hole and no test. **All** not-null columns on `creator.creators` were then checked against `applyDefaults` rather than only the one the error named — there are no others. Found by reading the **RDS Postgres log** after the `v1.0.37` deploy; two rounds of reasoning from the committed schema had pointed at the wrong column, because the base schema file is stale and Flyway owns the truth. **Verified by:** `dao/campaign/application/ImportBatchHydrationServiceTest.java` (3 tests), `dao/creator/application/CreatorProvisioningServiceTest.java` (+1, confirmed red without the fix), DAO suite 60. Shipped in `v1.0.37` (crash) and `v1.0.38` (defaults) | 1 | **Import is the first thing a new account does** — it is step two of the Getting Started checklist, and PR-02 (Activation) is built on it. Every trial user who brought their own spreadsheet hit this. The blast radius is larger than the fix: any mapping that omits one entity, not merely this one |
| ~~`OP-18`~~ | **Creator-collaboration foundation repair** — ✅ **SHIPPED 2026-08-27.** five live defects in the already-shipped collaboration path, four verified directly against the code. (a) `saveAsCollaborator` handles only `document`/`blocks`, **not `sections`** — since `PR-39` switched production to the section editor, a creator's edit returns 200 and is silently discarded. (b) The same method drops `scheduledPublishAt`/`hostingExpiresAt`/`firstPublishedAt`; the DAO comment at `LandingService.java:85` states the rule — *"every BFF caller writing this row restates it"* — and `PageCollaborationService` is a BFF caller that does not, so **one creator save silently cancels the brand's scheduled launch**. Extract one shared carry-forward helper, since this has now been got wrong twice, and fix `changeStage` the same way. (c) `decide()` loads the claim by `linkId` alone with no brand comparison; the BFF checks `CREATOR_WRITE` but never that the link is the caller's, and `linkId` comes from the URL — so **any user with `creator:write` in any brand confirms another brand's pending creator claim by guessing a UUID**. Check `brandId` at **both** BFF and DAO. (d) `pagesForCreator`/`requireEditRights` read `brandId` from the grant row and never compare it to the fetched page's, so a malformed grant is cross-brand read+write. (e) No `@Version` on `LandingTemplate` — concurrent brand and creator edits overwrite unrecoverably; add it with a 409 carrying both versions, and make `snapshotVersion` capture `before`, not `saved`. Also: `X-Creator-Token` into `setAllowedHeaders`, and symmetric status on backward stage transitions. **These are the first unit tests either service has had.** **Design:** `docs/Creator-Handoff-Design.md` §1 | 3 | **Two of these are live cross-tenant defects and one is silent data loss, all in production today**, independent of whether the creator portal is ever built. This is the one row worth doing regardless of how §10.4 is resolved — it fixes shipped bugs, not speculative ones. Ordered first because every later row writes through the code it repairs. **Verified by:** `schema/flyway/V44__landing_template_optimistic_lock.sql`, `webe/content/application/LandingTemplateWrites.java`, `webe/content/application/PageCollaborationSaveTest.java` (7 tests), `dao/identity/api/CreatorLinkDecisionTest.java` (4), `dao/content/api/LandingTemplateVersionTest.java` (4). DAO 56, BFF 510. Every defect test was confirmed to FAIL against the unfixed code rather than merely passing against the new one — a regression test that was never red proves nothing. **Two findings worth carrying:** the `sections` omission did not destroy a creator's edit as first thought — the DAO null-guards that column, so the save was *silently ignored* and returned 200, which is harder to diagnose than loss. And `changeStage` had the same `scheduledPublishAt` omission, so dragging a Kanban card cancelled a scheduled launch too; its content columns were never at risk, again because of the DAO's guards |
| ~~`PR-40`~~ | **Creator auth filter + the `turn` axis** — ✅ **SHIPPED 2026-08-27.** — `CreatorTokenAuthenticationFilter`, and `CREATOR_PORTAL_PATHS` moves from `permitAll()` to `authenticated()`. Today that matcher is `permitAll()` with *all* authentication hand-rolled in controller bodies, and this plan adds six to eight endpoints to that surface: one forgotten `requireCreator(token)` is a fully unauthenticated endpoint serving unpublished pages. The filter makes a forgotten check **fail closed**. `V45` adds `turn` (nullable `brand`&#124;`creator`), `turn_changed_at`, a `page_handoffs` audit table, and `creator_portal_sessions` (**`V44` went to `OP-18`**, which shipped first — the version is a position in the sequence, not an identifier of a plan) — the session map is a `ConcurrentHashMap` today and an ASG roll is the live step of every deploy, so it must be a table before the first real creator uses it. `HandoffMachine` shaped after `LandingStageMachine`; `assertCreatorStageTransition` as one central allowlist defaulting to deny with `PUBLISHED` unreachable unconditionally, rather than restated per endpoint. Idempotency keys are per-occurrence, **not** `templateId:from->to`, because work legitimately loops twice | 5 | The spine. **Stage and turn are orthogonal** — stage answers *how far along*, turn answers *whose move*; a page sits at `content_needed` while the turn bounces three times. Provable end-to-end by `tests/e2e_handoff.sh` before any UI exists. The `permitAll` → `authenticated` flip is the single highest-leverage security change in the plan. **Verified by:** `schema/flyway/V45__creator_handoff.sql`, `webe/security/CreatorTokenAuthenticationFilter.java`, `webe/shared/application/CreatorSessionVerifier.java`, `webe/content/application/HandoffMachine.java`, `dao/identity/domain/CreatorPortalSession.java`, plus tests `CreatorTokenAuthenticationFilterTest` (5), `CreatorSessionStoreTest` (6), `HandoffMachineTest` (6). DAO 56, BFF 528. V45 applied against a real Postgres, re-run for idempotency, both check constraints confirmed to reject bad data. **Three findings worth carrying.** ArchUnit refused the filter's first form — `security` is cross-cutting and may not import a context — so the credential is resolved through a `CreatorSessionVerifier` port in `shared`, mirroring `TokenVerifier`; that also narrowed what crosses the boundary to the creator's id, leaving the token inside the context that mints it. The session store's move was more urgent than its original comment implied: the justification given was "no second instance yet", but an ASG instance refresh is the live step of every deploy, so the map signed out every creator on every release. And `GET /creator-identities/{id}` returns `passwordHash`, so the new `findById` projects to id/email/displayName — closing part of what `PR-41` had been scheduled to fix |
| ~~`PR-41`~~ | **Tokenised invite + the first creator email** — ✅ **SHIPPED 2026-08-27.** — `creator_invites` with the token SHA-256-hashed at rest, single-use, 7-day expiry, ≥128-bit entropy and rate-limited redemption, following `MemberInvitationService` exactly. Redemption creates the identity and the confirmed link atomically. An expired link lands on "ask Acme for a new one", not a 404. Adds `findById`→email to `DaoCreatorIdentityClient` projecting **only** id/email/displayName, because `GET /creator-identities/{id}` currently returns the whole entity **including `passwordHash`** | 4 | **This is what breaks the bootstrap circularity**: today the only route to a `confirmed` creator link is an out-of-band UUID exchange, which is why the collaboration backend has been dark since Phase G. **Verified by:** `schema/flyway/V46__creator_invites.sql`, `webe/identity/application/CreatorInvitationService.java`, `dao/identity/api/CreatorInviteController.java` (redemption is one transaction), `CreatorInvitationServiceTest` (8 tests). V46 applied against a real Postgres; the partial unique index was confirmed to refuse a second pending invite and to free the slot on revoke. **Not blocked by `OP-06` after all** — a failed send deliberately does not roll the invitation back, and the token is returned to the inviting brand once, so a brand can pass the link on by hand until SES clears. **One bug worth recording:** `EmailPort.send` REPORTS failure rather than throwing it, and the `log` provider — today's configured default — returns `sent=false` having written a line, so a first version that only caught exceptions reported `delivered=true` for mail nobody sent |
| ~~`PR-42`~~ | **Brand-side collaborator panel + handoff button** — ✅ **SHIPPED 2026-08-27.** — invite/list/revoke, the pending-claims queue (brand-scoped after `OP-18`c), a "Waiting on you" filter driven by `turn`, and "Take it back". One button, one endpoint: `POST /api/landing-pages/{id}/handoff` = `invite(rights='edit')` + `changeStage(creator_assigned)` + set turn. Built in **`InfluencerContentUI`**, not `InfluencerUI/src/pages/` — production serves the remote. Add to `contentRemoteCopies.test.mjs`. Restrict the invite UI to `rights: 'edit'` for v1: `'comment'` is accepted by the schema and **has no possible client**, and shipping a grant nothing can honour is worse than recording the gap | 4 | Gives brands the feature whose backend already exists. **With `OP-18` this is a demoable product in 7 days** with no creator-facing code — see §10.4. **Verified by:** `webe/content/application/PageCollaborationService.handOff`/`takeBack`, `PageHandoffTest` (6 tests), `components/CollaboratorPanel.jsx` in BOTH trees, two new assertions in `contentRemoteCopies.test.mjs` (confirmed to fail on a one-line drift). DAO 56, BFF 542, UI 268; both Vite projects build. **Two silent failures found while building it**, each of which would have shipped looking correct: `ResponseShapeService`'s projection is an ALLOW-LIST, so `turn` was written to the database and never reached the UI — the "Waiting on you" filter would have sat permanently empty while the column held the right answer. And the DAO's PUT needed a null-guard on `turn` unlike `scheduledPublishAt` beside it: clearing a turn is meaningful, but the hosting sweep, the publish sweep and an ordinary save never mention it, and unguarded every one of them would have dropped a page out of somebody's list |
| ~~`PR-43`~~ | **Creator portal — invite screen and page list** — ✅ **SHIPPED, found already built 2026-09-04.** This row was still listed as 4 days of open work; the code says otherwise, and CLAUDE.md §7 puts the code first. **What exists:** `InfluencerCreatorPortalUI/` with all four screens — `InvitePage.jsx`, `SignInPage.jsx`, `MyPagesPage.jsx`, `EditPage.jsx`; the full BFF surface at `/api/creator-portal/*` (`auth/login`, `auth/signup`, `auth/logout`, `me`, `pages`, `pages/{id}`, `pages/{id}/hand-back`, `pages/{id}/preview`, `pages/{id}/sections/rewrite`, `claims`, `collaborations`); its own CloudFront distribution keyed `creator-portal` (`static-site.tf:78`) with the URL coming from a **terraform output** (`outputs.tf:90`), not `.env.production`, exactly as this row required. **The three specified security requirements are met:** the invite GET returns a redacted teaser and never the page (`CreatorInvitationService:124`, `CreatorInviteController:120`); login rate-limits **before the lookup and before BCrypt** (`CreatorPortalService:86`); the token is not BCrypt-hashed, with the reason recorded at `:167`. **Live-verified 2026-09-04:** `portal.tejdux.com` returns 200 and the `smoke-ui.mjs` check reports no JS errors and a populated root. **Most of it landed under `PR-44`**, whose row claimed only the editor — which is how a shipped feature stayed on the schedule as four days of pending work | 4 | Was marked "consider deferring" per §10.4. Moot: it exists, deployed and loading. §10.4's trigger question — a creator asking "where do I log in?" — is now answerable |
| ~~`PR-44`~~ | ✅ **SHIPPED 2026-08-27 — the creator handoff is complete.** **Shipped:** the creator editor (`InfluencerCreatorPortalUI/src/pages/EditPage.jsx`), which mounts `SectionEditor` from `packages/ui` as an IMPORT rather than a third copy — the extraction that `OP-19` forced was done first, deliberately, so this increment removed duplication instead of tripling it. The three creator endpoints (`/hand-back`, `/preview`, `/sections/rewrite`), each authorised by the creator's grant rather than by an operator permission and each re-checking that grant rather than trusting the session. `CreatorPublishedEmail` + `CollaboratorNotifier` — **the acknowledgement the whole handoff was asked for** — keyed on `first_published_at` rather than on reaching the published stage, so a publish→unpublish→republish does not send it three times; read BEFORE `startHostingWindow` stamps that column, since afterwards every publish looks alike. `CreatorHandedBackEmail` to the granting user, carrying the creator's note verbatim. A draft snapshot taken BEFORE access is revoked, because revoking happens in a hurry and the work in progress is what is most easily lost. And `access_revoked` distinguished from a bare 404: everywhere else a refusal is deliberately indistinguishable from a missing page, but this creator was demonstrably given this page, so a 404 would only tell them their work vanished. And the **abandonment sweep** (`HandoffReminderScheduler`, `HandoffReminderEmail`, `V47`): the creator at day three because the usual cause is a forgotten email, the BRAND at day seven because by then the useful action — chase them, or take the page back — is theirs. The longer threshold is checked first so a page that has waited eight days escalates rather than sending a reminder it has outgrown. `V47`'s `handoff_reminder_sent_at` is what makes the hourly sweep idempotent — without it, "four days elapsed" is true at hour 96, 97 and 98 and the creator is emailed every hour, which is how a sending domain gets marked as spam. A stamp rather than a boolean because two thresholds would have meant two flags to reset in step; compared against `turn_changed_at` instead, so passing a page back and forth re-arms the sweep with nothing to reset. An undelivered reminder is deliberately NOT stamped, or one transient mail failure silences that page permanently. **`OP-17` applies**, as to every `@Scheduled` here. **`V47` verified against a real Postgres 2026-08-27**: applies clean, re-runs idempotently, and the two properties the design rests on were checked in SQL rather than only in a unit test — stamping suppresses the reminder, and moving the turn re-arms it with nothing reset. One correction while doing so: the planner chooses a **sequential scan**, not `V45`'s partial index. That is right on a table this size and the index earns its place later, but the sweep is not index-backed today and it would be wrong to record otherwise. **Verified by:** `CreatorPublishNotificationTest` (8), `PageHandoffTest` (6), `PageCollaborationSaveTest`, `tests/e2e/creator-portal.spec.js`. `HandoffReminderSweepTest` (7). DAO 56, BFF 565 tests | 6 | Ghosting is the modal outcome in creator marketing, so the sweep is not polish — it is the difference between a workflow that handles its commonest ending and one that quietly accumulates dead pages a brand finds the week of the campaign |

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
| Social publishing **via platform API** (`PR-46`) | LPB-F | 6+ | **Decline reversed 2026-08-27** — see §10.3. `PR-27b` (Meta review, ~2d, dossier in `docs/platform-app-registration.md`) makes this reachable, where the predecessors assumed it was not. Still deferred: it needs `PR-45` first, and Instagram's `content_publish` screencast requires a working publish flow to record, which does not exist. Trigger: `PR-45` shipped **and** Meta approval granted |
| Per-brand publishing to the **brand's own** handle | — | 8+ | Not the small follow-on it looks like: the current integration reads via `business_discovery` from *our own* single connected account. Publishing to a customer's handle needs per-brand Business Login with `config_id` (**not** `scope` — the Business app ignores it, learned the slow way per the dossier), a per-tenant token store, refresh, and a Page-selection screen. None exists |
| Meta webhook receiver (`PR-57`) | — | 2 | **Not required for the `instagram_basic` submission** — verified 2026-09-03: no `hub.challenge`, `hub.verify_token` or `X-Hub-Signature` handler exists anywhere, and none is needed. Instagram webhooks push events about accounts that AUTHORISED the app; `InstagramProfileAdapter` does the opposite, reading creators who never will via `business_discovery`. Pull, not push — so an empty Webhooks section in the dashboard blocks nothing, and subscribing to a product the adapter does not use invites the same "where is this used?" question that withdrew `instagram_manage_insights` on 2026-08-16. Shape when it lands: `GET` echoing `hub.challenge` after a constant-time compare of `hub.verify_token` (a string WE invent and paste into both the dashboard and config — Meta does not issue it), plus `POST` verifying `X-Hub-Signature-256` HMAC over the **raw** body. `BillingWebhookController` is the working template for the raw-body half. Trigger: mention/comment tracking becomes a real feature, or `PR-46` publishing needs delivery receipts |

**U2 is the clearest defer in the backlog:** a quarter of the remaining product budget on scale work
for zero users. Nobody has enough rows to page.

**Critical path to first paying subscriber: ~35–40 dev-days.** This figure **excludes** the creator handoff block (`PR-40`..`PR-44`, ~25d) added 2026-08-27, which is scheduled in Stage 1 but is not on the path to first payment — §10.4 records why, and holds that decision open. `OP-18` (3d) *is* included: it repairs defects that are live in production now.

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

9. **No LLM prompt caching, and the reason is a measurement, not a preference.** Decided 2026-08-27.
   The cacheable prefix of the page generator was measured at **~437 tokens** (system prompt ~163 +
   tool schema ~274) against `claude-opus-5`'s **512-token minimum**. Below that floor the API accepts
   `cache_control`, caches nothing, and reports `cache_read_input_tokens: 0` **with no error** — so
   adding it today would be code that looks like an optimisation and provably is not. Volume does not
   change this: traffic grows the *number* of requests, not the size of the prefix. Only content growth
   crosses the floor, and the per-actor voice prompts in §10.3 would (437 → ~700). Even then the saving
   at present volume is **under a dollar a month**, while output — billed at 5× input and never
   cacheable — dominates. **The trigger to revisit is both** a materially larger prefix (a brand style
   guide or few-shot examples, not just the voice prompts) **and** generation volume worth the wiring.
   Carry forward one trap for whoever does: the tool schema is byte-stable today only *by accident*
   (Jackson `ObjectNode` preserves insertion order; the fields are added unconditionally), so any future
   conditional field would silently kill every cache hit with no error — that needs an explicit test on
   the day caching goes in.

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

## 10. The creator handoff (added 2026-08-27)

Scheduled as `OP-18` and `PR-40`..`PR-44` in Stage 1, with `PR-45`/`PR-46` below. Full design and the
evidence for every claim: **`docs/Creator-Handoff-Design.md`**. Creator UI: `docs/Creator-Portal-UI-Design.md`.
Prompt design: `docs/Creator-Handoff-AI-Per-Actor.md`.

**The ask:** a brand owner or marketer initiates sharing a page with a creator; both collaborate on
authoring it using their own logins; the brand publishes; the creator is acknowledged by email and can
then publish it to their own handle.

### 10.1 The shape — this is not a new subsystem

The collaboration engine, the stage machine, the AI ports and the email transport all already exist.
`CreatorIdentity` even carries a `passwordHash`, so creator login exists at the data layer. What is
missing is a creator-facing UI, an invitation with a token, and **one nullable column**.

Two orthogonal axes, and keeping them separate is the design:

- **Stage** (existing, 8 values in `LandingStageMachine`) — *how far along is this?*
- **`turn`** (new, nullable `brand`\|`creator`) — *whose move is it?*

They change for different reasons. A page sits at `content_needed` while the turn bounces three times.
Collapsing them into one column is the obvious-looking simplification that breaks the moment a creator
hands back work the brand then hands forward again.

**One rule, to be written as a comment where it can be re-litigated:** the collaborator row is
authoritative for *is a creator involved*; the stage is display. `revoke()` moves the stage back to
`approved` when it removes the last active grant. A single command path owns both columns — never a
direct column write.

### 10.2 The lifecycle

```
                        turn=brand
  draft ──► review ──► approved
                          │
                          │  ◄─── THE HANDOFF (one button, one endpoint)
                          │       POST /api/landing-pages/{id}/handoff
                          │       = invite(rights=edit) + changeStage(creator_assigned) + turn=creator
                          ▼
                  creator_assigned ──────────────► content_needed
                        turn=creator                  turn=creator
                          │                               │
                          │  "Take it back"               │  "Send back to Acme"
                          │  (existing reverse edge)      │  POST /creator-portal/pages/{id}/hand-back
                          ▼                               ▼
                      approved                      content_needed, turn=brand
                                                          │
                                                          │  brand reviews, accepts
                                                          ▼
                                                   ready_to_publish
                                                     turn=brand
                                                          │
                                                          │  ONLY content:publish
                                                          ▼
                                                      published ──► performance_tracking
                                                     turn=null          turn=null
```

**The four actors.** *Brand owner* (`OWNER`, account-scoped) authors, invites, and is the only actor who
publishes. *Agency owner* is structurally identical — the multi-brand case is already handled by
`AccountRole.impliesAllBrands()`, so **no new code**. *Marketer* (`MARKETER`, brand-scoped) does the
day-to-day authoring and initiates the handoff, but holds `CONTENT_WRITE`/`CREATOR_WRITE` and **not**
`content:publish`, so they can hand off and hand back but cannot publish — the existing separation, kept.
*Creator* is **not a role at all**: they authenticate through `CreatorPortalService` with `X-Creator-Token`
and deliberately hold no `accountId`, no `brandId` and no `account_role`.

**What a creator cannot do, and why it is structural rather than checked:** `saveAsCollaborator` reads
`status`/`stage` off the stored row, and `ck_collaborators_rights` has no `publish` value — the constraint
*is* the policy.

**The unhappy paths, which all three candidate designs omitted.** Ghosting is the *modal* outcome in real
creator marketing, so `PR-44` includes an abandonment sweep on `turn_changed_at` (creator reminder day 3,
brand nudge day 7), a retraction email when a page a creator was emailed about is unpublished, and a
revocation experience that hands the creator their draft rather than a dead end.

### 10.3 AI, and what is deliberately excluded

**No new AI capability is needed.** Everything reuses the two shipped ports, `PageGenerationPort.generate`
and `rewriteSection`. The highest-value new use is the creator's **"rewrite in my voice"** per section —
framed as helping *them* sound like themselves, not helping the brand. Creators are not copywriters, and
this is what makes co-authoring work instead of producing a blank box they abandon.

Two invariants worth writing into `sectionTypes.js`, because they are the obvious thing a future change
breaks: **AI never crosses the curated-editor line** (it returns typed sections validated against
`SECTION_TYPES` and cannot emit a colour, font, size or position, because those fields do not exist), and
**AI never changes the turn or the stage** (generation is a content operation; a model does not decide a
page is ready).

**Prompt caching is explicitly NOT in this plan (decided 2026-08-27).** The cacheable prefix was measured,
not estimated: system prompt ~163 tokens + tool schema ~274 = **~437, against `claude-opus-5`'s 512-token
minimum**. Caching today is a silent no-op — the API accepts `cache_control`, caches nothing, and
`cache_read_input_tokens` stays 0 forever. Adding the per-actor voice prompts would take the prefix to
~700 and make it engage, but at present volume the saving is **under a dollar a month**, and output — 5×
input, never cacheable — dominates the bill regardless. Revisit only if generation volume and prompt size
both grow. One finding to carry forward if it is ever revisited: the tool schema *is* byte-stable today
(Jackson `ObjectNode` preserves insertion order and the fields are added unconditionally) but **safe by
accident, not by intent** — any future conditional field would silently kill every cache hit with no error,
so that needs an explicit test.

**On `regenerateVariant`**, since it looks like a cost lever and is not: it generates three drafts and
discards two, deliberately — the docstring explains that a generator whose one draft collides with a seen
headline would otherwise have nothing left to offer. The genuinely cheap path is `rewriteSection`
(`max_tokens: 2000`), which is already correctly scoped and is what the per-section work uses.

**Social publishing** is `PR-45`/`PR-46`, and the four-times-made decline is now partially reversed —
see Stage 4. Three hard prerequisites the candidate designs treated as details:

1. **There is no `S3AssetStorage` class in the repo.** `FilesystemAssetStorage` is `matchIfMissing=true`
   and `assets.provider` is set nowhere, so production serves assets off one EC2 box's local disk — the
   adapter's own header says "not intended for production." Meta's Content Publishing API **fetches your
   asset URL server-side**, so object storage is a *precondition of the API path*, not share-kit polish.
2. **There is no image resizing anywhere** — zero hits for `Graphics2D`/`getScaledInstance`; `AssetService`
   uses `ImageIO` only to measure. "Per-platform aspect ratio" is unwritten code, not an existing capability.
3. **Instagram captions are not clickable**, so the share kit's central artifact — a tracked URL — is inert
   plain text on the dominant platform. Lead with **the coupon code** (already the attribution primitive,
   and it survives being read off a screen). Also verify `/s/{slug}/{creator}` returns 200 before showing
   it: that route **404s when no coupon matches**, so a creator on a coupon-less page would get a dead link.

**And the credential trap:** the Instagram Page token **expires every 60 days with nothing refreshing it**
(`secrets.tf:230`) and `OP-02`, the alarm for it, is unshipped. Worse,
`SocialPlatformRegistry.find()` returns empty on `!isConfigured()` and falls through to **fabricated metrics
by design** — correct for read-only vetting, and a *liar* the moment a publish path shares the credential.
**Rule: a failed publish must surface as a failure; only a failed read may degrade to simulation.** Model
the port with three outcomes, not two — `posted`, `staged_for_user_confirmation` (TikTok inbox / IG draft),
`manual` — because a binary real-or-manual result forecloses the inbox path that is reachable *now*.

| ID | Item | Size | Status |
|---|---|---|---|
| ~~`PR-45`~~ | **S3 asset storage, then the Share Kit** — ✅ **SHIPPED 2026-09-02.** `S3AssetStorage` + flip `assets.provider` (a precondition of any future API path, per §10.3). `SocialPublishPort` with three outcomes and a `manual` default. Per-platform AI-drafted captions from the page's own sections — the best AI input in the product, because it is already structured and already written — correctly-sized assets, the tracked link, **non-removable `#ad` disclosure** (an FTC obligation, and `Brief` already carries disclosure as a first-class field with the reasoning encoded, so reuse it rather than reinventing), a QR for desktop-to-phone, `navigator.share()` on mobile, and "I posted this" closing the loop back to the brand. **No platform adapter.** **Shipped:** `S3AssetStorage` (behind `assets.provider=s3`, filesystem still the default) with `AwsSigV4.signS3Get`/`signS3Delete` — HEAD/GET/DELETE now share one verb-parameterised signer, and the test for it caught a real bug where the canonical request stayed hardcoded to `HEAD`, which would have been a 403 in production naming neither verb. `SocialPublishPort` with its three outcomes, `ManualSocialPublisher` as the `@ConditionalOnMissingBean` default, `ShareKitService` and `GET /api/landing-pages/{id}/share-kit`. **A correction to this row's own premise:** it says production serves assets "off one EC2 box's local disk". It does not — `WEBE_ASSET_ROOT=/mnt/assets` is an EFS access point with its own Terraform resource, so uploads already survive an instance refresh. S3 buys CloudFront serving, an origin Meta's API can fetch, and a host for `PR-59`'s `og:image`; it does not rescue durability, which was never at risk. **Verified by:** `ShareKitServiceTest` (9), `ManualSocialPublisherTest` (4), `S3AssetStorageTest` (7), and **live in `v1.0.53`** — the kit returns its disclosure and per-creator link, and "I posted this" records a row. **Share sheet shipped too:** `packages/ui/src/ShareSheet.jsx`, mounted per creator in the coupon list and only once the page is PUBLISHED — the server 409s a kit for a draft rather than handing over a link nobody can open. The disclosure is joined AT THE POINT OF COPYING, never in a box someone can edit it out of. **"I posted this" is a row (`V50`), not a flag** — a boolean answers "did anyone post this" and nothing else, while one page is shared by many creators. Named `share_posts`, a CLAIM rather than a measurement: nothing here can see a creator's feed, and when `PR-46` lands an adapter a verified post is a different row. **The QR cost 23.5 KB, not the 555 KB its package size implied** — measured, 560.7 KB with against 537.1 KB without; `react-qr-code` renders SVG and joins the `dedupe` list in all four configs that alias `packages/ui`. | 6 | Deferred with `PR-43`/`PR-44` — the honest answer to "creator publishes to their handle", and genuinely good rather than a consolation prize |
| `PR-46` | **Platform API publishing** — the adapter bodies behind `SocialPublishPort`. | 6+ | Stage 4. Gated on `PR-45` **and** `PR-27b`/`PR-27c` |

### 10.4 The honest argument against this plan — an open decision

**This is wrong if the bottleneck is demand, not workflow.** The project is pre-revenue with zero
subscribers. `PR-40`..`PR-44` spends ~25 days building a *two-sided* collaboration product, and two-sided
products have a two-sided cold-start problem: creator-side UX for creators who do not exist yet, for brands
who are not paying yet. §2.1 of this document says the remaining gap is **commercial, not technical**, and
`PR-02` (activation) is named there as the highest-value product work remaining. Every day here is a day
not spent on that.

**It is also wrong if the real ask is a demo.** `OP-18` + `PR-42` alone (**7 days**) give a working
brand-side collaborator panel over a backend that already exists, and the creator half can be narrated.

**The cheaper alternative that delivers the same literal ask — roughly 6 days:**

1. **`OP-18` regardless.** Three days.
2. **Skip the portal.** Send the creator a **tokenised magic link to a single-page editor** — no account,
   no password, no session table, no eighth CloudFront distribution. The token *is* the auth, scoped to one
   page, expiring in 7 days. Mount `SectionEditor` on a route inside the existing `InfluencerContentUI`.
   Three days.
3. **One email at publish.** Half a day, once `OP-06` clears.

That buys the handoff, the co-authoring and the acknowledgement for a fifth of the cost. What is lost:
creators cannot see history across brands, cannot manage a profile, and get a new link per page.
~~**If a real creator ever asks "where do I log in to see all my work?", that is the trigger for `PR-43`.** Until someone asks, the portal is speculative.~~ **Overtaken by events — corrected 2026-09-04.** The portal was built anyway, largely under `PR-44`, and is deployed at `portal.tejdux.com`. The question this section framed as the trigger is now answerable, so the deferral argument below applies only to what has NOT been built. The reasoning is kept because it is still the right test for the next speculative surface.

**The one thing not to cut either way is `OP-18`.** Everything else here is a judgement call about where
the product is. Those five defects are wrong today, in production, in code that is already shipped.

---

## 11. Payments and creator onboarding (added 2026-08-29)

Source: `tejdux-payout-roadmap.md`, drafted 29 Aug 2026. Scope: everything between "campaign ends"
and "creator has been paid." Entity: KMPS Global Corporation (d/b/a Tejdux).

The pitch it serves: **"You run the campaign. We handle attribution and the payout paperwork."**
Judge every item below against whether it makes that sentence more true.

### 11.1 What the source document did not know: most of the ledger already exists

The draft reads as a greenfield plan. It is not. Per §1.1 the code outranks the document, and the
code says all four of its phases are **partly shipped**. Recording this before the table, because
planning to build `influencer_commissions` a second time is the expensive version of this mistake:

| Draft item | Reality on disk |
|---|---|
| 4.1 commission ledger | `influencer_commissions` exists (`V8__coupons_marketplace_commissions.sql`), one row per attributed sale, with `clawed_back` and `void` already in the status vocabulary |
| 4.2 accrual lifecycle | `dao/finance/application/CommissionService.java` enforces the transitions and emits `CommissionAccrued`/`CommissionApproved`; re-approving a `paid` commission throws rather than silently re-opening a settled obligation |
| 4.3 batched payout run | `influencer_payouts` is **already a batch** — one row settles many commissions to one creator over a period. `webe/finance/application/PayoutService.java`, `CommissionsPayoutsController.java` and `PayoutsPage.jsx` exist |
| 1.1/1.2 Stripe + PayPal | `webe/payout/PayoutProvider.java` + `PayoutProviderRegistry.java` exist, with `ManualPayoutProvider` as the shipped default. The SPI javadoc already names Stripe Connect / PayPal / Wise as the intended implementations, and `PR-28` already made `payoutId` the idempotency key **specifically** so a Stripe `Idempotency-Key` or PayPal `sender_batch_id` slots in without redesign |
| 3.2 order-line attribution | `influencer_sale_attributions.order_line_id` exists (`V2`) — the column the draft calls non-negotiable is already there |
| 3.2 webhook idempotency | Shipped as `PR-29` (`V35`, `attribution/api/WebhookController.java`) |

**So the honest framing is: this is not four phases of construction. It is one genuinely missing
piece — creator onboarding, so money has somewhere to go — plus filling in bodies behind ports that
were built to receive them.** That is a much smaller number than the draft implies, and it is the
main reason the answer in §11.5 is what it is.

### 11.2 What is genuinely missing

| ID | Item | Size | Note |
|---|---|---|---|
| ~~`PR-47`~~ | **Stripe Connect Express onboarding** — ✅ **SHIPPED 2026-09-02.** Stripe-hosted pages for identity, bank and tax — do not build these screens. `stripe_account_id` + `payouts_enabled` on the creator record, surfaced to the brand. Trigger onboarding at **invitation**, not at payout time | 5 | The one truly blocking item. `PR-40`..`PR-44` already own creator invitation (§10.2), so this is a step added to a flow being built, not a new flow. **Shipped:** `V52` adds `stripe_account_id`, `payouts_enabled` and `payout_status_checked_at` to `creator.creators` with a PARTIAL unique index on the account id; `CreatorPayoutOnboardingPort` with a no-op `manual` default and `StripeConnectOnboarding` behind `payout.onboarding.provider=stripe`, reusing the billing Stripe key. **The two facts are kept apart everywhere** — an account existing is not a creator being payable, and an unreadable status is UNKNOWN rather than "not payable", leaving the stored value alone and reporting `stale`. **No tax column:** Stripe owns that fact and a stale copy of a tax status is worse than none. A NARROW `PATCH /creators/{id}/payout-account` was needed because the existing `PUT` overwrites every field — a two-field payload through it would blank a creator's handle and metrics. **Verified by:** `CreatorPayoutOnboardingServiceTest` (8 tests) and V52 against real Postgres. **The Connect approval clock is what this starts; it is not switched on** (`provider=manual` ships as the default) |
| `PR-48` | **`StripeConnectPayoutProvider`** — a `@Component` implementing the existing `PayoutProvider`, passing `payoutId` as the `Idempotency-Key`. Selected by a `web-experience.payout.provider` property, per the §1 registry pattern | 2 | Small *because* `PR-28` and the SPI already landed |
| ~~`PR-49`~~ | **Tax collection up front** — ✅ **SHIPPED 2026-09-02.** W-9 (US) / W-8BEN (non-US) before payment, never at payout time. Cumulative paid tracked against the $600 1099-NEC threshold; **Stripe Connect's tax reporting is enabled rather than 1099 generation built** — that would mean owning IRS deadlines, correction workflows and per-state variation. **Shipped:** `V53` adds `tax_form_required_at`, `tax_form_on_file_at`, `tax_form_kind` to `creator.creators` plus a PARTIAL index `idx_payouts_creator_year` (planner confirmed using it); `sumPaidBetween` + `GET /influencer-payouts/paid-total` in the DAO; `TaxThresholdService` and `GET /api/creators/{id}/tax-status` + `POST /api/creators/{id}/tax-form` in the BFF. **Per creator PER BRAND per CALENDAR year** — each brand is its own payer, not the platform as aggregator. **The gate sits in `createPayout`** after the amount is known (the question is whether THIS payment crosses the line) and before the payout row is written, so a blocked payout leaves no `processing` row. **Two rails, two authorities:** on Connect, Stripe collected the form and `payouts_enabled` decides — a brand cannot tick a box that overrides it; on the manual rail the brand's own record is the only evidence. **An unreadable total does NOT block a payout** — failing closed would stop a brand paying someone because the DAO hiccuped. **No document storage:** recording that a form arrived, not holding W-9s carrying SSNs. **Verified by:** `TaxThresholdServiceTest` (14 tests), V53 and the threshold arithmetic against real Postgres. **Not deployed — needs v1.0.54 (V52 and V53 are migrations)** | 3 | The sequencing is the whole point: chasing paperwork while someone is asking where their money is is the worst possible order |
| `PR-50` | **PayPal Payouts fallback.** Per-creator payout preference: Stripe default, PayPal (email only) as the escape hatch — lower friction, worse economics | 3 | Justified by a batch-level failure, not a per-creator one: one creator who will not finish Connect onboarding stalls the agency's whole batch |
| `PR-51` | **Hold period → `cleared`.** The draft's `pending → cleared → paid` needs one new state between the two that exist; the hold matches the merchant's return window (usually 30d) | 2 | `approved` today is an operator decision, not a time-based clearing. These are different things and both are wanted |
| `PR-52` | **Append-only ledger discipline.** Today `CommissionService` mutates `status` in place. A refund must write a **negative entry**, never edit the original row | 3 | **The difference between a tool an agency trusts and a spreadsheet with extra steps.** Also the only item here that changes shipped code rather than adding to it, so it carries the most regression risk |
| `PR-53` | **Campaign agreement + e-signature.** Deliverables, rate/structure, commission base, hold period, payment terms. Dropbox Sign or DocuSign; click-to-accept acceptable at small amounts | 5 | Gated on `OP-21` being settled, not on it shipping |
| `PR-54` | **Attribution priority waterfall.** Code redeemed on the order → deterministic credit; else tracked-link click inside the window → click-ID credit; else unattributed | 3 | Extends the existing coupon spine rather than replacing it |
| `PR-55` | **Fraud and code-leakage controls.** Self-referral (billing email matches creator), same-IP clusters, abnormal AOV spikes; per-campaign rotation, redemption caps, velocity flags | 4 | Vanity codes reach Honey and RetailMeNot within days — a *when*, not an *if* |
| `PR-56` | **Payout hygiene.** Minimum payout threshold (~$50) to avoid dust payments, fixed schedule (net-30, monthly), every payout traceable to its ledger entry ids | 1 | Traceability is mostly there already via `payout_id` on the commission row |
| ~~`OP-21`~~ | **Write the commission base down once, identically, in three places** — ✅ **SHIPPED 2026-09-02, and it was a LIVE DEFECT rather than a documentation task.** — the agreement, the UI, and the ledger logic: **net revenue after discount, excluding tax and shipping** | 0.5 | The cheapest item here and the highest ratio of dispute-prevention to effort. `net_amount` and `discount_amount` already existed on `influencer_sale_attributions`; what was missing is that they meant the same thing everywhere — and they did not. **`AttributionService.computeCommission` took the percentage on the GROSS sale while `netAmount` was stored as `sale - discount` on the very same row**, so a 20%-off order paid the creator a share of money the ledger's own net figure said the brand never received. No one had complained because there are no paying brands; the first dispute would have been unanswerable, because the product asserted both numbers at once. Now: **net revenue after discount, excluding tax and shipping** — tax and shipping because neither is revenue the brand keeps. The same sentence appears in the javadoc, in the coupon form's label (`% of net revenue`, not the ambiguous `% of sale`) and in a note rendered beside the field where the rate is chosen. A discount exceeding the sale pays zero rather than a negative commission. **Verified by:** `CommissionBaseTest` (6 tests) — and by restoring the gross calculation to confirm two of them fail. |

**Total: ~34.5 days**, of which `PR-47` (5d) is the only hard blocker on anything downstream.

### 11.3 Decisions this needs before the first campaign, not after

These are **not** build items; they are choices that must be settled or the ledger encodes an
accident. Recorded here because they will otherwise be re-litigated mid-implementation:

- First-touch vs last-touch attribution, and the window (7/30/60 days)
- Split rules when a code and a *different* creator's link both fire
- Refund/clawback behaviour — the schema already permits `clawed_back`; nothing decides *when*

### 11.4 Out of scope — decided, not deferred by accident

| Item | Why not |
|---|---|
| Creator vetting / audience-quality scoring | Deep, expensive problem the funded platforms compete on. Not the wedge |
| Smart-contract settlement | Sales and money both live off-chain. An oracle feeding our own numbers keeps every trust assumption while adding crypto complexity. Revisit only for cross-border payouts at volume |
| Multi-currency / global payouts | US-only for now — consistent with Decision 8 (no UK/EU sales until VAT is registered), not a separate call |

### 11.5 The honest argument — do not complete this before taking it to brands

**Take `PR-47` plus what is already shipped. Not §11.2 in full.** The reasoning is the same shape as
§10.4 rather than a new one:

**The demo already exists.** §11.1 is the finding that matters commercially. The batched payout run
the draft calls *"the demo moment — the thing worth showing an agency in the first five minutes"* is
**already built**, end to end, against `ManualPayoutProvider`. An agency watching a screen cannot see
whether the money left via Stripe or via the operator's bank app ten minutes later; they see one
approve replacing an afternoon of individual transfers. That demo is available **now**, and it is
the one that tests willingness to pay.

**The draft says so itself**, and its closing warning must not be lost in the act of filing it into a
roadmap: *"the risk with a document like this one is that it becomes a month of building instead of a
month of asking."* Filing it here without this subsection would do precisely that.

**§2.1 has not changed.** The remaining gap is commercial, not technical. Zero subscribers. ~34 days
of payout engineering is ~34 days not spent on `PR-02` (activation) or on the ten conversations.

**And the sequencing is inverted for a pre-revenue product.** Phases 2–4 of the draft encode answers
— attribution window, split rules, hold period, commission base — that ten agency conversations would
*give* us. Building them first means guessing and then rebuilding. The draft's own parallel track says
Phases 2–4 "should be shaped by what the conversations turn up"; that is not a nicety, it is the
argument for not building them yet.

**What is worth building before the conversations, and why each:**

1. **`OP-21`** (0.5d) — the commission base, written down identically in three places. You cannot
   have a credible pricing conversation without stating precisely what you take a percentage *of*,
   and getting it wrong in front of an agency is worse than not demoing.
2. **`PR-47`** (5d) — Stripe Connect onboarding, and the honest argument for starting it slightly
   early rather than on demand is that Connect approval has an external lead time, like `PR-04`.
   Nothing downstream of it can move money.

**Total before the conversations: ~5.5 days, not ~34.**

**The trigger to build the rest is an agency saying "we would pay for this, but we need X"** — where
X names one of `PR-49`..`PR-56`. Then build X, not the phase X belongs to. If no agency names any of
them, that is information too, bought for 5.5 days instead of 34.

**The caveat against my own answer:** if the first conversation is with an agency already running
paid campaigns that wants to switch *this month*, then `PR-47` + `PR-48` + `PR-49` (10d) become
urgent together — you cannot pay a US creator more than $600 in a year without the tax form on file.
~~**Do not promise a payout date before `PR-49` exists.**~~ `PR-49` now exists (2026-09-02), so a payout date may be promised — but note the gate is only as good as the rail: on Connect it defers to `payouts_enabled`, which `PR-47` ships switched OFF (`provider=manual`).

---

## Appendix A — Old → new ID crosswalk


**Audited 2026-09-02.** Several right-hand IDs never had rows of their own — they were allocated to
name a *deferred or declined* body of work, which is a legitimate use of a stable ID and is why they
are kept. What was NOT legitimate, and is now fixed, is `M5.1 → PR-34`: that pointed at Apollo
enrichment, which has nothing to do with hosting, and the pairing was plausible enough to leave a
reader believing S3-hosted per-brand landing pages had shipped. They have not. Landing pages are
rendered per request and served through Caddy, because personalising a page per creator is not
something a static object can do — see the corrected rows below.

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
| M3.2–3.5 | — | **DANGLING:** `PR-10` has no row. Shopify remains deferred; `docs/shopify-integration-plan.md` is the live document |
| M4.1–4.4 | `PR-02` | Activation |
| M5.1 | — | **Real hosting target — DANGLING.** Pointed at `PR-34`, which is Apollo enrichment and always was; the two were never related. Landing pages are rendered per request by `LandingService.renderPublic` and served through Caddy — verified 2026-09-02 — because personalisation per creator (`/s/{slug}/{creator}` substitutes that creator's code at render time) is not something a static object can do. There is no S3-hosted, brand-prefixed page path, shipped or half-shipped |
| M5.2/5.3/5.5 | — | **DANGLING.** `PR-03` has no row and never did. The wildcard cert and subdomain routing exist for the UI hosts (`static-site-cdn.tf`), not for per-brand page hosting; asset SERVING is `AssetsController` + `PR-45`'s `S3AssetStorage`. The per-brand-subdomain design is §9.2 and is unbuilt |
| M5.4 | `PR-23` | Real DNS verification |
| M5.6 | `PR-24` | Expiry warnings |
| M6.1–6.3, 6.5 | `PR-27` | Metrics infrastructure |
| M6.4 | `PR-27b` | IG/TikTok adapters (Meta-gated) |
| M6.6 | `PR-35` | Tiered refresh scheduler |
| M7.1–7.5 | ~~`PR-60`~~ | Custom domains — the UI shipped 2026-09-02. ACME issuance and the public serving lookup remain; `PR-11` never had a row of its own |
| M8.1/8.2/8.4 | — | **DANGLING:** `PR-12` has no row. Agency depth remains deferred |
| M8.3 | `PR-28` | Payout idempotency |
| U1 | `PR-25` | Creator record page |
| U2 | `PR-13` | Pagination (deferred) |
| U3/U5/U6/U7 | `PR-14` | UI depth (deferred) |
| U4 | `PR-26` | Metrics provenance |
| LPB A–E, G, H | `PR-36` | Landing-page builder |
| LPB F | `PR-15` | Social publishing (declined) — **superseded 2026-08-27** by `PR-45` (share kit) and `PR-46` (platform API), kept under distinct IDs so the declined thing stays visibly declined |
| DDD 0–6 | `PR-37` | Architecture migration (complete) |
| GAPS Tier 1 #1/#2 | `PR-20` / `IN-02` | Billing; deploy |
| EXEC §1 "the tax" | `PR-16` | Duplication cleanup (deferred) |
| Payout draft Phases 1–4 | `PR-47`..`PR-56`, `OP-21` | Payments and creator onboarding (§11) — the draft's four phases do not map one-to-one, because §11.1 found most of Phase 4 already shipped |
| — | `PR-57` | **New ID, no predecessor.** Meta webhook receiver, deferred at birth — opened 2026-09-03 after verifying no `hub.challenge`/`X-Hub-Signature` handler exists and that `business_discovery` (pull) needs none. Deliberately NOT in the `instagram_basic` submission; see Stage 4 |

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
