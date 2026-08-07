# Landing Page Builder — E2E Test Report

**Date:** 2026-08-05
**Scope:** Phases A, B, C and D of [landing-page-builder-roadmap.md](landing-page-builder-roadmap.md)

| Suite | Assertions | Result |
|---|---|---|
| [`tests/e2e_landing_builder.sh`](../tests/e2e_landing_builder.sh) — Phase A | 27 | all passing |
| [`tests/e2e_asset_library.sh`](../tests/e2e_asset_library.sh) — Phase B | 25 | all passing |
| [`tests/e2e_creator_onboarding.sh`](../tests/e2e_creator_onboarding.sh) — Phase C | 32 | all passing |
| [`tests/e2e_workflow_stage_identity.sh`](../tests/e2e_workflow_stage_identity.sh) — stage-rename fix | 16 | all passing |
| [`tests/e2e_stage_automation.sh`](../tests/e2e_stage_automation.sh) — Phase D | 36 | all passing |
| **Total E2E** | **136** | **all passing** |
| `mvn test` in `InfluencerWebExperience` (incl. ArchUnit) | 77 | all passing |

Run against the live stack (UI → BFF `:8081` → DAO `:8443` → PostgreSQL), not mocks. Every
assertion below was executed; the sanitizer assertions were additionally verified as unit
tests so the security boundary is covered at both levels.

---

## What was built

| Roadmap step | Delivered | Where |
|---|---|---|
| A.1 Embed GrapesJS, read/write `blocks` | **Done** (writes `document`, see §3) | `InfluencerContentUI/src/components/LandingBuilder.jsx` |
| A.2 Custom blocks (signup, product, UGC, CTA, brief) | **Done** — 7 blocks | `LandingBuilder.jsx` `BLOCKS` |
| A.3 Role-aware palette via `can(permission)` | **Done** | `LandingBuilder.jsx`, gated on `creator:write` |
| A.4 Device preview at 390 / 820 / 1440 | **Done** | GrapesJS device manager |
| A.5 Append-only `landing_template_versions` | **Done** + restore | `schema/migrations/2026_08_05_phase_a_landing_builder.sql` |
| — Coupon-free brand render path | **Added** (not in roadmap; see §2) | `LandingService.renderPublicBrandPage` |
| — HTML/CSS sanitization | **Added** (not in roadmap; see §1) | `LandingDocumentSanitizer.java` |

---

## 1. The security change the roadmap did not account for

The pre-existing renderer was safe *by construction*: it emitted a fixed set of tags from
typed blocks and HTML-escaped every dynamic value, so stored data could never become
markup. `LandingService`'s class comment states this explicitly.

**GrapesJS output is markup.** Serving it means escaping is no longer possible — escaping
would render the tags as literal text. The safety property therefore had to be rebuilt as
allow-list filtering, which is what `LandingDocumentSanitizer` does.

Two deliberate choices:

- **Sanitize on output, not on save.** Sanitizing at write would make the database the
  trust boundary, so anything written by another path (import, migration, direct DAO call,
  a future co-editor) would serve unfiltered. Filtering at render holds regardless of how
  the row got there.
- **Substitute tokens *before* sanitizing.** A coupon code containing markup is then
  neutralized by the same pass, rather than being injected into already-cleaned HTML.

### Two real defects the tests caught

Both were found by the unit tests before reaching E2E, and both would have been visible
to end users:

1. **`<h1>` was being stripped**, silently degrading every hero title to bare text.
2. **`<div>` was being stripped**, which would have flattened nearly every real builder
   page — a visual builder nests divs for layout.

Cause in both cases: jsoup's `Safelist.basicWithImages()` targets *user comments*, where
letting a commenter emit layout containers or an `<h1>` would wreck the host page. A
landing page **is** the document, so those tags are its primary structure. This is a
genuine trap in reusing a comment-oriented safelist for page content.

---

## 2. A gap in the roadmap's Phase A definition

The roadmap's Phase A "done when" says a brand *"publishes it to the existing `/s/{slug}`
hosted route — which already works."* Verified against the code, that was not accurate:

- The only public route was `/s/{slug}/{creator}`, which resolves a coupon by creator slug
  and **404s when none matches**.
- A brand could therefore build and save a page and have **no way to view it** until a
  creator coupon existed.

Confirmed empirically before any change was made — both `/s/{slug}/anyone` and `/s/{slug}`
returned 404 for a freshly saved page.

