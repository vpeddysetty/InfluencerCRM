# AI Campaign Page Authoring — Execution Plan

## Objective

Ship the MVP for goal-first landing page generation so a user can go from campaign brief to a publishable page without starting from a blank canvas.

This plan is intentionally designed for a solo founder-engineer. It prioritizes the smallest, highest-value slice that proves the product direction.

---

## Principle

The feature is not “a new landing page builder.”
The feature is “AI-assisted campaign-page generation with quick refinement.”

The product value is speed, clarity, and conversion quality, not maximal design flexibility.

---

## Success definition

The MVP is complete when a user can:

1. Start from a campaign goal
2. Enter a short brief
3. Receive 2–3 generated draft pages
4. Preview and choose one
5. Edit selected sections
6. Save or publish

This is the narrow definition of done.

---

## Scope recommendation

### In scope for v1
- campaign type selection
- brief form with 6–8 fields
- AI draft generation with 2–3 variants
- variant comparison UI
- section-level editing
- save as draft
- preview and publish flow
- fallback template if generation fails

### Out of scope for v1
- fully freeform builder as first experience
- advanced multi-page site generation
- autonomous brand visual design system
- sophisticated collaborative editing
- large template marketplace

---

## Recommended build order

### Week 1 — core flow

#### 1. Create campaign page flow
- Build route and screen for “Create campaign page”
- Add page type cards
- Add brief form
- Add validation for required fields
- Add loading state

#### 2. Draft generation API contract
- Define request payload model
- Define response schema with 2–3 variants
- Define section structure
- Add mock response before wiring external AI
- Add backend endpoint

#### 3. Variant comparison screen
- Render 2–3 generated cards
- Add preview button
- Add select button
- Add regenerate action placeholder
- Add conversion score badge

#### 4. Selected draft preview
- Show selected variant in full preview mode
- Add switch between desktop and mobile
- Add basic metadata summary

### Week 2 — refinement + publish

#### 5. Section editor
- Allow user to click a section
- Show section control panel
- Add actions: change headline, rewrite section, improve conversion, change CTA
- Update preview instantly

#### 6. Draft save / persist
- Save current draft state
- Persist generated sections
- Add draft list view

#### 7. Publish
- Add publish screen
- Add confirm state
- Attach page URL or staging URL
- Add final success state

### Week 3 — polish and resilience

#### 8. Fallback behavior
- If generation fails, show safe template
- Preserve brief input values
- Let user continue editing

#### 9. UX polish
- better empty states
- generation progress messages
- clearer CTA hierarchy
- more readable card layout

#### 10. Analytics / quality loop
- log variant chosen
- log publish intent
- track regeneration behavior
- gather quality signals for improvement

---

## Implementation tasks

### Task 1 — page flow and navigation
- [ ] Add route for campaign page creation
- [ ] Build screen shell with header and top-level actions
- [ ] Add page type selection card set
- [ ] Add brief form collection UI
- [ ] Add generate CTA and validation

### Task 2 — request and response model
- [ ] Define brief schema
- [ ] Define generated page variant schema
- [ ] Define section model
- [ ] Define conversion score output
- [ ] Define publish state model

### Task 3 — generation backend
- [ ] Add endpoint for draft generation
- [ ] Validate request input
- [ ] Call AI or mock service
- [ ] Return 2–3 page variants
- [ ] Ensure output schema is enforced
- [ ] Add error handling for generation failure

### Task 4 — frontend variant screen
- [ ] Render 2–3 cards
- [ ] Add preview action
- [ ] Add select action
- [ ] Add regenerate action
- [ ] Add confidence badge
- [ ] Make cards mobile-responsive

### Task 5 — draft editor
- [ ] Add selection state for sections
- [ ] Add quick edit actions for headline, copy, CTA
- [ ] Add section rewrite and regenerate actions
- [ ] Keep preview in sync with edits
- [ ] Add a compare view or undo action

### Task 6 — save and publish
- [ ] Add save draft flow
- [ ] Add publish flow
- [ ] Add success confirmation state
- [ ] Add URL or draft status display

### Task 7 — fallback and resilience
- [ ] Show fallback template if generation fails
- [ ] Preserve user inputs across failure states
- [ ] Avoid dead-end flow for user

### Task 8 — launch polish
- [ ] Add loading states
- [ ] Add empty states
- [ ] Add final CTA hierarchy cleanup
- [ ] Add mobile preview behavior

---

## Suggested data contract

### request
```json
{
  "campaignType": "product_launch",
  "goal": "Launch new product and drive first orders",
  "audience": "Women 25-40 focused on wellness",
  "offer": "15% off first order",
  "creator": "@wellnessco",
  "brandTone": "premium-clean",
  "ctaPreference": "shop_the_collection",
  "proofPoints": ["Cruelty-free", "4.9 average rating"],
  "optionalAssetRef": "https://example.com/reference.jpg"
}
```

### response
```json
{
  "variants": [
    {
      "id": "variant_a",
      "score": 91,
      "headline": "Your daily ritual, elevated.",
      "subheadline": "Clean essentials for your wellness routine.",
      "offerText": "15% off your first order",
      "ctaText": "Shop the collection",
      "sections": [
        { "type": "hero", "title": "Hero", "body": "..." },
        { "type": "offer", "title": "Offer", "body": "..." },
        { "type": "proof", "title": "Proof", "body": "..." },
        { "type": "faq", "title": "FAQ", "body": "..." }
      ]
    },
    {
      "id": "variant_b",
      "score": 88,
      "headline": "Wellness, simplified.",
      "subheadline": "A better ritual starts here.",
      "offerText": "15% off your first order",
      "ctaText": "Get started",
      "sections": []
    }
  ]
}
```

---

## UI screen summary for Claude

### Screen 1 — Create campaign page
- page type cards
- goal brief fields
- generate button
- short and clean UX

### Screen 2 — Variant selection
- 2–3 cards
- conversion score
- preview and select actions

### Screen 3 — Draft editor
- left preview, right section controls
- editable sections only
- quick rewrite actions

### Screen 4 — Publish
- summary and confirm state
- save draft / publish CTA

---

## Risks and mitigations

### Risk: AI output is weak
Mitigation: require 2–3 variants, include score, and keep the edit workflow simple.

### Risk: user feels lost
Mitigation: keep the flow guided and avoid the raw empty builder as the first step.

### Risk: too many inputs in the form
Mitigation: keep v1 to 6–8 essential fields and use smart defaults.

### Risk: generated pages look generic
Mitigation: enforce brand tone and product proof points in the prompt and validation layer.

---

## Deliverable for Claude

Give Claude the following as the handoff brief:

> Build the MVP for Tejdux AI campaign page authoring. Priority is a goal-first workflow where the user enters a short campaign brief and receives 2–3 generated page variants. The user can compare variants, choose one, edit sections, save as draft, and publish. Keep the builder as a refinement tool after generation, not before. Implement the route, data models, generation API, variant selection UI, editing panel, save draft flow, and publish confirmation state. Add fallback template behavior if generation fails. Keep the UI simple, conversion-focused, and easy for non-designers.

---

## Recommended first milestone

Ship the following before anything else:

- Create campaign page screen
- brief form
- mock generation response
- variant card list
- select draft
- save and publish action

This gives you a working proof of the core product idea with minimal surface area.
