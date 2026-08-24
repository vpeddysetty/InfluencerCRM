# AI Campaign Page Authoring — Implementation Checklist

## Objective

Build the goal-first campaign landing page workflow as a minimal but shippable feature. The user enters campaign context, gets 2–3 generated page drafts, selects one, refines sections, and publishes.

This is the implementation brief for execution with Claude or an engineering partner.

---

## Phase 1 — MVP definition

### Goal and scope
- Build a campaign page creation flow from brief to published page
- Keep the visual builder as a refinement surface after initial generation
- Prioritize conversion-focused page drafts over raw design freedom

### MVP in one sentence
A user can create a campaign landing page from a short goal brief, review 2–3 generated variants, pick one, edit selected sections, and publish.

---

## Workstreams

### 1) Product / UX requirements
- [ ] Define the initial set of campaign types: product launch, creator takeover, coupon offer, email capture, waitlist, affiliate campaign
- [ ] Confirm required prompt fields for v1
- [ ] Confirm required page structure: hero, offer, creator intro, proof, CTA, FAQ
- [ ] Confirm fallback behavior if generation fails
- [ ] Confirm preview and publish rules
- [ ] Confirm brand tone and CTA presets

### 2) Backend / API requirements
- [ ] Create campaign brief request schema
- [ ] Create generated page draft response schema
- [ ] Add variant generation endpoint
- [ ] Add draft save endpoint
- [ ] Add publish endpoint
- [ ] Add section-level rewrite endpoint
- [ ] Add safe fallback template generation
- [ ] Implement audit log for generated versions

### 3) AI integration requirements
- [ ] Prompt template for campaign page generation
- [ ] Structured output schema for page sections
- [ ] Validation layer to ensure required sections exist
- [ ] CTA recommendation output
- [ ] Conversion confidence score output
- [ ] Tone enforcement based on brand settings
- [ ] Guardrails for unsupported claims or false product messages

### 4) Frontend UI requirements
- [ ] Screen 1: Create campaign page
- [ ] Screen 2: AI brief form
- [ ] Screen 3: Variant comparison screen
- [ ] Screen 4: Detailed draft preview
- [ ] Screen 5: Section editing panel
- [ ] Screen 6: Publish summary and launch screen
- [ ] Loading states for generation
- [ ] Error state and fallback template fallback
- [ ] Preview switcher: desktop / mobile

---

## Data model checklist

### Campaign brief model
- [ ] id
- [ ] userId
- [ ] brandId
- [ ] campaignType
- [ ] goal
- [ ] audience
- [ ] offer
- [ ] creatorHandle
- [ ] brandTone
- [ ] ctaPreference
- [ ] proofPoints
- [ ] optionalAssets
- [ ] createdAt

### Generated draft model
- [ ] id
- [ ] briefId
- [ ] variantId
- [ ] pageType
- [ ] status
- [ ] headline
- [ ] subheadline
- [ ] offerText
- [ ] ctaText
- [ ] sections[]
- [ ] conversionScore
- [ ] metadata
- [ ] isSelected
- [ ] createdAt

### Section model
- [ ] id
- [ ] type
- [ ] title
- [ ] body
- [ ] order
- [ ] variant
- [ ] editable

### Publish model
- [ ] draftId
- [ ] publishedUrl
- [ ] publishStatus
- [ ] scheduledAt
- [ ] publishedAt

---

## API checklist

### POST /campaign-pages/generate
- [ ] Accept campaign brief object
- [ ] Validate required fields
- [ ] Call AI generation service
- [ ] Return 2–3 variants with content structure
- [ ] Include conversion scores and section metadata
- [ ] Return 400 if required fields are missing

### GET /campaign-pages/:id/variants
- [ ] Fetch saved variants for a draft
- [ ] Return variant list and selection state

### POST /campaign-pages/:id/variants/:variantId/select
- [ ] Mark one variant as active
- [ ] Persist selection

### POST /campaign-pages/:id/sections/:sectionId/regenerate
- [ ] Accept rewrite instructions
- [ ] Apply targeted regeneration to one section
- [ ] Return updated section

### POST /campaign-pages/:id/publish
- [ ] Validate selected draft
- [ ] Publish page content to public URL or staging environment
- [ ] Return final URL and status

### POST /campaign-pages/:id/save
- [ ] Save current draft as working state
- [ ] Keep version history

---

## Frontend checklist

### Screen 1 — Create campaign page
- [ ] Title and subtitle copy
- [ ] Primary CTA emphasized
- [ ] Secondary and tertiary CTAs present
- [ ] Page type cards rendered
- [ ] Card interactions update state
- [ ] Quick brief form fields visible
- [ ] Generate button enabled only when minimum requirements are met
- [ ] Loading state displayed while generating

