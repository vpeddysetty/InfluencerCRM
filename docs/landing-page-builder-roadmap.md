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
| Landing Page entity (`PageID`, `Blocks`, `Stage`) | **Built** | `content.landing_templates` — `blocks jsonb`, `theme jsonb`, `public_slug`, `status` |
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
| AI handle parsing & scoring | **Not built** | `creators.brand_safety_score` column exists, unused |
| Social publishing | **Not built** | `coupon:push` exists for marketplaces, not social posts |
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
| **GrapesJS** | **Adopt** | The PRD's strongest recommendation. A visual editor is genuinely expensive to build, `blocks jsonb` is already the persistence model, and GrapesJS is embeddable in the existing React shell without adopting its host framework. |
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

### The one architectural decision worth arguing

**Landing page stages must not be a second workflow engine.** The PRD lists eight page stages
(`Draft → Review → … → Published → Performance Tracking`) *and* a Kanban board that syncs with them.
Implemented literally, that is two state machines that must agree forever.

They should not sync. The page's `status` is the **fact**; the Kanban card is a **projection** of
it. Stage changes publish a domain event, and workflow reacts:

```
content:  LandingPageStageChanged { pageId, brandId, from, to }
            → workflow subscribes → moves the card, generates tasks
            → publishing subscribes → deploys when to = Published
```

This is the pattern `shared.domain_events` already exists for (`CommissionAccrued`,
`CommissionApproved`). Bidirectional sync between two writable state machines is how you get a card
in "Published" for a page that is still a draft.

**Consequence for the PRD's acceptance criteria:** *"Changing landing page stage moves Kanban card
automatically"* is satisfied. *"Moving the Kanban card changes the page stage"* is deliberately
**not** offered — the card is a view, and making it writable recreates the divergence.

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

### Phase C — AI creator onboarding ▸ extends a service that already exists

| Step | Change | Where |
|---|---|---|
| C.1 | `POST /creators/parse-handle` — platform, handle → structured profile | `agent_service` |
| C.2 | Scoring: audience fit, niche classification, risk flags | `agent_service`, LangGraph |
| C.3 | Persist to existing columns — `brand_safety_score`, `niche`, `audience_demographics` | `creator` schema |
| C.4 | Vetting state: `lead → pending → review → approved → rejected` | New `creators.vetting_status` |
| C.5 | Signup block writes a lead scoped to the page's brand | `content` → `creator` |
| C.6 | `CreatorApproved` event → welcome package | `shared.domain_events` |

**The columns already exist and are unused** — `brand_safety_score`, `safety_notes`, `niche`,
`content_categories`, `audience_demographics`. This phase populates them rather than adding a
parallel model.

**Non-negotiable:** an AI score is **advisory**. Approval stays a human action with a recorded
`created_by_user_id`. Auto-approving on a model's output makes an unexplainable decision about
someone's livelihood.

**Tenancy note:** a creator signing up through Brand A's page becomes a `creator.creators` row owned
by Brand A. That is the existing per-brand model, and it is why the same person can sign up to two
brands without either seeing the other's terms. If they later claim their rows in the portal, Stage
4's link table connects them.

---

### Phase D — Stage-driven automation ▸ mostly wiring, high leverage

| Step | Change |
|---|---|
| D.1 | `content.landing_templates.stage` with the PRD's eight values |
| D.2 | Publish `LandingPageStageChanged` on transition |
| D.3 | Workflow subscribes: move card, create task, notify |
| D.4 | Configurable stage → board-stage mapping per brand |
| D.5 | Card links back to the page (read-only projection — see §4) |

**Done when:** moving a page to *Creator Assigned* moves its card and creates the assignment task,
with no code path writing both.

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

---

### Phase F — Social publishing ▸ genuinely hard, smallest surface

| Step | Change |
|---|---|
| F.1 | `SocialPublisherPort`; Instagram Graph first |
| F.2 | OAuth token storage with refresh, per brand |
| F.3 | Scheduled publish via the outbox, with retry and backoff |
| F.4 | `SocialPostPublished` → stage advance |
| F.5 | Post metrics ingestion |

**The hard part is not the code.** Every platform has review processes, rate limits, token expiry
and policy constraints. Start with one platform, prove the token lifecycle, then add others.

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

---

## 7. Sequencing

```
H (observability) ──────────────────────────────────────────► continuous

A (builder) ──► B (assets) ──► C (AI onboarding) ──► D (automation)
      │                                                 │
      └──► G (co-editing) ◄───────────────────┐   E (domains)
                                              │         │
                                              │   F (social)
                                              └── needs C for confirmed links
```

