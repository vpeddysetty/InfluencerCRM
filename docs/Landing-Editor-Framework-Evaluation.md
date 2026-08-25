# Landing editor: framework evaluation

**Status:** recommendation, not yet scheduled. Supersedes nothing; `MASTER-ROADMAP.md` remains the
scheduling authority.

**Question asked:** the visual editor "looks cheap. It does not live up to product expectations."
Should we invest in GrapesJS, swap it for Puck or Craft.js, or build a curated section editor?

---

## Recommendation

**Build the curated section-based editor, and fall back to Puck only if that proves too slow.** The
founder's complaint is not that GrapesJS lacks features — it is that its output looks cheap, and
that is a *direct consequence* of what GrapesJS is. A free-form box canvas makes page quality a
function of the user's design skill, and the user is a brand manager with none. Every hour spent
theming GrapesJS improves the starting point while leaving the failure mode intact: the user can
still drag a div three pixels off-centre and publish it. A curated editor inverts the guarantee —
the user picks a section type and fills in fields, and *cannot* produce misalignment, clashing
fonts, or broken mobile layout, because those are not expressible. This is also what the design spec
already asks for ("section-level actions only, minimal page-level layout changes in v1") and what
the rest of the stack is already built for: `PageGenerationPort.Section(type, title, body)`
deliberately reuses the renderer's vocabulary, `rewriteSection` already exists as a section-level
port, and `LandingService.renderLegacyBlocks` is already a typed-section renderer in Java. GrapesJS
is the only box-shaped component in a system that is section-shaped end to end. Removing it deletes
951 KB, the largest asset in the app, and replaces ~336 lines of imperative escape-hatch code with
plain React that one engineer can actually maintain.

---

## Comparison

Scored 1–5, 5 best, against the stated priority order. Weight falls off down the list.

| # | Criterion (priority order) | GrapesJS (keep) | Puck | Craft.js | Curated sections |
|---|---|---|---|---|---|
| 1 | Output quality for a non-designer | **1** | 4 | 2 | **5** |
| 2 | Token/personalisation survival | 3 | 4 | 4 | **5** |
| 3 | AI-draft ingestion | 2 | 4 | 3 | **5** |
| 4 | Bundle size | **1** | 2 | 3 | **5** |
| 5 | Migration cost | **5** | 2 | 2 | 3 |
| 6 | Maintenance burden (one engineer) | 2 | 3 | 2 | **4** |
| 7 | SSR + sanitize compatibility | 4 | 3 | 3 | **5** |

Criterion 5 is the only one GrapesJS wins, and it wins it by doing nothing. That is worth naming
plainly: **the case for keeping GrapesJS is entirely a case about cost, not about quality.**

---

## The options

### 1. Keep GrapesJS, invest in theming and blocks

- **What it is:** a framework-agnostic web builder with a free-form canvas, layer manager, style
  manager and CSS editor. Currently `grapesjs@^0.21.13`; latest is **0.23.5** (published
  2026-08-11 — actively maintained).
- **Bundle:** `dist/grapes.min.js` is **988,781 bytes on disk** (951 KB), **261 KB gzipped**. Note
  it is vendored **twice** — `InfluencerContentUI/node_modules` *and* `InfluencerUI/node_modules`,
  both pinned `^0.21.13`, per the remote-copy pattern in CLAUDE.md §1.
- **Licence:** **BSD-3-Clause** (not MIT, as is commonly assumed). Free, no seat cost. GrapesJS
  *Studio* — the polished commercial product whose screenshots people usually have in mind — is a
  separate paid SDK. Adopting Studio to fix the "looks cheap" complaint would mean taking on a
  commercial dependency, which is disqualifying pre-revenue.
- **Why it loses here:** the complaint is structural. The most cited criticism of GrapesJS, from
  the Mautic project's own builder discussion, is that it "feels too advanced for non-technical
  users with flex properties, CSS classes, and weird px/vh units, while for technical users it
  feels clumsy." That is precisely our user. Theming raises the floor of a *new* page but never
  prevents a bad one, and the style manager — the thing that makes pages look cheap — is the part
  users are most drawn to. It also fights the design spec: GrapesJS's whole value proposition is
  arbitrary page-level layout, which v1 explicitly does not want. We would be paying 261 KB gzipped
  for freedom we are trying to take away.

### 2. Puck (`@measured/puck`)

- **What it is:** a schema-driven React visual editor. You declare components with **typed fields**;
  the user arranges *your* components and fills in *your* fields. It does not edit HTML — output is
  a JSON component tree, rendered through your React components.
