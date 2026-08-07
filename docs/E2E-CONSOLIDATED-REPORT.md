# Landing Page Builder — Consolidated E2E Report

**Date:** 2026-08-06
**Scope:** Every roadmap phase except F — [landing-page-builder-roadmap.md](landing-page-builder-roadmap.md)
**Environment:** live stack, not mocks — UI build → BFF `:8081` → DAO `:8443` → PostgreSQL 15 → agent_service `:8000`

| Suite | Feature | Assertions | Result |
|---|---|---|---|
| [`e2e_landing_builder.sh`](../tests/e2e_landing_builder.sh) | Phase A — visual builder | 27 | PASS |
| [`e2e_asset_library.sh`](../tests/e2e_asset_library.sh) | Phase B — asset library | 25 | PASS |
| [`e2e_creator_onboarding.sh`](../tests/e2e_creator_onboarding.sh) | Phase C — creator onboarding | 32 | PASS |
| [`e2e_workflow_stage_identity.sh`](../tests/e2e_workflow_stage_identity.sh) | Stage-rename regression | 16 | PASS |
| [`e2e_stage_automation.sh`](../tests/e2e_stage_automation.sh) | Phase D — stage automation | 36 | PASS |
| [`e2e_creator_vetting.sh`](../tests/e2e_creator_vetting.sh) | Phase C2 — per-brand vetting | 31 | PASS |
| [`e2e_creator_health.sh`](../tests/e2e_creator_health.sh) | Phase C3 — health monitoring | 30 | PASS |
| [`e2e_page_collaboration.sh`](../tests/e2e_page_collaboration.sh) | Phase G — co-editing | 29 | PASS |
| [`e2e_domains_hosting.sh`](../tests/e2e_domains_hosting.sh) | Phase E — domains + hosting | 35 | PASS |
| [`e2e_observability.sh`](../tests/e2e_observability.sh) | Phase H — observability | 19 | PASS |
| **Total E2E** | | **280** | **0 failures** |
| `mvn test` (InfluencerWebExperience) | unit + ArchUnit boundaries | 92 | PASS |

Detailed per-phase results, design rationale and known gaps are in
[E2E-LANDING-BUILDER-REPORT.md](E2E-LANDING-BUILDER-REPORT.md). This document is the
by-feature summary and the test data.

---

## Commits

| Commit | Phase | What shipped |
|---|---|---|
| `dd5ef3f` | A | GrapesJS builder, HTML/CSS sanitizer, version history + restore, coupon-free brand render path |
| `f31c99c` | B | Asset library behind `AssetStoragePort`, filesystem adapter, upload validation |
| `a2504cd` | C | Handle resolution, LLM classification, lead capture, public signup — mocked platform APIs |
| `8bfa4d4` | — | Stage-rename bug: renaming one stage silently unplaced every card on the board |
| `07a98b4` | D | Bidirectional Kanban sync, transition map, stage mappings, transition log |
| `ab09ffc` | C2 | Per-brand vetting rules, audit trail, dry-run, review queue, quality reports |
| `2623503` | C3 | Metric snapshots, per-brand thresholds, decline alerts that never revoke |
| `e5e62bd` | G | Page collaborators gated on a confirmed identity link; no publish right |
| `8a20097` | E | Brand-owned domains, DNS + SSL, two-month hosting window, 410 on expiry |
| `2f63721` | H | Actuator + Micrometer, narrow exposure, domain counters |

---

## Feature coverage

### 1. Visual landing page builder (Phase A)

| Capability | Verified by |
|---|---|
| Save a GrapesJS document; it round-trips UI → BFF → DAO → PG → back | A1, A2, A3 |
| Stored as real `jsonb`, not a quoted string | A3 |
| Every save appends a version | A4, A4b |
| Restore brings content back **as a new version**, never rewinding history | A5, A5b, A5c |
| A restored page returns as `draft` — restore never silently republishes | A5d |
| Published brand page renders at `/s/{slug}` with no coupon | A6 |
| Draft pages are not publicly readable | A15 |
| Pre-builder pages still render through the original typed-block path | A14–A14d |
| Version history is tenant-scoped | A17 |

