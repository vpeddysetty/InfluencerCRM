# Landing Page Builder — Implementation Roadmap

**Date:** 2026-08-02
**Responds to:** [LandingPageBuildPRD.md](LandingPageBuildPRD.md)
**Grounded in:** [ddd-roadmap.md](ddd-roadmap.md), [contracts/README.md](contracts/README.md),
[identity-signup-alignment.md](identity-signup-alignment.md)

This is an alternative execution plan for the ICLPB PRD, written against what this codebase
actually is. The PRD's product goals are adopted almost in full. Its **stack recommendation is
declined**, for a reason worth stating plainly.

---

## 1. The PRD's central factual error

> *"Seamless integration with an existing **C# Influencer CRM** (C:\AI\InfluencerCRM)"*
> *"**Alternative Recommendation: .NET / C# Centric** (for teams reusing C:\AI\InfluencerCRM)"*

There is no C# in this repository.

```
*.cs files:    0
*.java files:  345
*.py files:    8
```

The platform is **Java 21 / Spring Boot** across ten services, **React 19 + Vite** across seven
micro-frontends, **PostgreSQL 15** with nine context schemas, and a **Python FastAPI** agent service
that already runs LangGraph against OpenAI. Both of the PRD's recommended paths — "Node.js +
NestJS" and ".NET 7 + ASP.NET Core" — would introduce a third and fourth backend runtime to a
platform that has deliberately spent six migration phases consolidating on one.

Everything downstream of that error needs re-deciding: NestJS, Blazor, HotChocolate, Azure
Service Bus, SQL Server and Azure Cognitive Search are all answers to a question this codebase does
not have.

**What survives the correction** is most of the PRD, because the product thinking is sound and
largely independent of stack: role-aware building, responsive previews, AI onboarding, domain
provisioning, Kanban sync, social publishing. Those are adopted below.

---

## 2. What already exists

The PRD reads as though it were greenfield. A material portion is built and tested.

| PRD component | Status here | Where |
|---|---|---|
| Landing Page entity (`PageID`, `Blocks`, `Stage`) | **Built, schema to be replaced** | `content.landing_templates` — table reused, `blocks` reshaped by GrapesJS (§6.2) |
| Hosted public page rendering | **Built** | `LandingService.renderPublic` — HTML-escaped, per-creator personalization |
| Page preview | **Partly built** | `LandingService.previewTemplate` — no device framing yet |
| Landing page analytics | **Built** | `content.landing_page_views` — referrer, user agent, coupon attribution |
| Influencer CRM + relationship graph | **Built** | `creator.creators` per-brand rows, `creator.campaign_creators` |
| Kanban board | **Built** | `workflow.workflow_boards` / `_stages` / `_cards`, custom stages, drag-to-place |
| Creator portal & login | **Built** | Stage 4 of the identity roadmap — `creator_identities`, claim/approve |
| Creator ↔ brand relationship | **Built** | `creator_identity_links`, confirmed per brand |
| AI service with LLM orchestration | **Built** | `agent_service` — FastAPI + LangGraph + OpenAI, has `/content/draft` |
| Vector search for AI retrieval | **Built** | pgvector, `mapping.mapping_examples` |
| Role-based permissions | **Built** | 6 roles × 32 permissions, enforced server-side |
| Domain provisioning / SSL / CDN | **Not built** | — |
| Drag-and-drop visual editor | **Not built** | Blocks are stored; nothing edits them visually |
| Device preview framing | **Not built** | — |
| Creator metrics from platform APIs | **Not built** | Needs app registrations — the long pole (§10.2) |
| AI classification & scoring | **Not built** | `creators.brand_safety_score` column exists, unused |
| Per-brand vetting rules | **Not built** | No `vetting_status` yet — Phase C2 |
| Authenticity / fake-follower signals | **Not built** | Coarse in-house signal first; vendor on complaint ([analysis](group2-build-vs-buy.md)) |
| Post-approval health monitoring | **Not built** | No metric history kept today — Phase C3 |
| Social publishing | **Not built** | `coupon:push` exists for marketplaces, not social posts |
| Publishing as a creator's handle | **Not built** | Most security-sensitive item here — Phase F |
| Brand ↔ creator co-editing | **Not built** | Foundation exists — confirmed `creator_identity_links` (§6.1) |
| Real-time (simultaneous) collaboration | **Not built** | Deferred, possibly never — Phase G.6 |
| Welcome package automation | **Not built** | — |

**Roughly half the PRD is already delivered.** Planning it as six greenfield phases would rebuild
working, tested code.

---

## 3. Stack decisions

Each PRD recommendation, judged against this codebase rather than in the abstract.

