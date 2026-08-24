# AI Campaign Page Authoring — UI Design Draft

## Product direction

This UI should feel like a guided campaign workflow, not a blank design canvas. The user starts with a campaign goal, receives generated options, and refines the strongest draft.

Core principle: generate first, refine second, publish third.

---

## Visual design language

### Design goals
- fast and clear for non-designers
- high confidence in first draft
- crisp conversion-focused hierarchy
- minimal learning curve
- strong mobile-first preview behavior

### Style direction
- clean SaaS UI
- soft neutral backgrounds with bold accent color for primary actions
- clear spacing and card-based layout
- prominent CTA and confidence badges
- minimal but powerful controls

### Suggested theme
- background: off-white / slate-50
- panel: white with subtle gray border
- primary action: brand blue or violet
- success / confidence: green
- warning: amber
- text: dark slate
- border radius: medium-strong

---

## Screen 1 — Create campaign page

### Goal
Let the user choose the page type and start the brief quickly.

### Layout
Header row:
- left: Tejdux logo + Campaign Pages
- right: search / new page / help

Main content:
- title: Create campaign page
- subtitle: Start from a goal and generate a draft
- primary CTA: Start from campaign goal
- secondary CTA: Start from template
- tertiary CTA: Start from blank

Below that: page type cards
- Product launch
- Creator takeover
- Coupon offer
- Email signup
- Waitlist
- Affiliate campaign

### Wireframe

+--------------------------------------------------------------------------------+
| Tejdux / Campaign Pages                      [Search] [Help]                 |
|--------------------------------------------------------------------------------|
| Create campaign page                                                            |
| Start from a goal and generate a draft                                          |
|                                                                                |
| [ Start from campaign goal ]  [ Start from template ]  [ Start from blank ]    |
|                                                                                |
| Choose a page type:                                                             |
| [ Product launch ] [ Creator takeover ] [ Coupon offer ]                       |
| [ Email signup ] [ Waitlist ] [ Affiliate campaign ]                           |
|                                                                                |
| Quick brief                                                                    |
| Goal: [ Launch new product ]                                                   |
| Audience: [ Women 25-40, wellness-focused ]                                    |
| Offer: [ 15% off first order ]                                                 |
| Creator: [ @wellnessco ]                                                       |
| CTA: [ Shop the collection ]                                                   |
| Tone: [ Premium / Clean ]                                                      |
|                                                                                |
| [ Generate page variants ]                                                     |
+--------------------------------------------------------------------------------+

### Interaction notes
- Goal selection should feel like a guided flow, not a form dump.
- The visual cards should be easy to scan and click.
- The primary action should be obvious and high-contrast.

---

## Screen 2 — AI brief form

### Goal
Collect enough context for the AI to generate useful page variants without overloading the user.

### Layout
Two-column form:
- left: dynamic input fields
- right: preview summary / generated page checklist

Fields:
- Campaign goal
- Offer or promotion
- Audience segment
- Creator or ambassador
- Brand tone
- CTA preference
- Product proof points
- Optional image references

### Suggested form style
- compact labels
- helper text under each field
- chips for tone and CTA presets
- live summary panel that updates as the user types

### Wireframe

+--------------------------------------------------------------------------------+
| Campaign brief                                          [Back] [Save] |
|--------------------------------------------------------------------------------|
| Goal                                             [ Launch new product ]      |
| Audience                                         [ Women 25-40, health ]     |
| Offer                                            [ 15% off first order ]     |
| Creator                                          [ @wellnessco ]             |
| CTA                                              [ Shop the collection ]     |
| Brand tone                                       [ Premium / clean ]         |
| Proof points                                     [ Cruelty-free ] [ 4.9 avg ] |
| Optional asset reference                         [ Add reference image ]     |
|                                                                                |
| Live page summary                                                               |
| - hero headline: [Clean, elevated wellness essentials]                          |
| - offer emphasis: [15% off first order]                                        |
| - CTA: [Shop the collection]                                                   |
|                                                                                |
| [ Generate page variants ]                                                     |
+--------------------------------------------------------------------------------+

### Interaction notes
- Keep the form short and encourage natural-language input.
- Add inline examples for each field.
- Show a preview summary so the user sees how their inputs map into copy.

---

## Screen 3 — Generate variants

### Goal
Give the user a quick set of alternatives and a confidence score.

### Layout
A 3-card variant grid:
- variant A
- variant B
- variant C

Each card includes:
- conversion score
- one-line summary
- mini mock preview
- CTA recommendation
- actions: preview / choose / regenerate

### Wireframe

+--------------------------------------------------------------------------------+
| Choose a draft                                  [ Regenerate all ]       |
|--------------------------------------------------------------------------------|
|                                                                                |
| [ Variant A ]   Score 91/100                                                   |
| Hero + creator intro + offer + CTA                                             |
| [Preview] [Use this draft]                                                     |
|                                                                                |
| [ Variant B ]   Score 88/100                                                   |
| Story-first layout + product proof + trust                                   |
| [Preview] [Use this draft]                                                     |
|                                                                                |
| [ Variant C ]   Score 84/100                                                   |
| Product-led layout + social proof + FAQ                                       |
| [Preview] [Use this draft]                                                     |
|                                                                                |
+--------------------------------------------------------------------------------+