**Public-page XSS suite (A7–A13).** The old renderer was safe by construction — a fixed tag
set with every value escaped. GrapesJS output *is* markup, so that guarantee was rebuilt as
allow-list filtering. Payloads blocked while legitimate markup survived in the same document:

| Payload | Result |
|---|---|
| `<script>alert(1)</script>` | stripped |
| `<img src=x onerror=alert(2)>` | stripped |
| `<a href="javascript:alert(3)">` | stripped |
| `<iframe src="https://evil.test">` | stripped |
| `<form><input name="password">` | stripped |
| `.a{width:expression(alert(4))}` | whole stylesheet dropped |
| `<h1>Safe</h1>` alongside all of the above | **preserved** (A13) |

### 2. Asset library (Phase B)

| Capability | Verified by |
|---|---|
| Upload accepted; dimensions probed from the bytes | B1, B2, B2b |
| Content type from magic bytes, not the client's header | B2c, B4 |
| Storage key generated, brand-prefixed, never the filename | B3, B3b |
| HTML disguised as `.png` refused | B4 |
| SVG refused — a real image format and a script carrier | B5 |
| Rejected uploads leave no row | B7 |
| Bytes serve back byte-identical, anonymously, with `nosniff` | B8–B8d |
| Path traversal refused | B9 |
| Cross-tenant list/delete refused | B10, B11 |
| Owner delete removes row and bytes | B12–B12c |

### 3. Creator onboarding (Phase C — mocked platform APIs)

The roadmap's decision #4: **facts come from platform APIs, the model only classifies.**

| Capability | Verified by |
|---|---|
| Handle resolves without persisting anything | C1, C1c |
| The adapter is deterministic — same handle, same metrics | C2 |
| Metrics labelled `mock`, never `platform_api` | C3 |
| Metrics carry a fetch timestamp | C3b |
| Classification carries its **own** source, separate from metrics | C4b |
| The classification block contains **no metric fields at all** | C5 |
| A gambling creator is flagged; a clean creator is not | C6, C6b |
| Leads are created as `lead`, never approved | C7b |
| An unresolvable handle **still** creates the lead | C8 |
| …with the follower count **absent, not zero** | C8c |
| Public signup works with no auth token | C9b |
| Hostile body discarded: `status: approved`, fake `brandId`, `followerCount: 99999999` | C9c, C9d, C9e |
| Draft pages and unknown slugs refuse signups | C10, C10b |
| One row per (creator, brand); no cross-brand visibility | C11–C11c |

### 4. Stage automation and Kanban sync (Phase D)

| Rule (§4) | Capability | Verified by |
|---|---|---|
| 1 | A board drag is a **command** content may refuse | D8, D9 |
| 1 | A refused drag leaves card **and** page untouched | D9b, D9c |
| 1 | A legal drag drives the page stage — board is writable | D8b, D8c |
| 2 | `draft → published` refused, with the legal targets named | D3, D3b |
| 2 | The full legal path succeeds | D2 |
| 3 | An empty page cannot be published | D4, D4b |
| — | Reaching `published` also sets `status=published` | D5 |
| 4 | Every transition logged with its origin | D6, D6b |
| 4 | Three identical commands → one transition row | D7, D7b |
| 4 | Board-sourced changes do not echo | D10 |
| — | Reverse direction: builder change moves the card | D11, D11b |
| — | Mappings upsert rather than duplicate | D12 |
| — | Cross-tenant place/change/read all refused | D13–D13e |

### 5. Stage-rename regression (bug fix)

| Capability | Verified by |
|---|---|
| Renaming a stage keeps its id | W1b |
| No duplicate stages created | W2 |
| **Renaming an unrelated stage no longer unplaces the card** | W3 |
| Reordering preserves placement while updating position | W4b, W4c |
| A genuinely deleted stage **does** unplace its cards | W5c |
| A stage id from another board is not honoured | W6b, W6c |

---

## Test data

All suites create their own fixtures per run, stamped with epoch seconds so repeat runs never
collide. Accounts use `@example.test`, which the pre-existing fixture-clearing migration
recognises as non-production.

### Accounts and fixtures