| PRD says | Decision | Why |
|---|---|---|
| Next.js + Vercel | **Decline** | Seven React+Vite micro-frontends already exist behind a Module Federation gateway. The builder becomes `mf_content`, an eighth remote. Adding Next.js means two React toolchains and a second deployment model for one page. |
| Tailwind CSS | **Decline** | There is an established design system (`Mds*` components, `App.css`). Introducing utility-first CSS for one surface fragments it. |
| **GrapesJS** | **Adopted — decided** | The PRD's strongest recommendation. A visual editor is genuinely expensive to build, `landing_templates.blocks` is already a JSONB document column ready to hold its output, and it embeds in the existing React shell without adopting a host framework. Since existing templates are discarded (§6.2), it defines the block schema outright. |
| NestJS / ASP.NET backend | **Decline** | Content is already a bounded context with a service (`:8450`) and a schema. New endpoints belong there. |
| GraphQL (Apollo / HotChocolate) | **Decline for now** | Every context speaks REST through the BFF, and the DPS proxy assumes it. GraphQL would need a parallel auth and tenancy path. Revisit if editor state genuinely needs it. |
| PostgreSQL | **Already the case** | — |
| Redis + BullMQ | **Adopt Redis, decline BullMQ** | Redis is deployed and holds sessions. BullMQ is Node-only. Use the existing `shared.domain_events` outbox for async work. |
| S3 for assets | **Adopt** | The one genuine infrastructure gap. Assets are not in Postgres today and should not be. |
| OpenAI / LangChain | **Already the case** | `agent_service` runs LangGraph against OpenAI. Extend it; do not add a second AI service. |
| Elasticsearch | **Defer** | pgvector plus Postgres indexes cover current scale. Revisit on evidence, not anticipation. |
| ClickHouse / BigQuery | **Defer** | `landing_page_views` is a normal table with normal volume. |
| Yjs real-time collaboration | **Defer to last** | The PRD says this itself. Highest complexity, lowest proven demand. |
| Cloudflare / Route53 registrar APIs | **Adopt** | Correct for domain provisioning. Registrar behind a port so it can be swapped. |
| Sentry / Prometheus / Grafana | **Adopt** | No observability exists. Worth doing regardless of this feature. |

**Net:** the PRD proposes ~15 new technologies. This plan adopts four — GrapesJS, S3, a registrar
API, and observability — and reuses the rest of the platform.

---

## 4. Where this fits the DDD design

The PRD's components map onto existing bounded contexts. Two new contexts are genuinely warranted;
everything else belongs somewhere that already exists.

```
content     (:8450)  ← Landing pages, blocks, templates, previews, assets
                       EXISTING. Absorbs: builder, breakpoint editor, versions.

publishing  (:8451)  ← NEW. Domains, DNS, SSL, deploys, social posts.
                       Justified: it owns external side effects with their own
                       lifecycle, credentials and failure modes. Putting DNS
                       propagation retries inside the content service would make
                       page editing depend on a registrar being up.

creator     (:8446)  ← Creator records, AI scores, vetting state
                       EXISTING. Absorbs: handle parsing results, vetting stages.

workflow    (:8444)  ← Kanban boards, cards, stages
                       EXISTING. Absorbs: stage-driven automation rules.

identity    (:8445)  ← Accounts, members, creator identities
                       EXISTING (Stages 1-4 complete). No change needed.

agent_service        ← AI orchestration
                       EXISTING. Absorbs: handle parsing, scoring, layout hints.
```

### Bidirectional stage sync — decided, and the risk it carries

**Decision (2026-08-02): the Kanban board is writable.** Dragging a card changes the page stage, and
changing the page stage moves the card. Both directions are supported.

I argued for a one-way projection and was overruled; recording that here because the reasoning
still governs *how* this is built. Two writable state machines that must agree is the shape that
eventually produces a card in *Published* for a page still in draft. Since both directions are
required, the divergence has to be engineered against explicitly rather than hoped away.

**Four rules make it safe.**

**1. Content owns the transition, always.** A drag does not write `workflow_cards.stage_id` and then
tell content. It issues a *command* that content validates and may refuse:

```
drag card → PUT /api/landing-pages/{id}/stage { to: "Published" }
                        │
             content validates the transition
                        │
        ┌───────────────┴───────────────┐
     accepted                        refused
        │                               │
  LandingPageStageChanged          409 + reason
        │                               │
  workflow moves the card         card snaps back
```

The card never holds a stage the page does not have, because the card only ever moves in response to
the event. The UI applies the move optimistically and reverts on refusal — standard, and it keeps
the drag feeling instant without making the board authoritative.

**2. Not every transition is legal.** *Draft → Published* skipping review is a product decision, not
a drag gesture. Content holds the allowed-transition map and enforces it, so the same rule applies
whether the change came from the board, the builder, or the API.

**3. Some transitions need more than a stage change.** Moving to *Published* triggers a deploy; it
must fail if there is no domain bound or the page has never been rendered. Refusing at the command
boundary is why rule 1 matters: a card that had already moved would need compensating, and
compensating a UI drag is far worse than refusing it.

**4. Events carry the source, and the card write is idempotent.** `LandingPageStageChanged` names
the origin (`board` | `builder` | `api`) so a board-originated change does not echo back as a second
move. Combined with an idempotency key per transition, a duplicated or retried event is a no-op
rather than a loop.

**Reconciliation, because rules 1–4 still assume delivery.** The outbox is at-least-once, not
exactly-once. A nightly job compares `landing_templates.stage` against the linked card's stage and
re-emits for any mismatch, with content winning. This is cheap insurance and the difference between
"they diverged and someone noticed in a demo" and "they diverged and self-healed overnight".

**Cost of this decision, stated plainly:** Phase D grows by the transition map, the command
endpoint, the optimistic-revert UI, source tagging, and the reconciliation job. That is real work
the projection design would not have needed. It buys a board that behaves the way people expect a
Kanban board to behave, which is a legitimate reason to pay for it.

---

## 5. Roadmap

Sequenced so each phase ships something usable, and so the expensive, least-certain work comes after
the cheap, certain work. Sizes are relative, not estimates.

### Phase A — Visual editor on the existing model ▸ largest value, no new infrastructure

| Step | Change | Where |
|---|---|---|
| A.1 | Embed GrapesJS in a `LandingBuilderPage`, reading/writing the existing `blocks` JSONB | `InfluencerContentUI` |
| A.2 | Custom blocks: creator signup, product, UGC, CTA, brief | React components registered with GrapesJS |
| A.3 | Role-aware palette — blocks filtered by `can(permission)` | Existing gateway `can()` |
| A.4 | Device preview: iframe at 390 / 820 / 1440 breakpoints | Reuses `previewTemplate` |
| A.5 | Version history — append-only `landing_template_versions` | `content` schema |