`renderPublicBrandPage` closes this. Creator/coupon tokens render as neutral placeholders
(`{{creator.name}}` → "our creators") rather than leaving raw braces on a public page, and
the view is recorded without a `campaign_code_id` rather than inventing one — a fabricated
coupon id would corrupt the coupon-level funnel reporting that table exists for.

---

## 3. Storage: `document` beside `blocks`, not instead of it

The roadmap (§6.2) decided GrapesJS "defines the block schema outright" since existing
templates were discarded. The implementation keeps a **separate `document` column** anyway:

- `document IS NULL` is the signal that a page has never been opened in the visual builder,
  which is exactly what the renderer branches on.
- The cutover is **per page and reversible**. No migration of page content, no dual-write,
  and a rollback is `drop column`.
- The legacy renderer is untouched, so pre-builder pages render byte-identically (A14c).

This costs one nullable column and buys the ability to ship without a content migration.

---

## 4. Test results

### Setup
| Item | Value |
|---|---|
| Brand account | `lb.brand.<stamp>@example.test` (fresh per run) |
| Campaign | `LB Campaign` → `78a87e53-477c-4c56-a70c-6d3724eca562` |
| Builder page slug | `c-78a87e53` |
| Legacy page slug | `c-98217bf5` |

### Feature: save & round-trip
| ID | Assertion | Result |
|---|---|---|
| A1 | Save a GrapesJS document | PASS (200) |
| A1b | A public slug is generated | PASS |
| A2 | `document.html` survives UI → BFF → DAO → PG → back | PASS |
| A2b | `stage` persists and is not silently defaulted | PASS |
| A3 | Stored as real `jsonb` (`jsonb_typeof` = `object`), not a quoted string | PASS |

> A2 is not redundant with A3. The BFF's `ResponseShapeService` strips unknown fields, so a
> column existing in the DB is not sufficient — without adding it to the response shape the
> builder could never read back its own document. That was a real blocker during the build.

### Feature: version history (A.5)
| ID | Assertion | Result |
|---|---|---|
| A4 | First save writes version 1 | PASS |
| A4b | Second save appends version 2 | PASS |
| A5 | Restore v1 returns 200 | PASS |
| A5b | v1 content actually comes back | PASS |
| A5c | Restore **appends** v3 — it does not delete v2 | PASS |
| A5d | A restored page comes back as `draft` | PASS |

> A5c and A5d encode two decisions. Rewinding by deleting later versions would destroy the
> record of what was undone, which is the one thing history exists for. And a page rolled
> back to an older draft must not inherit `published` from wherever it currently is —
> otherwise a restore silently republishes content to a live URL.

### Feature: public page security (the XSS regression suite)
| ID | Payload | Result |
|---|---|---|
| A6 | Published brand page renders at `/s/{slug}` | PASS (200) |
| A7 | `<script>alert(1)</script>` | stripped |
| A8 | `<img src=x onerror=alert(2)>` | stripped |
| A9 | `<a href="javascript:alert(3)">` | stripped |
| A10 | `<iframe src="https://evil.test">` | stripped |
| A11 | `<form><input name="password">` | stripped |
| A12 | `.a{width:expression(alert(4))}` | stylesheet dropped |
| A13 | `<h1>Safe</h1>` alongside all of the above | **preserved** |

> A13 is the assertion that makes the rest meaningful. A filter that drops everything is
> trivially "secure" and useless; the requirement is that hostile markup dies while
> legitimate page content survives in the same document.

CSS is dropped **wholesale** when anything dangerous is found rather than excised. Partial
repair of a stylesheet invites bypasses through nesting and encoding; an unstyled page is a
visible, recoverable, safe failure.

### Feature: backward compatibility
| ID | Assertion | Result |
|---|---|---|
| A14 | A block-only page has `document IS NULL` — nothing was backfilled | PASS |
| A14b | Legacy page still renders (200) | PASS |
| A14c | Rendered by the **original** typed-block renderer (`.wrap` container proves the path) | PASS |
| A14d | Legacy block content intact | PASS |

### Feature: access control
| ID | Assertion | Result |
|---|---|---|
| A15 | An unpublished page returns 404, not readable | PASS |
| A16 | An unknown `stage` is refused with 400 at the API | PASS |
| A17 | Another brand sees no versions for a campaign it does not own | PASS |

> A16 checks the API rejects before the DB does. The check constraint would also catch it,
> but as a constraint violation surfacing as a 500 three hops down — a readable 400 is the
> better failure.