- **Bundle:** npm `dist.unpackedSize` is **1,293,615 bytes unpacked** for v0.20.2
  (registry-verified). That is the whole package including types, CSS and both module formats, so it
  is an upper bound, not the shipped cost. *Unverified:* the actual gzipped browser cost —
  bundlephobia and packagephobia both failed to return figures (no data / HTTP 429). Do not quote a
  gzip number for Puck without measuring it; treat it as "same order as GrapesJS until proven
  otherwise." The editor is lazy-loaded on one route either way.
- **Licence:** **MIT.** No seat cost, no vendor lock-in, self-hosted.
- **Maintenance signal:** healthy, and the best of the four. Latest **v0.23.0 (2026-08-07)**, with
  0.22.x releases through June–July 2026 adding CSS-custom-property theming and a Dictionary API.
  Peer deps `react: ^18 || ^19` — compatible with our React 19.2.
- **Why it is the runner-up and not the pick:** Puck is genuinely the right *shape* — typed fields
  mean tokens live in field values rather than free HTML, and an AI section maps cleanly onto a
  component with props. But it renders through React, and **our public page is rendered by Java**
  (`LandingService.renderBuilderDocument`). Puck's own guidance for a non-React backend is that you
  store the JSON and render it yourself with your own stack — which means we would write the Java
  section renderer *anyway*, and then additionally carry Puck's editor, its drag-and-drop model and
  its version churn (0.x, still pre-1.0, with breaking changes between minors). We would be paying a
  dependency for the drag-and-drop shell while writing the valuable half ourselves. Choose Puck if
  the in-house editor's UI work turns out to be the bottleneck; its component/field model is close
  enough that the section schemas transfer.

### 3. Craft.js (`@craftjs/core`)

- **Bundle/licence:** MIT. Version **0.2.12**, `time.modified` **2025-02-14** — no release in ~18
  months.
- **Maintenance signal: the weakest of the four, and disqualifying for a solo engineer.** There are
  React 19 compatibility commits on `main`, but they are not in a published release. The maintainer's
  own "Future of Craft.js" issue (#507) says the next-generation state system is being developed as
  a *separate* project (Reka), which reads as the successor being elsewhere.
- **Why it loses:** Craft.js is explicitly a *framework for building page editors* — it gives you
  the drag-drop/state layer and you build all the UI. So it carries most of the in-house build cost
  of option 4 **plus** an unreleased-for-18-months dependency in the critical path, and it is
  general-canvas by default, so it does not solve the quality problem either. Worst of both.

### 4. Curated section-based editor (in-house, plain React) — **recommended**

- **What it is:** no canvas. A fixed catalogue of professionally-designed section types (hero,
  feature/benefit trio, coupon, testimonial/quote, image+text, creator signup, FAQ, legal), each
  with (a) typed content fields, (b) 2–3 visual variants, and (c) a small page-level theme (accent
  colour, font pairing, light/dark). The user adds, removes, reorders and edits sections. Layout is
  not editable, because layout is where non-designers lose.
- **Bundle:** roughly **10–25 KB gzipped** of our own code, plus zero new runtime dependencies — a
  `dnd-kit`-style reorder library is ~10 KB gzipped, and arrow-button reordering needs nothing at
  all. Against 261 KB gzipped today, that is a **~90% reduction** on the app's largest asset.
  *Unverified:* exact figure, since the code does not exist yet; the estimate is from component
  count, not measurement.
- **Licence:** ours.
- **Why it wins on each criterion that matters:**
  - **(1) Output quality.** A designer (or a competent LLM-assisted pass) designs eight sections
    *once*, properly, with real type scale, spacing rhythm and mobile behaviour. Every page any user
    ever builds is a composition of those eight. The floor and the ceiling are both set by us. This
    is the only option where "it looks cheap" is fixable *by fixing one thing*.
  - **(2) Tokens.** Decisive, and worth stating precisely: token substitution today is
    `out.replace("{{" + key + "}}", value)` over the final HTML string (`LandingService.fill`).
    Tokens are therefore editor-agnostic — but in GrapesJS they sit in free-form HTML the user can
    partially delete, producing a broken `{{coupon.cod}}` that silently renders as literal text. In
    a curated editor a coupon section has a *code slot*, not a text box: the token is emitted by the
    renderer, not typed by the user, so it cannot be broken by an edit round-trip. This is a real,
    current fragility being removed.
  - **(3) AI ingestion.** The AI already returns `Section(type, title, body)`, and
    `PageGenerationPort` documents that `type` "deliberately reuses the vocabulary the renderer
    already understands." Today those typed sections must be flattened into an HTML blob to enter
    GrapesJS, which is a lossy one-way trip — the section identity is gone, so `rewriteSection`
    (already defined in the port) has nothing to point at. With a section editor, **an AI draft is
    already the editor's native state**: no adapter, and per-section "rewrite with AI" becomes
    almost free. Presenting 2–3 drafts side by side also becomes trivial, since a draft is just an
    array.
  - **(6) Maintenance.** No 0.x dependency in the critical path, no imperative teardown, no "React
    must never re-render the canvas" hazard (see the load-bearing comment at the top of
    `LandingBuilder.jsx`). Adding a section type becomes: add an entry to a catalogue array plus a
    Java render case.
  - **(7) SSR/sanitize.** Best of the four, and a genuine security improvement. The sanitizer's own
    header explains that the typed-block renderer "was safe by construction: it emitted a fixed set
    of tags and escaped every dynamic value, so stored data could never become markup," and that a
    visual builder "inverts that." Returning to typed sections **restores safety by construction**
    and lets the permissive `style`/`class`-on-everything allow-list be narrowed.
