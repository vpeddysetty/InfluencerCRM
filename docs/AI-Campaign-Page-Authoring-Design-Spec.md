# AI Campaign Page Authoring — Design Spec

## Objective

Turn landing-page creation from a blank-canvas design task into a campaign-goal workflow. The user starts with campaign context, not with blocks. The system generates a usable first draft, the user reviews alternatives, and then refines the winning draft with lightweight editing controls.

This is the top-priority authoring flow for Tejdux. The visual builder remains available, but it becomes the refinement layer after AI generation rather than the first step.

---

## Problem to solve

Current landing page authoring feels tool-first instead of outcome-first. A non-designer brand manager or agency operator is expected to think like a designer before they can launch a campaign page. That creates friction, slows campaign execution, and makes the product feel less like campaign infrastructure and more like a generic page builder.

The core requirement is: generate a good first draft from a campaign brief, not a blank page.

---

## Design principle

### Create from a goal, not from a canvas

The core user journey is:

1. Pick a campaign page goal
2. Provide a short brief
3. Generate 2–3 draft pages
4. Compare variants
5. Select the best draft
6. Refine sections with targeted controls
7. Publish

The builder should be optional after generation, not required before generation.

---

## User stories

### Brand manager
- As a brand manager, I want to create a campaign page from a goal so I can launch faster without needing design support.
- As a brand manager, I want to preview multiple page options so I can choose the strongest conversion-focused direction.
- As a brand manager, I want to edit only the sections that need refinement so I don’t rebuild the page from scratch.

### Agency operator
- As an agency operator, I want to create pages for multiple brands without redoing layout work.
- As an agency operator, I want to keep brand tone and offer structure consistent across campaigns.
- As an agency operator, I want the workflow to feel repeatable and fast for every client.

### Creator / campaign collaborator
- As a creator collaborator, I want to see a campaign page that reflects the offer and creator alignment.
- As a creator collaborator, I want to make small edits to the story or CTA without disrupting the whole page.

---

## Primary flow — goal-based page generation

### Screen 1 — Create campaign page

Purpose: choose the page type and start a brief.

Layout:
- Header: “Create campaign page”
- Primary CTA: “Start from campaign goal”
- Secondary CTA: “Start from template”
- Tertiary CTA: “Start from blank”
- Page archetype cards:
  - Product launch
  - Creator takeover
  - Coupon / offer
  - Email signup
  - Waitlist
  - Affiliate campaign

Recommended default: “Start from campaign goal” is the primary action.

#### Wireframe

+-----------------------------------------------------------+
| Tejdux / Campaign Pages                                  |
|  Create campaign page                                      |
|                                                           |
| [ Product launch ] [ Creator takeover ]                   |
| [ Coupon offer ] [ Email signup ]                         |
| [ Waitlist ] [ Affiliate campaign ]                       |
|                                                           |
| Quick brief:                                              |
| Goal: [ Launch new product ____ ]                         |
| Audience: [ Fitness women 25-40 ____ ]                   |
| Offer: [ 15% off first order ____ ]                      |
| Creator: [ @wellnessco ____ ]                             |
| CTA: [ Shop now ]                                         |
| Brand tone: [ Premium / playful / bold ]                 |
|                                                           |
| [ Generate page variants ]                                 |
+-----------------------------------------------------------+

---

### Screen 2 — AI brief form

Purpose: collect the minimum inputs needed to produce a strong page draft.

Fields:
- Campaign goal
- Offer or promo
- Audience segment
- Creator or ambassador name/handle
- Brand voice
- CTA preference
- Proof points or product highlights
- Optional asset / image references

Design guidance:
- Keep the form to 5–8 inputs max for version 1
- Use progressive disclosure for optional fields
- Support short descriptive text in plain language
- Include example copy in helper text

#### Suggested field set
- Goal: “Drive product discovery and purchases”
- Audience: “Women 25–40, wellness-focused”
- Offer: “15% off the first order”
- Creator: “@wellnessco”
- CTA: “Shop the collection”
- Tone: “Clean, premium”
- Key proof points: “Cruelty-free, dermatologist tested”

---

### Screen 3 — AI variants loaded

Purpose: show generated options before the user commits.