---

## 5. Known limitations

- **One landing page per campaign** still holds — `uq_landing_templates_campaign` was left
  in place deliberately. Lifting it changes what a "campaign landing page" means and the
  slug/coupon assignment logic assumes one; that is a product decision, not a builder
  prerequisite.
- **Breakpoint-specific overrides are not implemented.** A.4 delivers preview *at* three
  widths; per-breakpoint style overrides were explicitly out of scope for Phase A.
- **The asset manager is disabled** in the builder (`upload: false`). Images are URL-only
  until Phase B provides object storage — deliberately, to avoid base64-in-JSONB, which the
  roadmap calls out as the shortcut that is painful to undo.
- **Concurrent saves race on `version_no`.** `COALESCE(max)+1` can be read by two savers at
  once; `uq_landing_versions_template_no` turns that into a visible constraint violation
  rather than two rows silently sharing a number. Not hit at one-editor-per-page, and the
  constraint is what makes the assumption failing loud rather than silent.
- **Version snapshots are best-effort.** A failure to write history never fails the user's
  save. Losing an auxiliary record is a far smaller harm than failing a save the user
  believes succeeded.

---

## 6. Operational note

Restarting a service after a **dependency** change requires a rebuilt classpath. Services
here run from `target/classes` with a fully-expanded `-cp`, so replaying the original
command line brings the service back up missing the new jar — this surfaced as
`NoClassDefFoundError: org/jsoup/safety/Safelist` during this work. Both helper scripts
used are in the session scratchpad; the distinction matters for any future dependency add.

Also worth knowing: **all landing traffic routes through the DAO monolith on `:8443`**, not
the extracted content service on `:8450`. Both carry byte-identical copies of
`LandingTemplate` and `LandingTemplateController`; changes must land in both, and the DAO
is the one that must be restarted for a change to take effect.

---

# Phase B — Asset library

**Suite:** [`tests/e2e_asset_library.sh`](../tests/e2e_asset_library.sh) — 25 assertions, all passing

## What was built

| Roadmap step | Delivered | Where |
|---|---|---|
| B.1 Object storage behind `AssetStoragePort` | **Done** — port + filesystem adapter | `AssetStoragePort.java`, `FilesystemAssetStorage.java` |
| B.2 `content.assets` brand-scoped metadata | **Done** | `schema/migrations/2026_08_05_phase_b_assets.sql` |
| B.3 Upload; images never proxied through the BFF | **Partly** — see below | `AssetService.java`, `AssetsController.java` |
| B.4 Asset picker in the builder | **Done** | `LandingBuilder.jsx` asset manager + upload button |

### Deviation: filesystem adapter instead of MinIO, and no presigned URLs

The roadmap specifies "S3-compatible object storage (MinIO locally)" with "upload with
presigned URLs; images never proxied through the BFF". What shipped is the **port** plus a
**filesystem adapter**, with bytes proxied.

The reasoning: Phase B blocks every visual feature, and neither MinIO nor an S3 SDK was
present. Gating it on standing up a container would have stalled everything downstream. The
port is the durable architectural decision — `S3AssetStorage` is a new class and a config
value, with no change to the service, the controller or the builder.

Presigned URLs are an S3 concept and arrive with the S3 adapter. The consequence today is
that bytes flow through the BFF, which is fine at local scale and is exactly what B.3 rules
out at production scale. **This is a known gap, not a completed step.**

Production note: the filesystem adapter writes to one machine's disk, so a second instance
would not see the files. It is a development adapter by design.

## Test results

### Setup
| Item | Value |
|---|---|
| Brand A | `ab.brand.<stamp>@example.test` (fresh per run) |
| Brand B | `ab.other.<stamp>@example.test` |
| Fixtures | 4×2 PNG (generated), HTML-named-`.png`, script-carrying SVG |

Fixtures are generated in the test rather than committed — a few dozen bytes of PNG is
clearer as code than as a binary blob, and it keeps the hostile fixtures obviously inert.

### Feature: upload and metadata
| ID | Assertion | Result |
|---|---|---|
| B1 | PNG upload accepted | PASS (200) |
| B1b | `id` and `url` returned | PASS |
| B2 | Width probed from the bytes (4) | PASS |
| B2b | Height probed from the bytes (2) | PASS |
| B2c | Content type from magic bytes, not the header | PASS |
| B3 | Storage key sits under the owning brand | PASS |
| B3b | Key is **not** the uploaded filename | PASS |