- **Its real cost:** we own the UI, and the empty-state and blank-page design work is on us.

### Also considered, rejected quickly

- **Plasmic / Builder.io — rejected on cost and lock-in.** Builder.io's Team plan starts at
  **$99/month**; Plasmic's paid tiers run ~**$39/mo (Starter)**, ~**$103/mo (Pro)**, ~**$399/mo
  (Scale)**, with collaborator limits on the free tier. A per-seat SaaS dependency in the publish
  path of a pre-revenue product is disqualifying on its own; both also want to own rendering and
  hosting, which conflicts with a Java-rendered, server-sanitized public page.
- **TipTap-based approach — rejected as the wrong tool.** TipTap is a rich-text editor. It would
  make the *body copy* of a section nice, but has no opinion about page composition, which is where
  the pages look cheap. Worth revisiting *inside* a section's body field later; it does not answer
  this question.
- **Tailwind component catalogues (Tailwind UI et al.) — not a competing option; a shortcut for
  option 4.** They are a source of professionally-designed section markup. Note the licence:
  Tailwind UI/Tailwind Plus is a paid perpetual licence, and it is *unverified* whether current
  terms permit use in a product where end users compose the sections. Check before copying markup.
  Free alternatives (HyperUI, Meraki UI, Flowbite's MIT tier) avoid the question.

---

## Migration sketch (recommended option)

**Existing `{html, css}` documents keep working, and are not migrated.** This is already how the
system is built, which is what makes the recommendation cheap. `V24__phase_a_landing_builder.sql`
added `document jsonb` as **nullable and additive**, and `LandingService.renderHtml` chooses its
path at render time:

```java
if (sanitizer.hasRenderableHtml(builderHtml)) return renderBuilderDocument(...);
return renderLegacyBlocks(...);
```

So there are three states, and we add a third renderer rather than converting anything:

1. `document` null → legacy typed `blocks` → renders as today.
2. `document` set (the live published page, and any other GrapesJS page) → **keeps rendering from
   `{html, css}` exactly as today.** No data is rewritten, so there is no risk of corrupting live
   customer data. These pages open in a **read-only "legacy page" view** offering "rebuild with the
   new editor" (starting from a fresh AI draft) rather than a lossy HTML-to-section parse. With one
   published page at minimum, hand-rebuilding is minutes of work and infinitely safer than an import
   heuristic. **Do not write an HTML-to-section parser** — it is unbounded work to be approximately
   correct on input whose shape we control only by accident.
3. New `sections` representation → new renderer.

**Storage:** add `sections jsonb` alongside `document`, mirroring exactly the precedent V24 set. The
render precedence becomes `sections` → `document` → `blocks`. Keeping the columns separate (rather
than overloading `document`) means a rollback is a one-line precedence change, and the GrapesJS path
can be deleted later once no rows use it.

**Tokens:** unchanged mechanism — `fill()` still runs over the assembled HTML before sanitizing (the
"substitute BEFORE sanitizing" ordering in `renderBuilderDocument` is load-bearing and must be
preserved; it is why a coupon code containing markup cannot inject). The change is that tokens are
emitted by section renderers rather than typed by users, so `{{coupon.code}}` cannot be broken by an
edit.

**AI draft to initial state:** `List<Section>` becomes the editor's initial state directly. The
existing "required section set" validation in `CampaignPageGenerationService` becomes the editor's
schema validation — one definition, two uses.

**Effort, one engineer:**

| Work | Days |
|---|---|
| Section catalogue: 8 section types x 2–3 variants, designed and built (React + CSS) | 5–7 |
| Java section renderer in `LandingService` + sanitizer narrowing + tests | 2–3 |
| Editor shell: add/remove/reorder, field forms, live preview, theme picker | 3–4 |
| AI draft ingestion + per-section rewrite wired to existing `rewriteSection` | 1–2 |
| `sections jsonb` migration, render precedence, legacy read-only view | 1–2 |
| E2E + the duplicated-remote copy across `InfluencerUI`/`InfluencerContentUI` | 1–2 |
| **Total** | **13–20 dev-days** |

Call it **three to four weeks** for one engineer. The section *design* is the long pole and the part
most worth not rushing — it is the entire deliverable, since design quality is what the founder is
complaining about. Budget accordingly: if the sections are mediocre, this whole exercise reproduces
the current problem with a smaller bundle.

Deleting GrapesJS from **both** `package.json` files is the last step, not the first — keep the
legacy render path until no row needs it.

---

## The strongest argument against this recommendation

**It replaces a working feature with a rewrite, on a founder's aesthetic complaint, at a pre-revenue
company with zero subscribers — and it is the option whose cost we understand least well.**

The honest version: 13–20 days is my estimate *for code that does not exist*, and in-house UI
estimates are the ones that slip. Options 1 and 2 have externally-validated implementations; option
4's quality depends entirely on one engineer's section design being genuinely good, and if it is
not, we will have spent a month to ship pages that still look cheap — with the added indignity of
having thrown away a maintained library to do it. There is also a real capability loss: GrapesJS
users *can* build something we did not anticipate, and a fixed catalogue will, at some point, be
told "I just want the image on the left" and have no answer. A second customer with different needs
may arrive before the catalogue is broad enough.

The counter-argument, and why I still recommend it: with zero subscribers, the cost of constraining
users is hypothetical while the cost of cheap-looking output is immediate — it is the thing standing
between the product and its first customer. And the "throwing away a maintained library" framing is
weaker than it sounds, because the integration surface is only six calls (`init`, `setComponents`,
`getHtml`, `getCss`, `setStyle`, `AssetManager`). We are not discarding deep investment; we are
removing 951 KB of general-purpose canvas we were using as a text-blob editor.

**The cheap way to de-risk this:** before committing the full estimate, spend **1–2 days** building
three section types and rendering one realistic campaign page. Show it to the founder next to the
current output. If the answer to "does this live up to product expectations" is still no, the
problem is design capability, not framework — and no option in this document fixes that.

---

## Unverified

Flagged rather than asserted:

- **Puck's gzipped browser bundle size.** bundlephobia returned no data and packagephobia returned
  HTTP 429. The 1,293,615-byte figure is npm's `dist.unpackedSize` for v0.20.2 (whole package, both
  module formats, types and CSS) and is an upper bound, **not** a shipped-bundle number.