Layout:
- Header: “Choose a draft”
- Variant cards A/B/C
- Each card includes:
  - headline
  - mini section summary
  - conversion score or confidence label
  - CTA recommendation
  - mobile preview thumb

Expected content in each variant:
- hero headline
- key offer summary
- creator introduction
- product highlights
- CTA block
- social proof or testimonials
- FAQ or trust section

#### UI behavior
- User can click “Preview”
- User can click “Regenerate” on a variant
- User can choose “Edit this draft”
- User can compare side-by-side at a glance

#### Wireframe

+-----------------------------------------------------------+
| Choose a draft                               [ Regenerate ] |
|                                                           |
| Variant A   Conversion score: 91/100                     |
| Hero + creator story + offer + CTA                       |
| [ Preview ] [ Use this draft ]                            |
|                                                           |
| Variant B   Conversion score: 88/100                     |
| Story-first layout + trust + offer                       |
| [ Preview ] [ Use this draft ]                            |
|                                                           |
| Variant C   Conversion score: 84/100                     |
| Product-led format + social proof                        |
| [ Preview ] [ Use this draft ]                            |
+-----------------------------------------------------------+

---

### Screen 4 — Edit selected draft

Purpose: refine after selection without returning to the blank canvas.

Layout:
- Left column: page canvas preview
- Right column: section controls
- Toolbar:
  - Edit headline
  - Rewrite section
  - Swap image
  - Change CTA
  - Add social proof
  - Replace offer text
  - Add FAQ

Recommended editing model:
- section-level actions only
- prompts for targeted rewriting
- minimal page-level layout changes in v1

#### Key interactions
- Click section to open quick edit panel
- “Improve conversion” triggers rewrite with a sales-oriented tone
- “Match brand voice” applies brand settings
- “Generate alternate CTA” suggests variants
- “Undo” and “Compare previous version” available

---

### Screen 5 — Publish and review

Purpose: final confirmation before launch.

Sections:
- Final page summary
- CTA preview
- Mobile preview
- Save as draft
- Publish to URL or staging
- Schedule publish

Recommended controls:
- Publish now
- Save draft
- Preview mobile
- Schedule release

---

## Design system considerations

### Content blocks generated by default
Each generated page should contain a practical campaign structure:
- hero
- creator story or intro
- offer block
- key product/service proof
- social proof or metrics
- CTA block
- FAQ or trust section
- footer

### Brand controls
- brand colors
- tone values
- landing-page template preference
- CTA style
- font pairing or voice constraints

### Conversion-first guidelines
- CTA above the fold
- one primary offer
- scannable headline hierarchy
- trust stack visible early
- mobile-first clarity

---

## Review criteria

The feature is successful when:
- a user can go from brief to first draft in under 5 minutes
- the page is usable without manual redesign
- the generated draft reflects brand tone and campaign setup
- the user can edit specific sections without rebuilding the page
- the preview is convincing enough to publish

---

## MVP scope

### In scope
- create page from a goal
- generate 2–3 variants
- use selected brand tone
- section-level editing
- preview and publish
- save as draft

### Out of scope for v1
- full freeform builder as first experience
- complex layout generation across arbitrary blocks
- autonomous multi-page site generation
- advanced brand visual system creation
- full collaborative editing with simultaneous multi-user editing

---

## Engineering assumptions

- The AI authoring flow is part of the existing landing-page builder domain.
- Generation is controlled through structured inputs and output schemas.
- There should be a fallback template if AI generation fails.
- Generated drafts are treated as editable objects, not immutable outputs.
- Every page output must retain a review step before publishing.

---

## Design review checklist

Use this checklist before moving from design to implementation:

- [ ] The first screen is goal-based, not blank-canvas based
- [ ] The default flow produces usable variants quickly
- [ ] The user can compare variants without leaving the flow
- [ ] Editing is section-based, not full-layout reset
- [ ] Preview and publish are visible before deep editing
- [ ] Brand tone and CTA are modeled as prompt inputs
- [ ] The AI output is clearly reviewable and not hidden behind a final publish button

---

## Recommendation

This feature should be framed as “AI campaign page generation and conversion-first authoring” rather than “more visual builder features.” The product value is speed, clarity, and campaign execution, not simulation of a design tool.
