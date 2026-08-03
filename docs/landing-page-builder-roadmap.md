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

**No permanent free tier.** `/s/{slug}` is time-limited, not a forever home. That is a real
constraint on the build, not just pricing:

- Pages need a `hosting_expires_at`, and the renderer must handle expiry deliberately — a clear
  "this page has expired" response, never a 404 that looks like a bug or a page that silently keeps
  serving.
- Expiry warnings belong in the outbox as scheduled events, not a cron job scanning tables.
- Promotions extend `hosting_expires_at`; the model is an expiry date the platform can move, not a
  boolean `is_free`. A campaign-based promotion is then a bulk update, not a schema change.

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
      C (onboarding) ─► C2 (vetting) ─────────────────┘
              ▲
              └── needs the apps
```

**Two independent tracks.** A → B → D → E is the page's life: build it, fill it, move it through
stages, publish it to a domain. C → C2 is the creator's: onboard, score, vet. They meet only at
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
| 11 | Permanent free tier? | **No.** Time-limited, extensible by promotion | Phase E |
| 12 | Who publishes to social? | **Page context decides** — brand, or creator when a creator is on the campaign | Phase F |

Two of these were argued against and overruled; both are recorded with the reasoning intact, because
it governs how they get built rather than whether:

- **#8 bidirectional Kanban** — I proposed a one-way projection. Since both directions are wanted,
  §4 specifies the four rules and the reconciliation job that keep two writable state machines from
  diverging, and Phase D itemises the extra work.
- **#12 posting as a creator** — not argued against, but it is the most security-sensitive decision
  here, so Phase F specifies per-brand consent, separate token stores, and creator pre-approval of
  content rather than treating it as a routing detail.

---

## 10. Open questions

Far fewer than before. Ordered by when an answer is needed.

### Needed before Phase A ships

**10.1 — Confirm the existing `landing_templates` rows can be dropped.**
§6.2 assumes they are demo data. The verification query is there; if any slug has real traffic, the
answer is `status = 'legacy'` and read-only rather than deletion. This is a five-minute check that
prevents deleting something a partner has linked to.

### Needed before Phase C starts — and the long pole

**10.2 — Which platform developer apps exist today, and under whose account?**
Phase C reads metrics from Instagram Graph, TikTok, YouTube Data and Facebook Graph. Every one needs
a registered app and, for Instagram and TikTok, a review that can take weeks. **This is very likely
the longest lead time in the whole roadmap and it is not code.** If no apps are registered, someone
should start that this week regardless of which phase is being built, because Phase C *and* Phase F
both block on it.

**10.3 — Which platform first?**
Instagram is assumed. Each additional platform is its own API shape, token lifecycle, rate limit and
policy surface.

### Needed before Phase C2 ships

**10.4 — What can a vetting rule actually read?**
The rule engine's power is bounded by its inputs. Follower count and niche are obvious; is a brand
allowed to write rules against audience demographics — age, gender, location? That is legitimate for
campaign fit and also the kind of automated filter that attracts scrutiny. Worth deciding
deliberately, and worth recording per brand, since C2.5's audit trail is what answers "why was I
rejected?".

**10.5 — Do rules re-run on refreshed metrics?**
A creator approved at 50k followers who drops to 5k: does a rule re-evaluate and reject them? I would
say no — re-run rules only for creators still in a pending state, and surface a *flag* for approved
creators rather than silently revoking access someone is mid-campaign on.

### Needed before Phase E ships

**10.6 — How long is the free tier, and what happens at expiry?**
"A few months" needs a number, because `hosting_expires_at` is a column. And the behaviour at expiry
is a product decision with a real user consequence: a clear expiry page, a redirect, or an
unpublish. My recommendation is an expiry page that stays crawlable and tells the visitor where the
brand went — a hard 404 makes the platform look broken rather than the trial look over.

**10.7 — Who is warned before expiry, and when?**
Scheduled events are easy; the policy is the question. 30/7/1 days to the brand owner would be my
default.

### Needed before Phase F ships

**10.8 — Is creator consent per campaign or standing per brand?**
Phase F assumes standing per brand and revocable. Per campaign is more conservative and more
friction. This matters because it is the difference between a creator authorising a relationship and
authorising every future post in it.

### Not blocking anything

**10.9 — Are the eight page stages fixed, or per-brand?**
Boards already support custom stages. If page stages are also customisable, D.6's mapping becomes
user-configured rather than a default. Fixed is fine to start; this is easy to add later and hard to
remove.
