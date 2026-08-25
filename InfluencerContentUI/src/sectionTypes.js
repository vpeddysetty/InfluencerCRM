/**
 * The curated section vocabulary (roadmap PR-39, piece B).
 *
 * <p>THIS IS A COPY. The original is `InfluencerUI/src/shell/sectionTypes.js`; this duplicate
 * exists because `InfluencerContentUI` is a separate Vite project that cannot import across
 * project roots, and production runs `VITE_USE_REMOTES=true` — so the editor a signed-in brand
 * actually loads is this one, not the bundled fallback in the shell.
 *
 * <p>`InfluencerUI/src/shell/remoteCopies.test.mjs` compares the two below their headers and
 * fails when they drift. When it does, copy the change across — do not relax the assertion.
 */

/** Field kinds the editor knows how to render an input for. */
export const FIELD_KINDS = {
  TEXT: 'text',
  TEXTAREA: 'textarea',
  ASSET: 'asset',
  ITEMS: 'items',
}

/**
 * The eight section types.
 *
 * `variants[0]` is the default for a newly added section. Every variant is designed and
 * responsive; there is deliberately no way to author a ninth.
 *
 * `tokens: true` on a field means personalization tokens are meaningful there and the editor
 * should surface them. It is not a validation rule — a token in any text field still substitutes
 * server-side — it only decides where the hint is worth showing.
 */
export const SECTION_TYPES = [
  {
    type: 'hero',
    label: 'Hero',
    hint: 'The opening. One clear promise, and the reason to keep reading.',
    variants: [
      { id: 'centred', label: 'Centred' },
      { id: 'left', label: 'Left aligned' },
      { id: 'split', label: 'Split' },
    ],
    fields: [
      { name: 'eyebrow', label: 'Eyebrow', kind: FIELD_KINDS.TEXT, placeholder: 'Limited collection' },
      { name: 'headline', label: 'Headline', kind: FIELD_KINDS.TEXT, placeholder: 'What are you selling?', required: true },
      { name: 'subheadline', label: 'Supporting line', kind: FIELD_KINDS.TEXTAREA, tokens: true },
      { name: 'ctaLabel', label: 'Button label', kind: FIELD_KINDS.TEXT, placeholder: 'Shop the collection' },
    ],
  },
  {
    type: 'media',
    label: 'Image',
    hint: 'One photograph. Full-bleed for impact, contained to sit within the text.',
    variants: [
      { id: 'contained', label: 'Contained' },
      { id: 'full-bleed', label: 'Full bleed' },
    ],
    fields: [
      { name: 'asset', label: 'Image', kind: FIELD_KINDS.ASSET, required: true },
      // Alt text is a field rather than something generated, because only the person who chose
      // the image knows what it is meant to show. An empty one is a real accessibility gap on a
      // page served to the public, so the editor nudges rather than silently accepting it.
      { name: 'altText', label: 'Alt text', kind: FIELD_KINDS.TEXT, placeholder: 'Describe the image for screen readers', recommended: true },
      { name: 'caption', label: 'Caption', kind: FIELD_KINDS.TEXT },
    ],
  },
  {
    type: 'offer',
    label: 'Offer',
    hint: 'The discount, and the code. The code itself is added automatically for each creator.',
    variants: [
      { id: 'centred', label: 'Centred' },
      { id: 'left', label: 'Left aligned' },
      { id: 'split', label: 'Split' },
    ],
    fields: [
      { name: 'headline', label: 'Headline', kind: FIELD_KINDS.TEXT, placeholder: '20% off your first order', required: true },
      { name: 'supporting', label: 'Supporting line', kind: FIELD_KINDS.TEXTAREA, tokens: true },
      { name: 'ctaLabel', label: 'Button label', kind: FIELD_KINDS.TEXT, placeholder: 'Use my code' },
    ],
  },
  {
    type: 'proof',
    label: 'Reasons to buy',
    hint: 'Two to four short reasons. Three reads best.',
    variants: [
      { id: 'grid', label: '3-up grid' },
      { id: 'stacked-list', label: 'Stacked list' },
    ],
    fields: [
      { name: 'headline', label: 'Headline', kind: FIELD_KINDS.TEXT, placeholder: 'Why people keep it' },
      {
        name: 'items',
        label: 'Reasons',
        kind: FIELD_KINDS.ITEMS,
        min: 2,
        max: 4,
        itemFields: [
          { name: 'title', label: 'Title', kind: FIELD_KINDS.TEXT, placeholder: 'Woven in Portugal' },
          { name: 'body', label: 'Detail', kind: FIELD_KINDS.TEXTAREA },
        ],
      },
    ],
  },
  {
    type: 'creator',
    label: 'Creator',
    hint: 'In their words. Cleared when you save this page as a template.',
    variants: [
      { id: 'portrait-left', label: 'Portrait left' },
      { id: 'quote-first', label: 'Quote first' },
    ],
    fields: [
      { name: 'quote', label: 'Quote', kind: FIELD_KINDS.TEXTAREA, required: true },
      { name: 'name', label: 'Name', kind: FIELD_KINDS.TEXT },
      { name: 'handle', label: 'Handle', kind: FIELD_KINDS.TEXT, placeholder: 'mayawears' },
      { name: 'platform', label: 'Platform', kind: FIELD_KINDS.TEXT, placeholder: 'Instagram' },
      { name: 'portrait', label: 'Portrait', kind: FIELD_KINDS.ASSET },
    ],
  },
  {
    type: 'signup',
    label: 'Closing call',
    // Named "Closing call" rather than "Signup" on purpose: it renders a button to the shop, not
    // an email capture form. Calling it Signup in the picker would promise a field that is not
    // there. Email capture needs an endpoint, storage and a consent record — see the plan.
    hint: 'A last chance to act, at the end of the page.',
    variants: [
      { id: 'stacked', label: 'Stacked' },
      { id: 'inline', label: 'Inline' },
    ],
    fields: [
      { name: 'headline', label: 'Headline', kind: FIELD_KINDS.TEXT, placeholder: 'Ready when you are', required: true },
      { name: 'ctaLabel', label: 'Button label', kind: FIELD_KINDS.TEXT, placeholder: 'Shop the collection' },
    ],
  },
  {
    type: 'text',
    label: 'Text',
    hint: 'Anything that needs saying at length.',
    variants: [
      { id: 'one-column', label: 'One column' },
      { id: 'two-column', label: 'Two columns' },
    ],
    fields: [
      { name: 'headline', label: 'Headline', kind: FIELD_KINDS.TEXT },
      { name: 'body', label: 'Body', kind: FIELD_KINDS.TEXTAREA, tokens: true, required: true },
    ],
  },
  {
    type: 'legal',
    label: 'Disclosure',
    // No variants: a disclosure has one correct presentation — small, quiet, at the bottom. A
    // choice here would only be a way to make a legally required notice less visible.
    hint: 'Paid partnership disclosure and terms. Required in most markets.',
    variants: [],
    fields: [
      { name: 'body', label: 'Text', kind: FIELD_KINDS.TEXTAREA, required: true },
    ],
  },
]

