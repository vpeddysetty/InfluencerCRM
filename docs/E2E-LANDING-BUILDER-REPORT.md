# Landing Page Builder — E2E Test Report

**Date:** 2026-08-05
**Scope:** Phases A and B of [landing-page-builder-roadmap.md](landing-page-builder-roadmap.md)

| Suite | Assertions | Result |
|---|---|---|
| [`tests/e2e_landing_builder.sh`](../tests/e2e_landing_builder.sh) — Phase A | 27 | all passing |
| [`tests/e2e_asset_library.sh`](../tests/e2e_asset_library.sh) — Phase B | 25 | all passing |
| `LandingDocumentSanitizerTest` (unit) | 13 | all passing |

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