> B3b matters because two users uploading `hero.png` must not collide, and B3 because a
> caller-supplied key would be a path-traversal and a cross-tenant overwrite in one. Keys are
> generated by the adapter and never accepted from the caller.

### Feature: upload validation (what may be stored)
| ID | Payload | Result |
|---|---|---|
| B4 | HTML named `.png`, declared `image/png` | refused (415) |
| B5 | SVG containing `<script>` | refused (415) |
| B6 | Same bytes declared `text/html` | refused (415) |
| B7 | Only the one legitimate image persisted | PASS |

> B4 is the important one. A client controls its `Content-Type` completely, so the declared
> type is not evidence. Content is sniffed from magic bytes; without that, an HTML document
> would be stored and later served from the platform's own origin — reintroducing exactly
> the XSS hole the page renderer was hardened against in Phase A.
>
> SVG is excluded deliberately. It is a genuine image format and a script carrier, so
> "images only" would not be a sufficient rule on its own.
>
> B7 checks the rejections left no rows: a validation failure must not leak a half-created
> record.

### Feature: serving
| ID | Assertion | Result |
|---|---|---|
| B8 | Serves with no token | PASS (200) |
| B8b | Bytes byte-for-byte identical to the upload | PASS |
| B8c | Correct image content type | PASS |
| B8d | `X-Content-Type-Options: nosniff` set | PASS |

> Anonymous access is required, not an oversight: images on a public landing page must load
> for visitors who have no token. It is safe because keys are random UUIDs under a brand
> prefix — unguessable, and the only way to learn one is to be shown a page that already
> references it. `nosniff` is defence in depth on top of the upload-time sniffing.

### Feature: tenancy and traversal
| ID | Assertion | Result |
|---|---|---|
| B9 | Two path-traversal probes rejected | PASS (400) |
| B10 | Another brand's library is empty | PASS |
| B10b | The owning brand sees its asset | PASS |
| B11 | Another brand cannot delete by id | PASS (404) |
| B11b | The asset survived that attempt | PASS |
| B12 | Owner can delete their own asset | PASS (204) |
| B12b | Row removed | PASS |
| B12c | Bytes no longer served | PASS |

> B9 exists because `get`/`delete` take a key that came from a database row, and a row is not
> a trust boundary. The adapter re-resolves every key against the storage root and refuses
> anything that escapes it.

## Design notes worth recording

**Write order is deliberate and differs between upload and delete.** The object store and
Postgres cannot be committed together, so one failure mode has to be chosen:

- **Upload writes bytes first, row second.** A failure leaves an orphaned object — invisible,
  costing only storage, cleanable by a sweep. The reverse would leave a row pointing at
  nothing, which renders as a broken image on a live page.
- **Delete removes the row first, object second.** Removing the reference is what the user
  asked for, and an orphan is harmless; deleting the object first would risk a row that
  renders broken if the row delete then failed.

**A failed metadata write deletes the object it just stored**, so a rejected upload leaves no
litter (verified by B7).

**No PUT on assets.** Bytes are immutable once written; replacing an image means uploading a
new one. That keeps `storage_key` stable for anything already referencing it — including
already-published pages.

---

# Phase C — Creator onboarding (mocked platform APIs)

**Suite:** [`tests/e2e_creator_onboarding.sh`](../tests/e2e_creator_onboarding.sh) — 32 assertions, all passing

## The platform adapter is mocked, and the schema says so

The roadmap calls app registration the longest lead time in the plan and entirely non-code:
Meta review is 2-4 weeks and **resets if a reviewer requests changes**; TikTok is 5-10
business days. The status tracker in
[platform-app-registration.md](platform-app-registration.md) is still empty, so no real
platform read is possible yet.

**The mock reports `metrics_source = "mock"` and never `platform_api`.** This is the single
most important thing about it. The whole point of Phase C's design (roadmap decision #4) is
that a brand can tell a measured number from a guessed one; a mock that lied about its
provenance would be worse than having no adapter at all, because the invented figures would
look authoritative. C3 asserts the label explicitly.

Swapping in the real thing is a new class implementing `SocialProfileGateway` plus a config
value. Nothing above the port changes.

## What was built