### Interaction notes
- Small preview thumbnails should be consistent and readable.
- Score visual should be simple: badge + explanation
- Users should feel they are comparing options, not reading raw AI output

---

## Screen 4 — Variant detail preview

### Goal
Give the user a closer look before committing.

### Layout
- left: large mobile/desktop preview panel
- right: detail panel with section breakdown
- settings bar: switch between mobile/desktop

### Panel content
- headline
- hero copy
- CTA
- offer block
- creator intro
- proof points
- social proof
- FAQ

### Recommended controls
- Change CTA
- Rewrite hero
- Improve conversion
- Match tone
- Add proof points
- Replace image

### Wireframe

+--------------------------------------------------------------------------------+
| Variant A / Preview                              [Desktop] [Mobile]      |
|--------------------------------------------------------------------------------|
|                                                                                |
| [ Large page preview area ]                                                    |
|        Hero headline + product offer + CTA                                     |
|                                                                                |
| [ Section breakdown ]                                                          |
| - Hero headline: “Your daily ritual, elevated.”                                |
| - Offer: “15% off your first order”                                            |
| - CTA: “Shop the collection”                                                  |
| - Creator intro                                                                  |
| - Social proof: 4.9 rating                                                     |
|                                                                                |
| [Update heading] [Rewrite section] [Improve conversion] [Swap image]           |
|                                                                                |
| [ Use this draft ] [ Save as draft ] [ Regenerate variant ]                    |
+--------------------------------------------------------------------------------+

---

## Screen 5 — Edit selected draft

### Goal
Make editing targeted and easy without exposing full builder complexity.

### Layout
- left: page canvas
- right: selected section controls
- bottom: action bar

### Editing panel
- Edit headline
- Edit CTA
- Regenerate section
- Change tone
- Add proof section
- Add FAQ
- Reorder blocks

### Interaction behavior
- tap a section to select it
- editing panel opens inline
- changes update the draft immediately
- user can compare previous and current states

### Wireframe

+--------------------------------------------------------------------------------+
| Draft editor                                        [Preview] [Publish] |
|--------------------------------------------------------------------------------|
| [Canvas preview]                              |  Section selected: Hero        |
|                                           |  Headline: Your daily ritual,  |
|                                           |  elevated.                     |
|                                           |                                |
|                                           |  [Update heading]              |
|                                           |  [Rewrite section]             |
|                                           |  [Improve conversion]          |
|                                           |  [Change CTA]                  |
|                                           |                                |
|                                           |  Brand tone: Premium/clean     |
|                                           |                                |
|                                           |  [Add proof section]           |
|                                           |  [Add FAQ]                    |
|--------------------------------------------------------------------------------|
| [ Undo ] [ compare ] [ save draft ] [ publish ]                                 |
+--------------------------------------------------------------------------------+

---

## Screen 6 — Publish / launch

### Goal
Final confirmation before live publishing.

### Layout
- left: CTA summary
- right: publish controls
- bottom: schedule / staging / publish CTA

### Controls
- Publish now
- Save draft
- Schedule release
- Open preview
- Send to collaborator

### Wireframe

+--------------------------------------------------------------------------------+
| Review and publish                                         [Back]         |
|--------------------------------------------------------------------------------|
| Publish summary                                                                  |
| - URL: brand.example.com/campaign-launch                                       |
| - CTA: Shop the collection                                                     |
| - Mobile layout: Ready                                                          |
| - Conversion-focused copy: Approved                                             |
|                                                                                |
| [ Preview page ] [ Save as draft ] [ Schedule ]                                 |
|                                                                                |
| [ Publish now ]                                                                  |
+--------------------------------------------------------------------------------+

---

## Components to build

### Primary components
- campaign goal selector
- brief form card
- tone and CTA chips
- variant cards
- conversion score badge
- section editor panel
- preview state switcher
- publish summary panel

### Secondary UI elements
- empty states
- loading states for generation
- regeneration animation
- error state for failed generation
- fallback template path

---

## UX notes

### Loading states
Use a clear generation state with message like:
- Building your campaign page
- Comparing good options
- Finalizing the strongest draft

### Error states
If AI generation fails:
- offer a fallback template
- tell the user what can be edited
- preserve their brief inputs

### Fallback behavior
The app should never leave the user in a broken state. If generation fails, show a safe template page and let them edit from there.

---

## Recommended interaction flow

1. User clicks Create campaign page
2. User selects type and fills brief
3. System generates 2–3 variants
4. User compares cards and previews one variant
5. User selects a draft
6. User edits key sections
7. User previews on mobile and desktop
8. User publishes or saves draft

This is the default flow for v1.

---

## Success criteria for the UI

The UI is successful if:
- a user can create a page in minutes, not hours
- the first draft feels usable immediately
- the user never feels like they are staring at an empty editor
- the strongest draft is clear and easy to compare
- editing is section-based and not overwhelming

---

## Design conclusion

The UI should help the user move from objective to published page quickly. The first screen should not feel like a blank builder. It should feel like a campaign assistant that helps turn a brief into a living page draft.