/** The personalization tokens the renderer substitutes, with plain-language descriptions. */
export const TOKENS = [
  { token: '{{coupon.code}}', describes: "this creator's own discount code" },
  { token: '{{discount}}', describes: 'the discount, e.g. "20% off"' },
  { token: '{{creator.name}}', describes: "the creator's name" },
  { token: '{{channel}}', describes: 'where the link was shared, e.g. Instagram' },
]

const TOKEN_PATTERN = /\{\{\s*([a-zA-Z0-9._]+)\s*\}\}/g

export function sectionType(type) {
  return SECTION_TYPES.find((t) => t.type === type) || null
}

export function sectionLabel(type) {
  return sectionType(type)?.label || type
}

/**
 * Find the tokens used in a section, and any that look like a typo.
 *
 * <p><b>Why "unknown" matters more than "used".</b> A half-typed or misspelled token is not an
 * error anywhere — `fill()` is a plain string replace, so `{{coupon.cod}}` simply never matches
 * and publishes as literal text on every creator's page. Nothing else in the stack will ever
 * mention it. Surfacing it here is the only guard, which is why this returns the unknown ones
 * rather than just highlighting the valid ones.
 */
export function tokensIn(section) {
  const known = new Set(TOKENS.map((t) => t.token.replace(/[{}]/g, '').trim()))
  const used = new Set()
  const unknown = new Set()
  const scan = (value) => {
    if (typeof value !== 'string') return
    for (const match of value.matchAll(TOKEN_PATTERN)) {
      const name = match[1]
      if (known.has(name)) used.add(`{{${name}}}`)
      else unknown.add(match[0])
    }
  }
  const fields = section?.fields || {}
  for (const value of Object.values(fields)) {
    if (Array.isArray(value)) value.forEach((item) => Object.values(item || {}).forEach(scan))
    else scan(value)
  }
  return { used: [...used], unknown: [...unknown] }
}

/** A new section of a type, with its default variant and every field empty. */
export function blankSection(type) {
  const spec = sectionType(type)
  if (!spec) return null
  const fields = {}
  for (const field of spec.fields) {
    if (field.kind === FIELD_KINDS.ITEMS) {
      fields[field.name] = Array.from({ length: field.min || 2 }, () => ({}))
    } else {
      fields[field.name] = ''
    }
  }
  return { type, variant: spec.variants[0]?.id || '', fields }
}

/**
 * Whether a section has enough in it to render.
 *
 * <p>Mirrors the server's own emptiness rules rather than inventing stricter ones: the renderer
 * drops a media section with no asset and a creator section with no quote, so the editor must
 * warn about exactly those instead of guessing. A section the brand can see in the editor but
 * which silently vanishes on the public page is the specific bug this prevents.
 */
export function sectionIssues(section) {
  const spec = sectionType(section?.type)
  if (!spec) return []
  const issues = []
  const fields = section.fields || {}
  for (const field of spec.fields) {
    const value = fields[field.name]
    const empty = field.kind === FIELD_KINDS.ITEMS
      ? !Array.isArray(value) || value.every((i) => !i || Object.values(i).every((v) => !v))
      : !value || !String(value).trim()
    if (empty && field.required) {
      issues.push({ level: 'error', field: field.name, message: `${field.label} is needed, or this section will not appear.` })
    } else if (empty && field.recommended) {
      issues.push({ level: 'warning', field: field.name, message: `${field.label} is empty.` })
    }
  }
  const { unknown } = tokensIn(section)
  for (const token of unknown) {
    issues.push({ level: 'warning', field: null, message: `${token} is not a token we recognise — it will publish exactly as written.` })
  }
  return issues
}