**GrapesJS is decided** (2026-08-02), not a recommendation to be re-litigated. The spike in §8 is
therefore about *reconciling its document model with the existing `blocks` JSONB*, not about
choosing a builder.

**Deliberately not in A:** breakpoint-specific overrides, AI layout suggestions, collaboration.
A.1–A.4 make the builder usable; the rest is refinement on top of a proven surface.

**Done when:** a brand owner builds a page by dragging blocks, previews it at three widths, and
publishes it to the existing `/s/{slug}` hosted route — which already works.

---

### Phase B — Asset library ▸ small, unblocks everything visual

| Step | Change |
|---|---|
| B.1 | S3-compatible object storage behind an `AssetStoragePort` (MinIO locally) |
| B.2 | `content.assets` — brand-scoped metadata, key, content type, dimensions |
| B.3 | Upload with presigned URLs; images never proxied through the BFF |
| B.4 | Asset picker in the builder |

**Why before AI or domains:** every visual feature needs somewhere to put images. Doing it now
avoids base64-in-JSONB, which is the shortcut that gets taken under deadline and is painful to undo.

---

### Phase C — Creator onboarding via platform APIs ▸ extends a service that already exists

**Decided: facts come from platform APIs, not from the model.** Follower counts, engagement and
verification are read from Instagram Graph, TikTok, YouTube Data and Facebook Graph. The LLM does
only what it is good at — classifying niche, summarising content themes, flagging risk language —
and never invents a metric.

That distinction is the whole design. An LLM asked for a follower count will produce a confident,
plausible, wrong number, and a brand would make spend decisions on it.

| Step | Change | Where |
|---|---|---|
| C.1 | `SocialProfilePort` + one adapter per platform, behind rate-limit and retry | `creator` service |
| C.2 | `POST /creators/resolve-handle` — platform, handle → **verified metrics** | via the port |
| C.3 | `POST /creators/classify` — niche, themes, risk flags from profile + recent captions | `agent_service` |
| C.4 | Persist metrics and classification separately, with provenance and fetch time | `creator` schema |
| C.5 | Signup block writes a lead scoped to the page's brand | `content` → `creator` |
| C.6 | Graceful degradation: no API access → manual form, lead still created | — |

**Provenance is a column, not a comment.** Every populated field records whether it came from an API
or a model, and when. A brand looking at a 2 %-engagement creator needs to know if that is measured
or guessed, and a metric fetched four months ago is not a current fact.

**C.1 shares its dependency with Phase F.** The same app registrations, OAuth apps and review
processes serve both onboarding and publishing. Whoever starts the Instagram app review should do it
once, for both — and that lead time is likely longer than the code, which is the argument for
starting it in week one regardless of phase order.

**C.6 is not optional.** Platform APIs fail: tokens expire, rate limits bite, private accounts
return nothing, and app review may not have landed yet. A creator signing up must never be blocked
because an API was down — capture the manual form, mark the metrics unverified, and enrich later.

**The columns already exist and are unused** — `brand_safety_score`, `safety_notes`, `niche`,
`content_categories`, `audience_demographics`, `average_views`, `last_active_at`. This phase
populates them rather than adding a parallel model.

**Tenancy, confirmed:** one `creator.creators` row per (creator, brand) pair. A creator signing up to
five brands has five rows, and none of those brands sees another's negotiated rate, safety notes or
score. This is the existing model and it stays. If the creator later claims their rows in the portal,
Stage 4's `creator_identity_links` connects them behind one login without merging the rows.

---

### Phase C2 — Per-brand vetting ▸ each brand vets its own way

The PRD treats vetting as one fixed pipeline. It is not: an agency running luxury beauty and one
running gaming have genuinely different thresholds, and hard-coding either is wrong for the other.

| Step | Change |
|---|---|
| C2.1 | `creator.vetting_status`: `lead → pending → under_review → approved → rejected` |
| C2.2 | `creator.vetting_rules` — per-brand, ordered, each a condition plus an action |
| C2.3 | Rule evaluation on lead creation and on metric refresh |
| C2.4 | Rules UI: build, reorder, enable/disable, and **dry-run against existing creators** |
| C2.5 | `creator.vetting_events` — every automated and manual decision, with the rule that fired |
| C2.6 | Review queue for anything a rule did not resolve |
| C2.7 | `CreatorApproved` → welcome package (brief, guidelines, asset access) |
| C2.8 | `creator.creator_quality_reports` — a brand disputes a creator's audience quality |

**Rules may reject and advance. They may never approve.** Decided 2026-08-02.

```
follower_count < 5 000              → auto-reject
risk_flags contains 'adult'         → auto-reject
niche not in brand's allowed list   → auto-reject
score > 80 AND verified             → advance to "Ready for review"
anything else                       → human review queue
```

The asymmetry is deliberate and worth defending. Rejection is reversible — a creator can be
reinstated, and at worst a brand missed one partnership. Approval grants access to briefs, assets and
eventually money; getting it wrong automatically is far more expensive, and the reasoning behind an
approval is what a brand will be asked to justify.

**Every decision is recorded with the rule that caused it.** `vetting_events` is what makes "why was
I rejected?" answerable. Automated rejection without an audit trail is how a platform ends up unable
to explain itself to a creator, or to a regulator.

**C2.4's dry-run matters more than it looks.** A rule that silently rejects 80 % of a brand's
existing creators should be discovered before it is switched on, not after. Evaluating a draft rule
against current data and showing the counts is cheap and prevents the worst failure mode here.

**Auto-approval is not built, and the schema does not anticipate it.** If it is wanted later, that is
a deliberate decision with its own review — not a flag someone finds and flips.

