# Curated section editor — implementation plan

**Status:** plan, not yet scheduled. `MASTER-ROADMAP.md` remains the scheduling authority; this
expands PR-35's editor work and does not supersede it.

**Decision it implements:** `docs/Landing-Editor-Framework-Evaluation.md` recommends replacing
GrapesJS with a curated section editor. Three prototypes were built and approved before this plan
was written — the rendered page, the editing interaction, and the template gallery.

**Estimate: 16–23 dev-days** for one engineer. The evaluation said 13–20; saved templates add 3.

---

## 1. Why this is a rewrite and not a restyle

The founder's report was "the visual editor looks cheap." The evaluation's finding was that this is
a *consequence of the tool*, not of its theme: a free-form box canvas makes page quality a function
of the user's design skill, and the user is a brand manager with none. Theming GrapesJS improves the
starting point and leaves the failure mode intact — the user can still drag a div three pixels off
centre and publish it.

Three facts from the codebase settled it, each verified rather than assumed:

1. **The stack is already section-shaped.** `PageGenerationPort.Section(type, title, body)` carries
   a comment saying `type` "deliberately reuses the vocabulary the renderer already understands";
   `rewriteSection` already exists as a section-level port; `LandingService.renderLegacyBlocks` is
   already a typed-section renderer in Java. GrapesJS is the only box-shaped component in a system
   that is section-shaped end to end.
2. **The AI's sections are currently destroyed on entry.** A generated draft is flattened to an HTML
   blob to enter the builder. That is a lossy one-way trip, and it is why `rewriteSection` has
   nothing to point at once a draft is open.
3. **The sanitizer already made this argument.** Its header notes the typed-block renderer was "safe
   by construction" and that a visual builder "inverts that — its output *is* markup." Returning to
   typed sections restores a security property the code records losing.

Removing GrapesJS also deletes **951 KB** (261 KB gzipped), the largest asset in the app, vendored
twice under the remote-copy pattern.

---

## 2. What is being built

Four pieces, in dependency order.

| # | Piece | Days | Depends on |
|---|---|---|---|
| A | Section schema + renderer | 3–4 | — |
| B | Section type library (design + build) | 5–7 | A |
| C | Editor shell | 4–5 | A, B |
| D | Templates, built-in and saved | 4–7 | B, C |

### A. Section schema and renderer (3–4d)

**`V42__landing_sections.sql`** adds `sections jsonb` to `content.landing_templates`, nullable.

The renderer gains a third path, and precedence is **`sections` → `document` → `blocks`**. This is
the whole migration story: `renderHtml` already branches at runtime and V24 already proved the
pattern when `document` was added beside `blocks`. No existing row is rewritten, so the live
published page is never at risk.

**No HTML-to-section parser.** Converting an existing GrapesJS document back into typed sections is
an unbounded heuristic over arbitrary markup, and it would run against customer pages. There is one
published page today; hand-rebuilding it is minutes of work and cannot corrupt anything. A brand
whose page predates this keeps rendering from `document` until someone opens it in the new editor.

Server-side rendering of sections is Java, in `LandingService`, extending `renderLegacyBlocks`
rather than replacing it. Token substitution is unchanged: `fill()` is a plain string replace over
the final HTML and is editor-agnostic.

### B. Section type library (5–7d)

The long pole, and it is **design work more than code**. Each type is a React component for the
editor, a Java renderer for the public page, and a field schema shared by both.

| Type | Fields | Variants |
|---|---|---|
| Hero | eyebrow, headline, subheadline, CTA label | centred / left / split |
| Media | asset, caption, alt text | full-bleed / contained |
| Offer | headline, supporting line | centred / left / split |
| Proof | 2–4 items (icon, title, body) | 3-up grid / stacked list |
| Creator | quote, name, handle, platform, portrait | portrait-left / quote-first |
| Signup | headline, field label, button label | inline / stacked |
| Text | body | one column / two column |
| Legal | body | — |

Variants are the concession to real flexibility. Every variant is designed and responsive; the brand
picks one and cannot author a ninth.

**Two rules that make "it cannot look wrong" true rather than aspirational:** no field accepts a
colour, font, size or position, and every variant is built mobile-first so previewing is
confirmation rather than a second design job.

### C. Editor shell (4–5d)

Per the approved interaction prototype:

- Click a section on the canvas; its fields appear in one context panel. No layer tree, no separate
  style manager — the two GrapesJS panels that were never even enabled here.
- Reorder with up/down controls on the section. **Not drag-and-drop:** buttons are keyboard
  reachable and work on touch, and dragging is the part that breaks on a phone.
- Four preview widths — Desktop, Laptop 1024, Tablet 820, Phone 390 — narrowing the real canvas.
  The app already ships three; this adds Laptop.
- The editor itself is responsive: under 900px the panel moves below the canvas. A creator asked to
  tweak their page will do it on the device in their hand.