### Screen 2 — AI brief form
- [ ] Prompt fields visible in clear order
- [ ] Helper copy shown for each field
- [ ] Live summary panel updates on input change
- [ ] Tone and CTA chips are clickable
- [ ] Optional fields are collapsible or secondary
- [ ] Generate action has a strong visual focus

### Screen 3 — Variant comparison screen
- [ ] 2–3 variants rendered as cards
- [ ] Each card has mini preview and metadata
- [ ] Conversion score badge visible
- [ ] Preview action per card
- [ ] Use-this-draft CTA per card
- [ ] Regenerate action per card or global

### Screen 4 — Detailed preview
- [ ] Desktop/mobile toggle available
- [ ] Large live preview rendered accurately
- [ ] Section summary visible on side panel
- [ ] Selected layout is readable and not overloaded
- [ ] CTA and proof points visible above the fold

### Screen 5 — Editor panel
- [ ] Clicking a section selects it
- [ ] Right-side panel updates based on selection
- [ ] Section rewrite controls available
- [ ] Section regeneration button functions
- [ ] CTA or copy updates visible immediately
- [ ] Undo / compare available

### Screen 6 — Publish screen
- [ ] Final page summary present
- [ ] Publish controls clear and prominent
- [ ] Save-draft control available
- [ ] Schedule option exists if needed
- [ ] Confirmation or success state after publish

---

## Component checklist

### Reusable components
- [ ] CampaignTypeCard
- [ ] BriefField
- [ ] ToneChip
- [ ] CTAPresetChip
- [ ] VariantCard
- [ ] ConversionBadge
- [ ] SectionEditorPanel
- [ ] DraftPreviewCanvas
- [ ] PublishSummaryCard
- [ ] LoadingState
- [ ] ErrorState
- [ ] FallbackTemplateBanner

### State management checklist
- [ ] selectedCampaignType
- [ ] briefFormState
- [ ] generatedVariants
- [ ] selectedVariantId
- [ ] draftSections
- [ ] isGenerating
- [ ] isPublishing
- [ ] publishStatus
- [ ] errorState

---

## AI output validation checklist

- [ ] Output has required sections
- [ ] CTA exists and is clear
- [ ] Offer is emphasized
- [ ] Headline is not generic filler
- [ ] Proof points are specific and realistic
- [ ] Page includes trust or social proof
- [ ] Tone matches selected brand settings
- [ ] Page is readable on mobile
- [ ] No unsupported claims or fake product facts
- [ ] Output schema validates before saving

---

## Edge-case checklist

- [ ] No brand tone supplied → use default tone
- [ ] No creator handle supplied → omit creator intro section
- [ ] Missing offer text → fallback to generic CTA section
- [ ] AI generation fails → show fallback template and retain brief
- [ ] Publish action without selected variant → block and prompt user
- [ ] User edits section but not save → keep unsaved draft in working state

---

## QA checklist

### Flow validation
- [ ] User can create page from goal path
- [ ] User can generate 2–3 variants
- [ ] User can compare variants
- [ ] User can select a variant
- [ ] User can edit a selected section
- [ ] User can preview mobile and desktop
- [ ] User can publish successfully
- [ ] User can save as draft

### Visual validation
- [ ] Primary CTA is prominent
- [ ] Page hierarchy is clear
- [ ] No blank-canvas confusion in first screen
- [ ] Layout remains readable on mobile
- [ ] Generated page sections feel campaign-ready

### Business validation
- [ ] Generated page is conversion-oriented, not just decorative
- [ ] Offer is visible early
- [ ] CTA is front and center
- [ ] User can complete the journey without design expertise

---

## Suggested implementation sequence

### Sprint 1
- [ ] Build campaign brief form and routing
- [ ] Create generator API contract and mock response
- [ ] Render variant cards UI
- [ ] Add selection and preview flow

### Sprint 2
- [ ] Add section-level editor
- [ ] Add CTA and copy rewrite actions
- [ ] Add save draft and publish flow
- [ ] Add fallback generation path

### Sprint 3
- [ ] Improve tone fidelity and brand guardrails
- [ ] Add conversion scoring explanation
- [ ] Improve mobile preview and editing
- [ ] Add analytics around variant selection and publish behavior

---

## Prompt for Claude / execution handoff

Use this prompt to hand off to Claude:

> Build the AI campaign page authoring flow for Tejdux. The user starts from a campaign goal, fills a short brief, and gets 2–3 generated page variants. The user chooses one, edits selected sections, previews mobile and desktop, saves as draft, and publishes. Keep the visual builder as a refinement layer after generation, not the first step. Implement the MVP with a campaign brief model, AI generation endpoint, generated draft schema, variant selection flow, section-level edits, and publish flow. Use a clean SaaS UI with strong CTA hierarchy. If generation fails, show a fallback template and keep the user in a safe working state.

---

## Final handoff note

The first milestone is not “build the full design builder.” The first milestone is: create a working campaign page from a brief, compare versions, and publish one of them.