**C2.8 is small and easy to skip, and should not be.** It records what our own signal said at the
time of the complaint, which turns each dispute into a labelled example of the signal being wrong.
That is both the trigger for engaging a vendor and the only ground truth available for tuning
in-house thresholds — see [group2-build-vs-buy.md](group2-build-vs-buy.md) §5.1. Without it, "wait
for complaints" degrades into someone half-remembering that a few brands grumbled.

#### What a vetting rule can read — the attribute catalogue

Researched against what Modash, HypeAuditor, Upfluence and CreatorIQ actually expose (sources at the
foot of this section). Four groups, because they have very different acquisition costs and very
different legal weight.

**Group 1 — Reach and activity.** Cheap, available from every platform API, uncontroversial.

| Attribute | Column | Notes |
|---|---|---|
| Follower count | `follower_count` ✅ | Exists |
| Engagement rate | `engagement_rate` ✅ | Exists. `(likes + comments) / followers` |
| Average views | `average_views` ✅ | Exists. The honest metric for video platforms |
| Last active | `last_active_at` ✅ | Exists. Dormancy is a real disqualifier |
| Post frequency | **new** | Posts per 30 days |
| Follower growth trend | **new** | 30/90-day delta — feeds Phase C3 |

**Group 2 — Authenticity.** The differentiator. This is what separates a vetting tool from a
contact list, and the industry has converged on it.

| Attribute | Column | Notes |
|---|---|---|
| Audience quality score | **new** `audience_quality_score` | 0–100, the HypeAuditor/Modash convention. ≥70 is the common acceptance floor |
| Estimated fake-follower % | **new** `fake_follower_pct` | Industry studies put the average near 37 %, and highest in the 100k–500k tier — the band brands most want |
| Engagement authenticity | **new** `engagement_authenticity` | Engagement that does not track follower growth is the clearest fraud signal |
| Follower-growth anomalies | **new** `growth_anomaly_flags` | Sudden spikes without corresponding content |
| Audience-type mix | **new**, inside `audience_demographics` | Real / mass-follower / suspicious / influencer. An audience >40 % other influencers is a red flag |