| Suite | Account pattern | Fixtures created |
|---|---|---|
| Phase A | `lb.brand.<stamp>@example.test` | 1 brand, 2 campaigns, 2 pages (1 builder, 1 legacy), 3 versions |
| Phase B | `ab.brand.<stamp>`, `ab.other.<stamp>` | 2 brands, 1 PNG (4×2, generated), 2 hostile fixtures |
| Phase C | `cr.brand.<stamp>`, `cr.other.<stamp>` | 2 brands, 2 campaigns, 4 creator leads |
| Stage identity | `ws.brand.<stamp>@example.test` | 1 brand, 2 boards, 3 stages, 1 card |
| Phase D | `sa.brand.<stamp>`, `sa.other.<stamp>` | 2 brands, 1 board, 3 stages, 3 mappings, 2 pages, 1 tracking card |

### Handle fixtures (Phase C) — deterministic by design

The mock adapter is seeded by FNV-1a over the handle, so the same handle always yields the
same figures. `String.hashCode` was deliberately avoided: it is not guaranteed stable across
JVM versions, and fixtures asserting exact values would shift under an upgrade.

| Handle | Niche | Risk flags | Purpose |
|---|---|---|---|
| `@glow_daily` | beauty | none | Clean creator — proves the classifier discriminates |
| `@casino_king` | gaming | `alcohol`, `gambling` | Exercises the flagging branch |
| `@fit_mike` | fitness | none | Public-signup path |
| `@unknown_person` | — | — | Resolves to nothing → manual fallback (C.6) |

Engagement falls as follower count rises in the mock (6.5% under 10k, 3.8% under 100k, 1.9%
above), matching the real inverse relationship — so a vetting rule written against mock data
behaves the same way against a live platform read.

### Image fixtures (Phase B)

Generated in-test rather than committed — a few dozen bytes of PNG is clearer as code than a
binary blob, and it keeps the hostile fixtures obviously inert.

| Fixture | Bytes | Expected |
|---|---|---|
| `lb_real.png` | valid 4×2 PNG, 75 bytes | accepted; dimensions probed as 4×2 |
| `lb_fake.png` | `<html><script>alert(1)</script></html>` | 415 — sniffed, not trusted |
| `lb_evil.svg` | SVG containing `<script>` | 415 — SVG excluded entirely |

### Database state after a full run

| Table | Rows | Note |
|---|---|---|
| `content.landing_templates` | 22 | Across all suite runs |
| `content.landing_template_versions` | 31 | Append-only; more than templates by design |
| `content.assets` | 1 | Phase B deletes its own asset in D12 |
| `creator.creators` (leads) | 11 | Includes per-brand duplicates — the tenancy model |
| `creator.creators` with `metrics_source='mock'` | 8 | Every one labelled mock, none `platform_api` |
| `workflow.stage_mappings` | 12 | |
| `workflow.stage_transitions` | 57 | `builder` 47, `api` 7, `board` 3 |
| `workflow.workflow_cards` with a page link | 4 | |

`casino_king` appears once per brand that captured it, each row carrying that brand's own
classification — the one-row-per-(creator, brand) rule holding in the data.

---

## Defects found and fixed during this work

| # | Defect | Found by | Impact if shipped |
|---|---|---|---|
| 1 | `<h1>` stripped by the sanitizer | unit test | Every hero title silently degraded to bare text |
| 2 | `<div>` stripped by the sanitizer | unit test | Nearly every real builder page flattened |
| 3 | Renaming a stage unplaced **every** card on the board | manual repro | Silent data loss on an ordinary edit |
| 4 | An empty page could be published | D4 | A live URL serving a blank page |
| 5 | Repeated transitions vanished from the audit trail | D10 | "Why did this card move?" unanswerable |
| 6 | `placeCard` had **no authorization at all** | Phase D review | Any caller could place any card by id |
| 7 | `ResponseShapeService.creator()` returned 9 fields | Phase C build | Metric columns could never reach the UI |
| 8 | The vetting rule engine matched **nothing at all** | C2 smoke test | Every rule inert while the UI showed them active |
| 9 | Collaborator revoke returned 404 for everyone | G12 | Access could be granted but never withdrawn |