- Tokens (`{{coupon.code}}`) are highlighted wherever they appear with a plain-language note. In a
  free-text HTML editor a half-deleted token silently publishes as literal text on every creator's
  page; showing it is the cheapest available guard.
- Per-section AI rewrite — "Shorter", "Warmer", "Lead with the offer" — wired to the **existing**
  `/api/campaign-pages/sections/rewrite` endpoint, which finally has a section to point at.

### D. Templates (4–7d)

**Built-in (2d).** Eight starting points: the six matching the campaign types the brief form already
offers, plus Photo-led and Story-led, which are page shapes rather than campaign shapes and
therefore carry no campaign mapping. Section orders come from the design skill's landing-pattern
data, not invention.

A template is **an order of sections, not a skin.** It chooses which sections exist and where; never
colours, fonts or spacing. If templates could restyle, they would reintroduce precisely what this
editor removes — a way to pick something that looks worse.

Selecting a campaign type in the brief pre-selects the matching template, so template and campaign
type stay **one decision**. "Start from template" is in the design spec and was never built; this
joins it to the existing archetypes instead of adding a parallel concept.

**Switching templates keeps the words.** Re-ordering adds or removes sections; text already written
stays with its section, and removing one warns before discarding copy. A brand that has edited three
headlines *will* try another template, and losing that silently is the fastest way to stop them
exploring.

---

## 3. Saved templates (2–3d, inside D)

**The ask:** let a brand save a page it has built and reuse it later.

### The schema decision

A saved template is **its own table**, not a flag on `landing_templates`:

```sql
-- V43__brand_page_templates.sql
create table content.brand_page_templates (
    id          uuid primary key,
    brand_id    uuid not null,
    name        text not null,
    sections    jsonb not null,
    created_by_user_id uuid,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz
);
create unique index uq_brand_page_templates_name on content.brand_page_templates (brand_id, lower(name));
```

**Why not a flag on the existing table.** `uq_landing_templates_campaign` enforces one page per
campaign, and V24 records that as a deliberate product decision — the slug and coupon-assignment
logic assume it. A reusable template has no campaign, so it would need that constraint relaxed or a
nullable `campaign_id` carved out, which would weaken the guarantee for every real page in order to
store something that is not a page. A separate table leaves that invariant untouched.

**Sections only, never a rendered document.** A saved template stores the section list and its
content; it stores no HTML. That is what lets a design-system change reach every saved template at
once, rather than freezing last year's styling into every page a brand saved.

### What saving strips

Saving a page as a template **removes the campaign-specific values** and keeps the structure:

- Coupon tokens stay as tokens — that is the point of a token.
- Creator name, handle and portrait are cleared: they belong to one campaign's creator.
- Asset references are kept (they belong to the brand), but the brand is told which are referenced,
  because deleting an asset later would leave a hole in every page made from the template.

Not stripping the creator would mean a template that silently credits the wrong person on the next
campaign — a mistake that reaches a public page under the brand's name.

### Where limits apply

Saved templates count against the plan, added to `PlanPolicy.Resource` alongside
`LANDING_PAGE`. Free gets a small allowance; an unbounded per-brand table on a free tier is a
storage cost with no revenue attached.

### API

```
GET    /api/brand-page-templates            list this brand's saved templates
POST   /api/brand-page-templates            save the current page as a template
DELETE /api/brand-page-templates/{id}       remove one
```

Brand-scoped through the verified token, exactly like every other content endpoint — never from a
body field.

---

## 4. What is deliberately not in this plan

- **Per-breakpoint styling.** Preview only. Tuning desktop and never re-checking the phone is where
  builder pages break, and phone is where most creator traffic lands.
- **Arbitrary section nesting.** Sections are a flat ordered list. Nesting is how a curated editor
  becomes a box canvas again.
- **A template marketplace.** Sharing templates between brands raises attribution and moderation
  questions that no customer has asked for.
- **Migrating existing GrapesJS documents automatically.** See §2A.

---

## 5. Rollout

The new editor ships behind `web-experience.landing.editor` (`sections` | `builder`), defaulting to
`builder`. Same pattern as `page_generation_provider`: both code paths ship in one image, so
switching — or switching back — is a variable flip and an instance refresh rather than a redeploy.

GrapesJS is removed from the bundle only after the flag has been `sections` in production for a
release with no rollback. Deleting 951 KB is the reward, not the milestone.

---

## 6. The honest argument against

This is a rewrite prompted by an aesthetic complaint, and it is the option whose cost is least well
understood — 16–23 days is an estimate against design work that has not been done, and section
design is the part most likely to overrun.

The cheaper alternative remains real: restyle GrapesJS's defaults and ship a better starter document
in 1–2 days. It would raise the floor without changing the ceiling, and if the ceiling turns out not
to be the problem, this plan spends three weeks to discover that.

The counter-evidence is the approved prototypes. They demonstrate output the current editor cannot
reach at any theming budget, because the difference is what the user is permitted to do rather than
how it is painted. If those prototypes had looked merely adequate, the correct call would have been
the restyle.