| Roadmap step | Delivered | Where |
|---|---|---|
| C.1 `SocialProfilePort` + adapter behind retry | **Port + mock adapter** | `SocialProfileGateway.java`, `MockSocialProfileGateway.java` |
| C.2 `POST /creators/resolve-handle` | **Done** | `CreatorOnboardingController.java` |
| C.3 `POST /creators/classify` in agent_service | **Done** — LLM + heuristic fallback | `agent_service/app.py` |
| C.4 Persist metrics and classification separately, with provenance | **Done** | `2026_08_05_phase_c_creator_onboarding.sql` |
| C.5 Signup block writes a lead scoped to the page's brand | **Done** | `POST /api/public/landing/{slug}/signup` |
| C.6 Graceful degradation to a manual lead | **Done** | `CreatorOnboardingService.captureLead` |

## Test results

### Feature: handle resolution
| ID | Assertion | Result |
|---|---|---|
| C1 | Handle resolves | PASS (200) |
| C1b | Profile found | PASS |
| C1c | **Nothing was persisted** — looking is not saving | PASS |
| C2 | Same handle returns the same metrics on a second call | PASS |

> C2 matters for testability, not realism: fixtures can only assert exact metrics if the
> adapter is deterministic. It is seeded by FNV-1a over the handle rather than
> `String.hashCode`, which is not guaranteed stable across JVM versions.

### Feature: the metrics/classification separation (the core of Phase C)
| ID | Assertion | Result |
|---|---|---|
| C3 | Metrics labelled `mock` — never `platform_api` | PASS |
| C3b | Metrics carry a fetch timestamp | PASS |
| C4 | Classified `beauty` from captions alone | PASS |
| C4b | Classification reports its **own** source, distinct from `metricsSource` | PASS |
| C5 | The classification block contains **no metric fields at all** | PASS |
| C6 | A gambling creator is flagged `[alcohol, gambling]` | PASS |
| C6b | A clean creator is **not** flagged | PASS |

> C5 is the assertion the phase exists for. An LLM asked for a follower count returns a
> confident, plausible, wrong number, and a brand would spend against it. The request model
> in `agent_service` deliberately has no metric output fields, and the prompt says so.
>
> C6b is the pair to C6: a classifier that flags everything is as useless as one that flags
> nothing. Both directions have to hold.

### Feature: lead capture
| ID | Assertion | Result |
|---|---|---|
| C7 | Lead created | PASS (201) |
| C7b | Created as `lead` — onboarding never approves | PASS |
| C7c | Provenance persisted alongside the metrics | PASS |
| C7d | The DB row itself carries `mock\|llm\|gaming` | PASS |

> C7b reflects roadmap decision #5: rules and automation may reject and advance, never
> approve. Approval grants access to briefs, assets and eventually money, and is the thing a
> brand will be asked to justify.

### Feature: graceful degradation (C.6)
| ID | Assertion | Result |
|---|---|---|
| C8 | An unresolvable handle **still creates the lead** | PASS (201) |
| C8b | Labelled `manual`, not `mock` | PASS |
| C8c | Follower count is **absent, not zero** | PASS |

> C8c is a small distinction with real consequences. Zero followers is a legitimate,
> meaningful value; "we do not know" is a different claim. Writing 0 as a stand-in would make
> a vetting rule like `follower_count < 5000 → reject` silently reject every creator whose
> lookup failed.

### Feature: public signup from a landing page
| ID | Assertion | Result |
|---|---|---|
| C9b | Accepted with **no auth token** | PASS (201) |
| C9c | Body said `status: approved`; the row is a `lead` | PASS |
| C9d | Body's `followerCount: 99999999` discarded | PASS |
| C9e | Body's `brandId` ignored — brand comes from the page slug | PASS |
| C9f | Attributed to `landing_page` | PASS |
| C9g | Lead linked back to the page that produced it | PASS |
| C10 | A **draft** page refuses signups | PASS (404) |
| C10b | An unknown slug is refused | PASS (404) |

> This endpoint is unauthenticated by necessity — a creator applying to a campaign has no
> account. C9c/C9d/C9e are the containment: the payload is **rebuilt** from four allowed
> fields rather than forwarded, so the hostile body in the test (naming another brand,
> pre-approving itself, inflating its metrics) changes nothing. The brand is derived from the
> slug, which is the difference between "a signup form" and "an endpoint that injects leads
> into any brand's CRM".

### Feature: tenancy
| ID | Assertion | Result |
|---|---|---|
| C11 | A second brand holds its own row for the same handle | PASS |
| C11b | The two rows belong to different brands | PASS |
| C11c | The second brand sees only its own row | PASS |
| C12 | An unsupported platform is refused with 400 | PASS |

