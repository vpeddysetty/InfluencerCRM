# Landing Page Builder — E2E Test Report

**Date:** 2026-08-05
**Scope:** Phase A of [landing-page-builder-roadmap.md](landing-page-builder-roadmap.md)
**Suite:** [`tests/e2e_landing_builder.sh`](../tests/e2e_landing_builder.sh) — 27 assertions, all passing
**Unit tests:** `LandingDocumentSanitizerTest` — 13 assertions, all passing

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