Defects 1, 2, 4 and 8 share a root cause worth recording: **the DAO maps `jsonb` columns as Java
`String`**, so anything reading one must parse before judging it and anything writing one must
send text. This trap appeared **four** times — Phase A (`blocks`), Phase C
(`audienceDemographics`), Phase D (the publish guard), and Phase C2 (rule `condition`, where it
silently disabled the entire rule engine).

Defect 9 has its own lesson: the DAO's list endpoints return an empty array when unfiltered,
*on purpose*, because an unfiltered list would be a cross-tenant leak. Using one as a lookup by
id therefore always fails.

Defect 6 was found by reading rather than testing, while implementing rule 1. Defects 1 and 2
came from reusing jsoup's comment-oriented safelist for page content: `basicWithImages()`
assumes untrusted content *inside* someone else's page, but a landing page **is** the document.

---

## What is not built

Stated plainly, because a report that only lists passes is not a status report.

**Waiting on external approval:**
- **Real platform adapters.** The status tracker in
  [platform-app-registration.md](platform-app-registration.md) is still empty. Meta review is
  2–4 weeks and resets if a reviewer requests changes; TikTok is 5–10 business days. Everything
  above the port is built and tested; only the adapter waits. **This is still the longest lead
  time in the roadmap and none of it is code.**

**Deliberately deferred, with the reason:**
- **Schedulers.** C3's tiered refresh cadence and E's expiry warnings (30/7/1 days) both need a
  scheduler that is not wired up. The endpoints a scheduler would call are built and tested.
- **Phase D reconciliation** (§4) — same reason.
- **Presigned uploads / S3 adapter** (B.3). The port is the durable decision; the adapter is
  filesystem, so bytes currently proxy through the BFF.
- **Outbox integration for Phase D.** The Workflow service has no events package and the relay
  is enabled only in the DAO.
- **Rate limiting** on public signup — needs infrastructure the platform lacks.
- **G.6 simultaneous editing** (a CRDT). Version history makes the non-simultaneous case safe,
  and the roadmap says to defer until users report losing each other's work.
- **A Prometheus registry.** The endpoint name is deliberately absent from the exposure list
  rather than listed-and-broken — add the dependency and the name together.
- **Welcome package automation** (C2.7). The approval trigger exists; the package does not.
- **Phase F — social publishing.** Blocked on the same app registrations, and the roadmap calls
  it the most security-sensitive item in the plan: posting to a creator's personal account needs
  per-brand consent, separate token stores, and creator pre-approval of content.

**Not lifted:**
- `uq_landing_templates_campaign` still enforces one landing page per campaign. Changing that
  is a product decision, not a builder prerequisite.

---

## Reproducing

```bash
export E2E_WORKDIR=/path/to/scratch
bash tests/e2e_landing_builder.sh          # 27
bash tests/e2e_asset_library.sh            # 25
bash tests/e2e_creator_onboarding.sh       # 32
bash tests/e2e_workflow_stage_identity.sh  # 16
bash tests/e2e_stage_automation.sh         # 36

cd InfluencerWebExperience && mvn -q test   # 77 incl. ArchUnit
```

Requires the stack running: PostgreSQL and Redis via `docker-compose`, plus the DAO (`:8443`),
BFF (`:8081`) and agent_service (`:8000`).

**Migrations applied by this work**, in order:

```
schema/migrations/2026_08_05_phase_a_landing_builder.sql
schema/migrations/2026_08_05_phase_b_assets.sql
schema/migrations/2026_08_05_phase_c_creator_onboarding.sql
schema/migrations/2026_08_05_phase_d_stage_automation.sql
```

All four are idempotent and additive; each carries its own rollback in a trailing comment.

**Two operational notes for anyone picking this up:**

1. **All landing and workflow traffic routes through the DAO monolith on `:8443`**, not the
   extracted services. Every affected class exists twice — once in the extracted service, once
   in `InfluencerDAO` — and both must be edited in lockstep, but only the DAO needs restarting
   for a change to take effect.
2. **After a dependency change, rebuild the classpath before restarting.** Services run from
   `target/classes` with a fully-expanded `-cp`, so replaying the original command line brings
   the service back up missing the new jar. This surfaced as
   `NoClassDefFoundError: org/jsoup/safety/Safelist` when jsoup was added in Phase A.