## Two things found while building this

**`ResponseShapeService.creator()` returned only 9 fields.** Every metric and vetting column
on `creator.creators` — some of which have existed for a long time — was stripped before
reaching the UI, so they could never have been displayed even when populated. Widened here,
with metrics and their provenance exposed together deliberately: a follower count without
`metricsSource` and `metricsFetchedAt` cannot be judged, and shipping the number alone invites
exactly that mistake.

**The ArchUnit boundary test caught a real design error.** The first implementation imported
`campaign.infrastructure.AgentMappingClient` from the creator context. Both call the same
agent service, so sharing looked reasonable — but an outbound client is a context's own
detail, and reaching across re-couples two contexts through a class neither owns. Fixed by
giving the creator context its own small client (`CreatorClassificationClient`). The rule was
right and the original design was wrong.

## Known gaps

- **No rate limiting on the public signup endpoint.** Nothing stops a script filling it in
  repeatedly, and a flood would land in a brand's review queue. That needs infrastructure the
  platform does not have yet; it is recorded here rather than papered over with a check that
  would not survive a real bot.
- **No real platform adapter**, pending app registration. Everything above the port is built
  and tested; the adapter is the piece waiting on external approval.
- **Vetting rules (C2) and health monitoring (C3) are not built.** This phase captures and
  labels leads; deciding what to do with them is the next phase.

---

# Phase D — Stage automation and bidirectional Kanban sync

**Suite:** [`tests/e2e_stage_automation.sh`](../tests/e2e_stage_automation.sh) — 36 assertions, all passing

## What was built

Roadmap decision #8 made the board **writable in both directions**: dragging a card changes
the page's stage, and changing the page's stage moves the card. §4 records that a one-way
projection was argued for and overruled — and that the reasoning still governs *how* this is
built, because two writable state machines that must agree is the shape that eventually
produces a card in *Published* for a page still in draft.

| Roadmap step | Delivered | Where |
|---|---|---|
| D.1 `stage` with the PRD's eight values | **Done** (added in Phase A's migration) | `content.landing_templates.stage` |
| D.2 Allowed-transition map enforced in content | **Done** | `LandingStageMachine.java` |
| D.3 `PUT /api/landing-pages/{id}/stage` — one command path | **Done** | `LandingStageController.java` |
| D.4 Transition carries `{from, to, source}` | **Done** — as a table, not the outbox (see below) | `workflow.stage_transitions` |
| D.5 Workflow moves the card, idempotent | **Done** | `LandingStageService.syncCard` |
| D.6 Configurable stage to board-stage mapping per brand | **Done** | `workflow.stage_mappings` |
| D.7 Board drag issues the command | **Done** | `WorkflowBoardsController.placeCard` |
| D.8 Nightly reconciliation | **Not built** — see Known gaps | — |

## The four rules, and the tests that pin each

| Rule (§4) | Assertions |
|---|---|
| 1 — Content owns the transition, always | D8, D9, D9b, D9c |
| 2 — Not every transition is legal | D3, D3b |
| 3 — Some transitions need more than a stage change | D4, D4b |
| 4 — Events carry a source; card writes are idempotent | D6, D6b, D7, D7b, D10 |

**D9 is the assertion that matters most.** A refused drag must leave the card exactly where it
was. §4's reasoning: a card that had already moved would need compensating, and compensating
a UI drag is far worse than refusing it. The test drags to an illegal column and then checks
*both* the card and the page did not move.

## Test results

### Feature: the transition map (rule 2)
| ID | Assertion | Result |
|---|---|---|
| D1 | Eight page stages published at `/api/landing-pages/stages` | PASS |
| D1b | The map says `draft` cannot reach `published` — the UI can grey out illegal drops | PASS |
| D3 | `draft` to `published` refused | PASS (409) |
| D3b | The refusal names the legal targets | PASS |
| D2 | The legal path draft → review → approved → ready_to_publish → published | PASS (4x200) |

> Backwards moves are allowed; skipping *forwards* is not. Work genuinely goes backwards — a
> page in review gets sent back, a published page gets pulled. Blocking that would push people
> to delete and recreate pages to get around it, losing the history. What is blocked is jumping
> ahead of a gate.

### Feature: publishing actually publishes
| ID | Assertion | Result |
|---|---|---|
| D5 | Reaching stage `published` also sets `status=published` | PASS |