**Group 3 — Audience demographics.** Approved for rule use (decision #14). Stored in the existing
`audience_demographics` JSONB rather than as columns, because the shape varies per platform and this
avoids a migration per field.

| Attribute | Shape |
|---|---|
| Age brackets | `{"13-17": 0.04, "18-24": 0.38, …}` |
| Gender split | `{"female": 0.62, "male": 0.36, "other": 0.02}` |
| Geography | Country and city percentages |
| Language | Primary audience languages |
| Interests / affinities | Platform-derived affinity categories |

**Group 4 — Brand safety and history.** Content risk, distinct from audience fraud.

| Attribute | Column | Notes |
|---|---|---|
| Brand safety score | `brand_safety_score` ✅ | Exists, unused |
| Risk flags | **new** `risk_flags` | Adult, alcohol, gambling, politics, controversy |
| Recent brand mentions | **new** | Competitor detection — Modash offers a 180-day window |
| Disclosure compliance | **new** | Whether past sponsored posts were properly disclosed. A creator who does not disclose is a regulatory liability, not just a brand-fit question |
| Content-niche alignment | `niche`, `content_categories` ✅ | Exist |

#### Demographic rules are allowed, with two constraints

Decision #14 permits rules against Group 3. Two things follow, and they are engineering
requirements rather than caveats:

**These are audience attributes, not the creator's.** A rule reads *"this creator's followers are
70 % aged 18–24"*, never *"this creator is 22"*. The distinction is the difference between campaign
targeting and screening a person by protected characteristics. **No creator-personal demographic
fields should exist on the schema at all** — the safest way to guarantee a rule cannot filter on
them is for the data never to be collected.

**Every demographic rejection is auditable.** C2.5 already records the rule that fired. Given
demographic rules, that record is what answers a regulator or a creator asking why they were
declined. This is not extra work — it is the reason C2.5 was specified before this decision was
taken.

#### Where the data comes from

Group 1 and Group 3 come from platform APIs (Phase C). **Groups 2 and 4 largely do not exist in
platform APIs at all** — fake-follower estimation and brand-safety classification are the products
that Modash and HypeAuditor sell.

That is a build-or-buy decision this roadmap does not resolve, and it is worth surfacing now because
it affects sequencing (§10.10):

- **Buy** — integrate a vetting API. Fastest, industry-grade accuracy, and a per-lookup cost that
  scales with creator volume.
- **Build** — derive coarse signals from raw API data. Cheaper per lookup, materially less accurate,
  and follower-quality analysis is genuinely hard.
- **Defer** — ship Groups 1, 3 and 4 first, add Group 2 once volume justifies it.

I would **defer, then buy** — analysed in full in
[group2-build-vs-buy.md](group2-build-vs-buy.md). The decisive point is not cost but access:
fake-follower detection means examining the followers, and no platform exposes a follower list.
Groups 1, 3 and 4 support useful rules on day one; coarse in-house signals (comment quality,
engagement consistency, growth anomalies) can be built from data we legitimately hold.

**Sources:**
[ContentGrip — how to vet influencers](https://www.contentgrip.com/how-to-vet-influencers/) ·
[ContentGrip — fraud detection](https://www.contentgrip.com/influencer-marketing-fraud-detection/) ·
[InfluenceFlow — vetting tools and authenticity checks](https://influenceflow.io/resources/influencer-vetting-tools-and-authenticity-checks-essential-guide-for-2026/) ·
[Archive — creator vetting tools](https://archive.com/blog/creator-vetting-tools-screen-influencers) ·
[Modash vs HypeAuditor](https://www.impulze.ai/post/modash-vs-hypeauditor) ·
[Influencer analytics tools compared](https://influencerfee.com/blog/influencer-analytics-tools-comparison/)

---

### Phase C3 — Creator health monitoring ▸ the relationship after approval

Vetting is a gate; this is what happens after someone is through it. A creator approved at 50k
followers who quietly declines to 5k is a live problem, and today nothing would notice.

| Step | Change |
|---|---|
| C3.1 | Scheduled metric refresh per creator — cadence by tier, not uniform |
| C3.2 | `creator.creator_metric_snapshots` — append-only history, one row per fetch |
| C3.3 | Decline detection against configurable per-brand thresholds |
| C3.4 | `CreatorHealthAlert` event → alert surfaced to the owning brand |
| C3.5 | Alert queue: acknowledge, snooze, or act — with the reason recorded |
| C3.6 | Trend view on the creator record — the graph, not just the current number |

**Alerts inform a decision; they never take one.** Decided 2026-08-02. A drop in standing raises a
flag for the brand or agency, and a human decides whether to keep, pause or end the relationship.
Nothing auto-revokes.

The reasoning is the same asymmetry as auto-approval, and it is stronger here. A creator mid-campaign
has delivered work, may be owed money, and may have declined other offers to take this one. Silently
revoking their access because a number moved would be both a commercial and a contractual mistake —
and metrics dip for legitimate reasons: platform algorithm changes, a break, a seasonal niche, or
one viral post inflating the previous baseline.

**Snapshots, not overwrites.** C3.2 keeps history rather than updating `follower_count` in place.
Without it there is no trend, no way to distinguish a slide from a correction, and no evidence when a
brand asks why an alert fired. The current value stays on `creators` for fast reads; the series lives
alongside it.

**What warrants an alert** — per brand, since a 20 % drop means different things at 5k and 5M:

```
follower_count       ↓ >20 % over 30 days
engagement_rate      ↓ >30 % over 30 days
last_active_at       > 45 days ago
audience_quality     ↓ below the brand's floor
risk_flags           any new flag appears        ← always alerts, regardless of thresholds
```

**Alert fatigue is the failure mode to design against.** An alert nobody reads is worse than no
alert, because it looks like coverage. Hence per-brand thresholds rather than platform defaults,
snooze on C3.5, and grouping — one digest for a roster, not fifteen notifications.

**Reuses the existing outbox.** `CreatorHealthAlert` joins `CommissionAccrued` in
`shared.domain_events`; no new infrastructure.

**Cadence, since this costs API quota.** Refreshing 10,000 creators daily is a lot of calls to buy.
Weekly for active-campaign creators, monthly for the rest, on demand from the creator record — and
because Group 2 attributes may come from a paid vendor (§C2), refresh frequency is a direct cost
line, not just a scheduling choice.

---

### Phase D — Stage-driven automation ▸ bidirectional, so larger than it looks

| Step | Change |
|---|---|
| D.1 | `content.landing_templates.stage` with the PRD's eight values |
| D.2 | Allowed-transition map, enforced in content (§4 rule 2) |
| D.3 | `PUT /api/landing-pages/{id}/stage` — the single command path for every origin |
| D.4 | Publish `LandingPageStageChanged { pageId, brandId, from, to, source }` |
| D.5 | Workflow subscribes: move card, create task, notify. Idempotent on transition key |
| D.6 | Configurable stage → board-stage mapping per brand |
| D.7 | Board drag issues the command; optimistic move, revert on refusal (§4 rule 1) |
| D.8 | Nightly reconciliation: page stage wins, re-emit on mismatch (§4) |

**D.2, D.7 and D.8 exist only because the board is writable.** They are the cost of the
bidirectional decision, itemised so it is visible rather than absorbed into "sync".

**Done when:** dragging a card to *Creator Assigned* moves the page to that stage and creates the
assignment task; dragging it to an illegal stage snaps back with a reason; and a page changed in the
builder moves its card without a second write path.

---

### Phase E — Domains and hosting ▸ highest external risk, deliberately late

| Step | Change |
|---|---|
| E.1 | New `publishing` service (`:8451`) + `publishing` schema |
| E.2 | `DomainRegistrarPort`, Cloudflare adapter first |
| E.3 | Connect-existing-domain flow with DNS validation and clear instructions |
| E.4 | Provision-new-domain wizard (purchase via registrar API) |
| E.5 | ACME/Let's Encrypt SSL issuance and renewal |
| E.6 | Deploy pipeline: render → object storage → CDN invalidate |
| E.7 | Rollback to any prior published version |

**Why last of the build phases:** it depends on real money, real DNS propagation and a real
registrar contract, and it is the only phase that cannot be fully tested locally. Everything before
it ships without it — pages are already reachable on `/s/{slug}`.

**Registrar behind a port** because the choice between Cloudflare and Route 53 is a business
decision that may change, and it must not reach into the deployment engine.

#### Decided 2026-08-02

**The brand pays the registrar directly; the platform hosts.** The domain is purchased on the
brand's own account and the platform never resells it. This removes a great deal: no reseller
agreement, no markup logic, no billing integration blocking Phase E, and no awkward question about
who owns a domain when a brand leaves. E.4's wizard therefore *guides and validates* a purchase
rather than transacting one — E.3 (connect existing) and E.4 (guided provision) become the same flow
with a different starting point, which is simpler than the PRD's two paths.

**Hosting is the platform's, and is not optional.** Pages are rendered, stored and served by the
InfluenCRM hosting engine behind its CDN. A brand cannot point a domain at their own server, which
keeps SSL renewal, cache invalidation and rollback in one place where they can be made to work
reliably.

**No permanent free tier — two months.** `/s/{slug}` is time-limited, not a forever home. That is a
real constraint on the build, not just pricing:

- Pages need a `hosting_expires_at`, and the renderer must handle expiry deliberately — a clear
  "this page has expired" response, never a 404 that looks like a bug or a page that silently keeps
  serving.
- Expiry warnings belong in the outbox as scheduled events, not a cron job scanning tables.
- Promotions extend `hosting_expires_at`; the model is an expiry date the platform can move, not a
  boolean `is_free`. A campaign-based promotion is then a bulk update, not a schema change.

**Two months from first publish, not from signup.** Decided 2026-08-02. A brand that signs up,
explores for six weeks and then publishes should get the full window on the thing being trialled —
starting the clock at signup means the trial expires while they are still learning the builder.
`hosting_expires_at` is therefore set on the first `Published` transition and left null before it.

**Warn at 30, 7 and 1 days**, to the brand owner, via the outbox. My default unless you want
otherwise (§10.7 previously; now settled).

**At expiry the page is unpublished, not deleted.** It stops serving at its public URL and returns a
clear expiry response; the row, its blocks and its assets stay. Re-publishing after payment is then a
stage change rather than a rebuild — deleting a brand's work because a trial lapsed is the kind of
thing that ends a customer relationship permanently.

**Consequence:** Phase E is no longer optional. With a permanent free tier it would have been
"nice to have"; with expiry, every brand that wants a page to outlive its trial needs a domain
bound. That raises E's priority relative to F, and the sequencing in §7 reflects it.

---

### Phase F — Social publishing ▸ genuinely hard, smallest surface

| Step | Change |
|---|---|
| F.1 | `SocialPublisherPort`; Instagram Graph first |
| F.2 | OAuth token storage with refresh — **per brand and per creator identity** |
| F.3 | Publishing-identity resolution from page context (below) |
| F.4 | Creator consent flow: explicit, per brand, revocable |
| F.5 | Scheduled publish via the outbox, with retry and backoff |
| F.6 | `SocialPostPublished` → stage advance |
| F.7 | Post metrics ingestion, attributed to the posting identity |

**The hard part is not the code.** Every platform has review processes, rate limits, token expiry
and policy constraints. Start with one platform, prove the token lifecycle, then add others.

#### Who publishes — decided 2026-08-02

**The page's context decides the handle.**

| Page context | Posts as |
|---|---|
| Brand + campaign | The **brand's** handle |
| Brand + campaign + creator | The **creator's** handle |

This matches how influencer marketing actually works — a creator's audience follows the creator, and
a brand posting to its own feed is a different act from a creator endorsing a product. It is also the
single most security-sensitive feature in this roadmap, because it means **the platform posts to a
creator's personal account**.

**What that requires, none of it optional:**

**Consent is per brand, explicit, and revocable.** A creator authorising posts for Brand A has not
authorised Brand B. The grant is scoped to the (creator, brand) pair — the same grain as
`creator.creators` and `creator_identity_links` — and revoking it stops future posts immediately.

**A creator's token is not a brand's token.** They live in separate stores with separate lifecycles.
A brand user must never be able to read, export, or reuse a creator's credential for anything but a
post that creator's consent covers.

**The creator sees what will be posted before it goes out.** Publishing to someone's personal
account without them seeing the content first is not defensible, whatever the contract says. This
lands as an approval step, not a notification after the fact.

**Resolution is server-side and re-derived at publish time**, never passed by the caller. A request
that could name its own posting identity is a request that could post as any creator.

```
publish(pageId)
   │
   ├─ page has a confirmed creator link? ──► creator identity
   │        └─ requires: consent for this brand, valid token, content approved
   │
   └─ otherwise ─────────────────────────► brand identity
            └─ requires: content:publish
```

**Failure is explicit.** No consent, expired token, or unapproved content means the publish is
refused with a reason — never a silent fallback to the brand's handle. Substituting identities
because the intended one was unavailable is exactly the kind of "helpful" behaviour that publishes
the wrong thing to the wrong audience.

**Sequencing note:** F.4's consent flow depends on Stage 4's creator portal, which exists. Creators
already have a login and confirmed per-brand links, so consent has somewhere coherent to live.

---

### Phase G — Brand ↔ creator co-editing ▸ the collaboration the PRD actually asked for

Split from the old "collaboration" phase because §6.1 made co-editing a committed feature rather
than an optional one. G.1–G.3 are in scope; G.4 remains deferred.

| Step | Change |
|---|---|
| G.1 | `content.landing_page_collaborators` — page, creator identity, rights, granted-by |
| G.2 | Invite a confirmed creator to co-edit; revoke; list. Brand side, `content:write` |
| G.3 | Creator-portal builder route: the same GrapesJS surface, scoped to pages they collaborate on |
| G.4 | Block-level comments |
| G.5 | Presence indicators |
| G.6 | Yjs CRDT simultaneous editing — **deferred, possibly never** |

**Two people editing at different times is not the same problem as two people editing at the same
instant.** G.1–G.3 deliver the former, which is what "co-create" means in practice for a landing
page: a brand drafts, a creator adds their section, the brand publishes. Version history (A.5)
already makes that safe by making overwrites recoverable.

G.6 solves simultaneous editing and costs a CRDT, a WebSocket tier and a new class of bug. The PRD
itself says to defer it, and comments plus version history cover most of what teams actually need.
Revisit only if users report losing each other's work — that is the signal, not anticipation.

**Publishing is not a collaborator right** (§6.1). A creator can shape the page; releasing it to a
domain or a social account stays with the brand.

---

### Phase H — Observability ▸ start now, in parallel

Sentry, Prometheus, Grafana. Not sequenced after the others: it should begin immediately and
independently. The platform currently has **no error tracking and no metrics**, which is a gap
regardless of whether the builder ships.

---

## 6. What I would cut from the PRD

Stated plainly, because a roadmap that adopts everything is not a plan.

| PRD item | Recommendation |
|---|---|
| **AI layout optimizer** | Cut for now. "AI suggests mobile improvements" is a demo feature; a correct mobile-first layout engine makes it unnecessary. |
| **Network condition preview** | Cut. Browser devtools do this better, and it is not a builder's job. |
| **Elasticsearch, ClickHouse** | Cut until there is evidence. Both are answers to scale problems this platform does not have. |
| **GraphQL** | Cut. A second API paradigm needs its own auth, tenancy and error handling. |
| **Blazor** | Cut — it follows from the C# error. |

### 6.1 Decided: co-editing only, no creator-authored pages

**Every landing page is owned by a brand.** A creator may be granted edit access to a specific page
and co-edit it; they can never create a page of their own.

This is a scope decision, and it removes real work:

- No page ownership model beyond `brand_id` — pages stay tenanted exactly like every other row.
- No creator-owned domains, so Phase E only ever provisions on behalf of a brand. No moderation
  question about what a creator publishes under the platform's infrastructure, and no billing
  question about who pays for their domain.
- No "creator templates" as a separate template class. The PRD's three template types collapse to
  two: **brand** and **campaign**.
- `content` gains no second tenancy rule. A page has one owning brand, always.

**What it costs:** a creator with no brand relationship has nothing to build. That is consistent
with the rest of the platform — a creator is someone brands work with, not an independent tenant.

**How access is granted.** Reuse Stage 4's `creator_identity_links` rather than inventing a second
mechanism: a creator may be invited to co-edit a page only if they hold a **confirmed** link to a
`creator.creators` row owned by that page's brand. The brand already approved that link, so
page-level access is a narrowing of an existing relationship rather than a new grant.

```
creator_identity_links (confirmed, brand B)
        │
        └──► may be invited to co-edit pages owned by brand B
```

A new `content.landing_page_collaborators` table records which pages, with what rights
(`comment` | `edit`). Revoking the identity link revokes page access with it — one place to cut off
a creator, not two.

**Permissions.** No new `account_role` is created; a creator is not an account member. Page-level
rights are checked against the collaborator row on the creator-portal path, the same way
`CreatorPortalController` already gates `collaborations`. Brand-side users continue to use
`content:write` / `content:publish`.

**Publishing stays a brand action.** A collaborator may edit and comment; moving a page to
*Published* requires `content:publish`, which only account members hold. A creator cannot publish to
a brand's domain or social accounts.
| **"Creator builds their own landing pages"** | **Cut — decided 2026-08-02.** Creators co-edit a brand's page and never author a standalone one. See §6.1. |

### 6.2 Decided: existing landing templates are discarded

**This is a new landing page design, and the current `landing_templates` rows are not migrated.**
GrapesJS's document model becomes the block schema; nothing has to reconcile with the hand-built
structure now in the table.

What this removes: no `BlockDocumentMapper`, no dual renderer, no compatibility mode, and no risk of
a half-migrated table where some pages open in the builder and others do not.

**Handling of the existing rows** — a decision to make before Phase A ships, not after:

- The rows are development and demo data, so the simplest correct answer is to drop them in a
  migration and let the builder create fresh pages.
- If any page is genuinely live at a `/s/{slug}` someone has shared, it should be marked
  `status = 'legacy'` and served read-only rather than deleted out from under a live link.

I would drop them. The table has a `uq_landing_templates_campaign` constraint, so leaving stale rows
would also block creating a new page for those campaigns — a real obstacle, not just untidiness.

**Verify before dropping**, because "brand new design" is a statement about the design, not
necessarily about production traffic:

```sql
SELECT lt.public_slug, lt.status, count(v.id) AS views
  FROM content.landing_templates lt
  LEFT JOIN content.landing_page_views v ON v.campaign_code_id IS NOT NULL
 GROUP BY lt.public_slug, lt.status;
```

Zero views everywhere makes this trivially safe.

---

## 7. Sequencing

```
  platform app registration ═══════════════════════════════► start NOW (§10.2)
                    ╲                          ╲
H (observability) ───╲────────────────────────► ╲ continuous
                      ╲                          ╲
A (builder) ─► B (assets) ─► D (automation) ─► E (domains)
      │            ╲                                 │
      │             ╲                          F (social) ◄── needs the apps
      └─► G (co-editing)                             ▲
                                                     │
      C (onboarding) ─► C2 (vetting) ─► C3 (health) ──┘
              ▲
              └── needs the apps
```

**Two independent tracks.** A → B → D → E is the page's life: build it, fill it, move it through
stages, publish it to a domain. C → C2 → C3 is the creator's: onboard, score, vet, then watch. They meet only at
Phase F, where a vetted creator publishes a finished page. Different people can run them in parallel
— C touches `agent_service` and the `creator` schema and never opens the builder.

**Start the platform app registrations this week**, whatever else is being built. Instagram and
TikTok review can take weeks, both C and F block on it, and it is the one dependency no amount of
engineering speed can compress (§10.2).

**E is now mandatory, not optional.** With no permanent free tier, a page that outlives its trial
needs a bound domain — so domains moved ahead of social publishing in priority. Previously E could
have slipped indefinitely because `/s/{slug}` was forever.

**G stays early.** Co-editing needs only a working builder and Stage 4's confirmed creator links,
which already exist. It is about *making* a page, not releasing one, so it does not queue behind
domains and publishing.

---

## 8. First step

GrapesJS is decided, so the spike is no longer *whether* to use it — it is the one thing about that
decision that could still cost weeks if assumed wrong:

> **Embed GrapesJS in `InfluencerContentUI`, load an existing `landing_templates.blocks` document,
> edit it, save it back, and render it through the existing `/s/{slug}` route.**

**The stakes dropped considerably** once existing templates were declared disposable (§6.2). GrapesJS
now defines the block schema outright rather than having to reconcile with one, so the spike proves
the *embed* — mount, edit, save, render — not a migration.

| Outcome | Consequence |
|---|---|
| Editor mounts, saves, and `/s/{slug}` renders it | Phase A is mostly assembly |
| Renderer needs rework for GrapesJS output | Contained: one service, one method |

**Do this before A.2.** Custom blocks are only worth building once the storage shape is settled;
building them first risks writing them twice.

One caution on the embed itself: GrapesJS owns its own DOM and is not a React component. It needs to
be mounted in an effect against a ref and torn down explicitly, or it will leak editors across route
changes — a common failure when embedding it in a React shell.

---

## 9. Decisions taken

All confirmed 2026-08-02.

| # | Question | Decision | Where |
|---|---|---|---|
| 1 | Creator-owned pages? | **No.** Co-edit only; every page is brand-owned | §6.1 |
| 2 | Visual builder? | **GrapesJS**, embedded in the existing React shell | §3, Phase A |
| 3 | Existing landing templates? | **Discarded.** New design; GrapesJS defines the schema | §6.2 |
| 4 | Where do creator facts come from? | **Platform APIs** for metrics; LLM only classifies | Phase C |
| 5 | Does AI auto-approve? | **No.** Rules may reject and advance, never approve | Phase C2 |
| 6 | Vetting rules? | **Per brand.** Each brand defines its own | Phase C2 |
| 7 | Creator signing up to many brands? | **One row per (creator, brand)**, N brands | Phase C |
| 8 | Kanban direction? | **Bidirectional.** Cards are writable | §4 |
| 9 | Who pays the registrar? | **The brand or agency**, directly. No reselling | Phase E |
| 10 | Who hosts? | **InfluenCRM.** Brands cannot self-host | Phase E |
| 11 | Free tier length? | **Two months**, from first publish. Extensible by promotion | Phase E |
| 12 | Who publishes to social? | **Page context decides** — brand, or creator when a creator is on the campaign | Phase F |
| 13 | Monitor creators after approval? | **Yes.** Decline raises an alert; a human decides | Phase C3 |
| 14 | Can rules read audience demographics? | **Yes**, audience attributes only — never the creator's own | Phase C2 |
| 15 | Group 2 authenticity signals? | **Own signal first**; vendor only when brands complain | [analysis](group2-build-vs-buy.md) |

Two were argued against and overruled; both are recorded with the reasoning intact, because it
governs how they get built rather than whether:

- **#8 bidirectional Kanban** — I proposed a one-way projection. Since both directions are wanted,
  §4 specifies the four rules and the reconciliation job that keep two writable state machines from
  diverging, and Phase D itemises the extra work.
- **#12 posting as a creator** — not argued against, but the most security-sensitive decision here,
  so Phase F specifies per-brand consent, separate token stores, and creator pre-approval of content
  rather than treating it as a routing detail.

---

## 10. Open questions

One left, and it does not block Phase A.

### The long pole — and the only thing worth acting on this week

**10.1 — Platform developer app registration. → instructions in
[platform-app-registration.md](platform-app-registration.md)**

Phase C reads metrics from Instagram Graph, TikTok, YouTube Data and Facebook Graph; Phase F
publishes through the same apps. Meta's review takes **2–4 weeks and resets if a reviewer requests
changes**; TikTok's takes **5–10 business days** with no expedited option.

**This is the longest lead time in the roadmap and none of it is code.** Started now, approvals land
roughly when Phase C needs them. Started when the code is ready, a month of finished work sits idle.
Phases A, B, D, E and G are unaffected and proceed in parallel.

The linked document has the step-by-step for each platform, the exact permissions to request, the
reviewer-facing description of our use case, and a status tracker.

### Answered — kept for the reasoning

**10.3 — Build, buy, or defer the authenticity signals (Group 2)? → analysed, see
[group2-build-vs-buy.md](group2-build-vs-buy.md)**

**Recommendation: defer, then buy** — and the reason is firmer than cost. Fake-follower detection
requires examining the followers, and **Instagram exposes no follower-list endpoint at all**. The
training data cannot be legitimately obtained, so this is not a problem engineering speed solves.
Buying costs $3,600–16,200/yr at realistic volume; a Claude-assisted build would cost ~$18k–31k in
year one *and only if the data existed*, with $15–25k/yr maintenance after.

Claude compresses roughly 75 % of the build's engineering and none of its data problem.

**Decided 2026-08-02: start with our own signal; engage a vendor when brands complain about follower
quality.** Threshold is three complaints in a quarter, or one on a creator our signal rated clean.
C2.8 captures those complaints as structured data, without which the trigger cannot fire.

### Settled since the last revision

- Existing `landing_templates` rows → verified as test fixtures only (12 rows, 17 views, all from
  one E2E run on 2026-08-01) and cleared by
  `schema/migrations/2026_08_02_landing_builder_reset.sql`, which guards against real customer data
  at run time rather than trusting the check
- Free-tier length, start point, warnings and expiry behaviour → Phase E
- Demographic rules and their two constraints → Phase C2
- Post-approval monitoring and what warrants an alert → Phase C3
- Which vetting attributes to capture, researched against competitors → Phase C2
- Group 2 build vs buy, and the trigger for revisiting → [group2-build-vs-buy.md](group2-build-vs-buy.md)