A and B are the critical path: nothing visual works without them. C is independently valuable and
could run in parallel by a different pair of hands, since it touches `agent_service` and the
`creator` schema rather than the builder.

**G moved earlier.** Co-editing now depends only on A (a working builder) and on Stage 4's confirmed
creator links, which already exist. It no longer waits behind domains and social publishing — those
are about *releasing* a page, while co-editing is about *making* one, and the PRD treats
brand↔creator collaboration as core rather than a finishing touch.

---

## 8. First step

GrapesJS is decided, so the spike is no longer *whether* to use it — it is the one thing about that
decision that could still cost weeks if assumed wrong:

> **Embed GrapesJS in `InfluencerContentUI`, load an existing `landing_templates.blocks` document,
> edit it, save it back, and render it through the existing `/s/{slug}` route.**

The bet is that GrapesJS's document model round-trips the `blocks` JSONB already in production. Three
outcomes, each worth knowing in week one:

| Outcome | Consequence |
|---|---|
| Round-trips cleanly | Phase A is mostly assembly |
| Round-trips with a mapping layer | Add a `BlockDocumentMapper`; contained, but real work |
| Cannot round-trip | Existing pages need migration or a legacy renderer — see §10.10 |

**Do this before A.2.** Custom blocks are only worth building once the document model is settled;
building them first risks writing them twice.

One caution on the embed itself: GrapesJS owns its own DOM and is not a React component. It needs to
be mounted in an effect against a ref and torn down explicitly, or it will leak editors across route
changes — a common failure when embedding it in a React shell.

---

## 9. Decisions taken

| # | Question | Decision | Date |
|---|---|---|---|
| 1 | Creator-owned pages? | **No.** Co-edit only; every page is brand-owned (§6.1) | 2026-08-02 |
| 2 | Visual builder? | **GrapesJS**, embedded in the existing React shell (§3, Phase A) | 2026-08-02 |

---

## 10. Open questions

Ordered by how early an answer is needed.

### Blocking Phase C

**10.1 — Does an AI score ever auto-approve a creator?**
My assumption is **no**: the score is advisory and approval is a human action with a recorded actor.
Confirming matters because auto-approval makes an unexplainable decision about someone's income, and
it changes what has to be logged for an audit.

**10.2 — What does the AI actually get to read?**
"Creator pastes handle, AI interprets profile" is doable three ways, with very different costs:
scraping public pages (brittle, often against platform terms), official platform APIs (accurate,
needs app review per platform), or the LLM's own knowledge (fast, and confidently wrong about
follower counts). I would use platform APIs where a token exists and the LLM only for
classification — but that means Phase C depends on the same API access as Phase F.

**10.3 — Whose brand does a creator sign up to?**
A creator signing up through Brand A's page becomes Brand A's `creator.creators` row. If the same
person signs up to Brand B, that is a second, independent row — the existing per-brand model.
Confirming you want that, rather than one shared creator profile, because it is deliberate and
occasionally surprising.

### Blocking Phase D

**10.4 — Kanban direction.**
Confirming cards are a read-only projection of page stage, not a second way to drive it (§4). If you
do want cards to be writable, say so now — it changes the design from an event projection to
bidirectional reconciliation, which is substantially more work and carries the divergence risk I
flagged.

**10.5 — Are the eight page stages fixed or per-brand?**
Boards already support custom stages. If page stages are also customisable, the stage → board
mapping becomes user-configured rather than a default, and D.4 grows.

### Blocking Phase E

**10.6 — Who pays the registrar, and is there a markup?**
Determines whether Phase E needs a billing integration before it can ship at all. Connecting an
existing domain has no such dependency, which is why E.3 precedes E.4 — if billing is unresolved,
E.3 still ships.

**10.7 — What is the fallback host?**
Pages are reachable today at `/s/{slug}`. Is that the permanent free tier, or does every published
page eventually need a custom domain? Affects whether Phase E is optional or mandatory.

### Blocking Phase F

**10.8 — Which platform first, and does an app review already exist?**
Instagram is assumed. Each platform is its own review process, token lifecycle and policy surface.
If no developer app is registered yet, that lead time is likely longer than the code.

**10.9 — Publishing on whose behalf?**
The brand's social account, the creator's, or both? A creator authorising the platform to post to
their account is a materially different consent and token-storage problem from a brand doing so.

### Worth settling before Phase A ships

**10.10 — What happens to the existing hand-built landing pages?**
`content.landing_templates` already holds live rows with a `blocks` structure. If GrapesJS cannot
round-trip them, the options are a migration, a compatibility renderer, or accepting that old pages
are read-only. The §8 spike answers this, and it is the main thing I would want to know in week one.