> Without this the board would report *Published* while the public URL returned 404 — the same
> divergence Phase D exists to prevent, just expressed through `status` instead of `stage`.

### Feature: rule 3, transitions that need more than a label
| ID | Assertion | Result |
|---|---|---|
| D4 | An **empty** page cannot be published | PASS (409) |
| D4b | And it did not move — the refusal happened before any write | PASS |

### Feature: rule 4, provenance and idempotency
| ID | Assertion | Result |
|---|---|---|
| D6 | Four legal moves produce four rows; refusals are not logged as transitions | PASS |
| D6b | Each row records where the change came from | PASS |
| D7 | Three identical commands with one key all return 200 | PASS |
| D7b | ...and wrote exactly **one** transition row | PASS |
| D10 | A board-originated change does not echo back as a second card move | PASS |

### Feature: rule 1, the board is a command issuer
| ID | Assertion | Result |
|---|---|---|
| D9 | An illegal drag is refused | PASS (409) |
| D9b | The card did **not** move | PASS |
| D9c | The page did **not** move | PASS |
| D8 | A legal drag is accepted | PASS (200) |
| D8b | The drag drove the **page** stage — the board is genuinely writable | PASS |
| D8c | The card is where it was dropped | PASS |
| D11 | The reverse: a builder stage change moves the card | PASS |
| D11b | The card followed the page — sync runs both ways | PASS |

### Feature: mappings and tenancy
| ID | Assertion | Result |
|---|---|---|
| D12 | Re-saving a mapping upserts rather than duplicating | PASS |
| D13 | Another brand cannot place our card | PASS (404) |
| D13b | Another brand cannot change our page's stage | PASS (404) |
| D13c | Another brand cannot read our transition log | PASS (404) |
| D13d | An unauthenticated placement is refused | PASS (401) |
| D13e | After every cross-tenant attempt the card is untouched | PASS |
| D14 | An unknown stage is refused | PASS (400) |
| D14b | An unknown **source** is refused | PASS (400) |

> D13-D13d close a real pre-existing hole: `placeCard` previously performed **no authorization
> at all**, so any caller could place any card by id. It is now permission-checked and
> brand-scoped.
>
> D14b is not cosmetic. An unrecognised source would be stored and could later suppress the
> wrong echo, so it is rejected rather than coerced to a default.

## Two defects found by these tests

**1. An empty page could be published.** `requirePublishable` measured the raw JSON node's
length, but the DAO returns `blocks` as a JSON *string* — so `"[]"` is four characters, not
two, and was read as content. This is the third appearance of the same jsonb-as-String trap in
this work (Phase A's `blocks`, Phase C's `audienceDemographics`, now this). The entity maps
jsonb columns as Java `String`, so anything reading one must parse before judging it.

**2. Repeated transitions vanished from the audit trail.** The default idempotency key was
`templateId:from->to`, which looked reasonable and was wrong: work legitimately goes round the
loop more than once (draft → review → draft → review), so the second pass collided with the
first and was silently swallowed as a "duplicate". The page and board stayed correct — only
the audit trail lost the entry, which is the one thing the log exists for.

Caught by D10 asserting a board-sourced row existed and finding zero. Fixed by making the
default key unique per occurrence; a caller-supplied key still means "this is the same
command, absorb the retry". The swallow path now logs instead of being silent, since silence
is what hid it.

## Known gaps

- **No nightly reconciliation job (§4).** Rules 1-4 make divergence unlikely; the job is what
  makes it self-healing when delivery duplicates or drops. It needs a scheduler the platform
  does not have wired up, so it is deferred rather than half-built.
- **Phase D does not use `shared.domain_events`.** It writes `workflow.stage_transitions`
  directly. The outbox has exactly one producer today (Finance's `CommissionAccrued`), and its
  relay is enabled **only in the DAO** — every extracted service defaults
  `EVENTS_RELAY_ENABLED=false`, and the Workflow service has no events package at all. Routing
  through it means vendoring that package into Workflow and flipping the flag. The transitions
  table gives the same audit trail and idempotency without that change; moving to the outbox
  later is a change of writer, not of contract.
- **No optimistic-move-and-revert in the UI.** §4 rule 1 describes the card snapping back on a
  refusal. The server-side rules are enforced and a refused drag returns 409 with a reason, but
  the board currently surfaces that as an error rather than an animation.