- **The curated editor's ~10–25 KB gzipped figure**, estimated from component count, not measured —
  the code does not exist.
- **Whether Tailwind Plus's licence permits** shipping its section markup in a product where end
  users compose pages from it. Verify before copying any markup.
- **Plasmic/Builder.io prices** are from 2026 third-party pricing round-ups, not the vendors' own
  pages; treat as indicative. (Directionally sufficient: any recurring per-seat cost is
  disqualifying here, so the exact figure does not change the decision.)
- **Real-world evidence of non-designer output quality** is thin for all four. The GrapesJS finding
  is one well-argued critique from the Mautic project, not a study. No comparable body of evidence
  exists for Puck or Craft.js in non-designer hands — the strongest claim I can defend is the
  structural one (a constrained editor cannot express a bad layout), not an empirical one.
- **Exactly how many live pages have a non-null `document`.** The brief says "one published page at
  minimum." The migration plan is safe for any number, since it rewrites nothing — but confirm the
  count with `select count(*) from content.landing_templates where document is not null;` before
  scheduling the legacy read-only view.

## Sources

- [GrapesJS on GitHub](https://github.com/GrapesJS/grapesjs)
- [Mautic forums — "Let's make the builder much better (incl. GrapesJS)"](https://forum.mautic.org/t/lets-make-the-builder-much-better-incl-grapesjs-and-ckeditor/37005)
- [Puck — puckeditor.com](https://puckeditor.com/) · [Puck docs](https://puckeditor.com/docs) · [Puck releases](https://github.com/puckeditor/puck/releases) · [@measured/puck on npm](https://www.npmjs.com/package/@measured/puck)
- [Puck discussion #503 — exporting to HTML/CSS](https://github.com/measuredco/puck/discussions/503)
- [Craft.js on GitHub](https://github.com/prevwong/craft.js/) · [@craftjs/core on npm](https://www.npmjs.com/package/@craftjs/core) · [Issue #507 — Future of Craft.js](https://github.com/prevwong/craft.js/issues/507)
- [Builder.io pricing round-up](https://toolradar.com/tools/builder-io/pricing) · [Plasmic pricing (Capterra)](https://www.capterra.com/p/265034/Plasmic/)
