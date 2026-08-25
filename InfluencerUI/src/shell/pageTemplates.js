/**
 * Built-in page templates (roadmap PR-39, piece D).
 *
 * <p><b>A template is an order of sections, not a skin.</b> It chooses which sections exist and
 * in what sequence; it never sets a colour, font or spacing. If templates could restyle, they
 * would reintroduce exactly what the curated editor removes — a way to pick something that looks
 * worse. Every template renders through the same stylesheet, so all eight are equally designed.
 *
 * <p><b>Six of the eight map to the campaign types the brief form already offers</b>, so choosing
 * a campaign type pre-selects its template and the two stay one decision rather than two. Photo-led
 * and Story-led carry no mapping on purpose: they are page *shapes* rather than campaign shapes,
 * and pretending otherwise would force a false choice on someone whose campaign is a product launch
 * that happens to be photograph-driven.
 *
 * <p>Section orders follow the landing-page patterns the design data records (hero → problem →
 * proof → CTA, social proof before the ask), not invention.
 *
 * <p>Duplicated into `InfluencerContentUI/src/pageTemplates.js`; guarded by
 * `shell/remoteCopies.test.mjs`.
 */

// Extension included: `node --test` resolves ESM strictly and Vite accepts it either way.
import { blankSection } from './sectionTypes.js'

/**
 * `sections` is a list of `[type, variant]` pairs. Content is deliberately empty — a template
 * that shipped placeholder copy would either be published verbatim by someone in a hurry, or
 * have to be deleted before it could be used. Placeholders live in the field inputs instead,
 * where they cannot be mistaken for the brand's own words.
 */
export const PAGE_TEMPLATES = [
  {
    id: 'product-launch',
    name: 'Product launch',
    campaignType: 'product_launch',
    description: 'Lead with the product, prove it, then ask.',
    sections: [
      ['hero', 'centred'],
      ['media', 'full-bleed'],
      ['proof', 'grid'],
      ['offer', 'centred'],
      ['creator', 'portrait-left'],
      ['signup', 'stacked'],
      ['legal', ''],
    ],
  },
  {
    id: 'creator-takeover',
    name: 'Creator takeover',
    campaignType: 'creator_takeover',
    description: "The creator's voice first; the brand second.",
    sections: [
      ['hero', 'left'],
      ['creator', 'quote-first'],
      ['media', 'full-bleed'],
      ['offer', 'split'],
      ['proof', 'stacked-list'],
      ['signup', 'inline'],
      ['legal', ''],
    ],
  },
  {
    id: 'coupon-offer',
    name: 'Coupon offer',
    campaignType: 'coupon_offer',
    description: 'The discount up front, for an audience already sold.',
    sections: [
      ['hero', 'centred'],
      ['offer', 'centred'],
      ['proof', 'grid'],
      ['creator', 'portrait-left'],
      ['signup', 'stacked'],
      ['legal', ''],
    ],
  },
  {
    id: 'email-signup',
    name: 'Email signup',
    campaignType: 'email_signup',
    // The closing call is a button to the shop, not a form — see sectionTypes.js. The template
    // is still useful for this campaign type: it is the shape of a page whose single job is one
    // action at the end.
    description: 'One clear action, repeated at the end.',
    sections: [
      ['hero', 'centred'],
      ['text', 'one-column'],
      ['proof', 'stacked-list'],
      ['signup', 'stacked'],
      ['legal', ''],
    ],
  },
  {
    id: 'waitlist',
    name: 'Waitlist',
    campaignType: 'waitlist',
    description: 'Anticipation: what is coming, and why it is worth waiting for.',
    sections: [
      ['hero', 'centred'],
      ['media', 'contained'],
      ['text', 'one-column'],
      ['proof', 'grid'],
      ['signup', 'stacked'],
      ['legal', ''],
    ],
  },
  {
    id: 'affiliate',
    name: 'Affiliate campaign',
    campaignType: 'affiliate',
    description: 'Built around the code, with the reasons close behind.',
    sections: [
      ['hero', 'split'],
      ['offer', 'centred'],
      ['proof', 'grid'],
      ['creator', 'portrait-left'],
      ['signup', 'inline'],
      ['legal', ''],
    ],
  },
  {
    id: 'photo-led',
    name: 'Photo-led',
    campaignType: null,
    description: 'For a product that sells on how it looks. Few words, large images.',
    sections: [
      ['hero', 'centred'],
      ['media', 'full-bleed'],
      ['offer', 'centred'],
      ['media', 'contained'],
      ['creator', 'quote-first'],
      ['signup', 'stacked'],
      ['legal', ''],
    ],
  },
  {
    id: 'story-led',
    name: 'Story-led',
    campaignType: null,
    description: 'For a product that needs explaining. Longer text, proof late.',
    sections: [
      ['hero', 'left'],
      ['text', 'two-column'],
      ['media', 'contained'],
      ['creator', 'portrait-left'],
      ['proof', 'stacked-list'],
      ['offer', 'centred'],
      ['signup', 'stacked'],
      ['legal', ''],
    ],
  },
]

export function templateById(id) {
  return PAGE_TEMPLATES.find((t) => t.id === id) || null
}

/** The template matching a campaign type, so the two stay one decision. */
export function templateForCampaignType(campaignType) {
  if (!campaignType) return null
  return PAGE_TEMPLATES.find((t) => t.campaignType === campaignType) || null
}

/** The empty sections a template describes. */
export function sectionsFromTemplate(template) {
  if (!template) return []
  return template.sections
    .map(([type, variant]) => {
      const section = blankSection(type)
      if (!section) return null
      return variant ? { ...section, variant } : section
    })
    .filter(Boolean)
}

/**
 * Apply a template to a page that already has content, keeping the words.
 *
 * <p><b>Why this is not just "replace the sections".</b> A brand that has written three headlines
 * *will* try another template, and losing that silently is the fastest way to stop them exploring.
 * So text already written stays with its section: each section in the new order claims the content
 * of the next unused section of the same type from the old page.
 *
 * <p>Matching is by type and by order — the first hero takes the first hero's words — because a
 * page can legitimately hold two of the same type (Photo-led has two media sections) and pairing
 * them out of order would shuffle the copy between them.
 *
 * @returns `{ sections, discarded }` — `discarded` lists sections whose content has nowhere to go,
 *          so the caller can warn BEFORE applying rather than reporting a loss afterwards.
 */
export function applyTemplate(template, existing = []) {
  const fresh = sectionsFromTemplate(template)
  const pool = existing.map((s) => ({ section: s, used: false }))

  const hasContent = (s) => Object.values(s?.fields || {}).some((v) =>
    Array.isArray(v)
      ? v.some((item) => Object.values(item || {}).some(Boolean))
      : String(v || '').trim())

  const sections = fresh.map((next) => {
    const match = pool.find((p) => !p.used && p.section.type === next.type)
    if (!match) return next
    match.used = true
    // The new template's VARIANT wins — that is the layout choice the user just made. Only the
    // words carry over.
    return { ...next, fields: { ...next.fields, ...match.section.fields } }
  })

  const discarded = pool
    .filter((p) => !p.used && hasContent(p.section))
    .map((p) => p.section)

  return { sections, discarded }
}

/**
 * Strip a page down to something reusable as a saved template.
 *
 * <p>Coupon tokens stay — that is the whole point of a token. Creator identity is cleared, because
 * it belongs to one campaign: a template that silently credits the wrong person would put the
 * wrong name on a public page under the brand's own name. Asset references are KEPT (they belong
 * to the brand), and returned separately so the brand can be told which images the template now
 * depends on — deleting one later would leave a hole in every page made from it.
 */
export function stripForTemplate(sections = []) {
  const CLEARED = { creator: ['name', 'handle', 'platform', 'portrait'] }
  const assets = []
  const stripped = sections.map((section) => {
    const fields = { ...section.fields }
    for (const name of CLEARED[section.type] || []) fields[name] = ''
    for (const [key, value] of Object.entries(fields)) {
      if (typeof value === 'string' && /^https?:\/\//i.test(value) && !assets.includes(value)) {
        assets.push(value)
      }
      void key
    }
    return { ...section, fields }
  })
  return { sections: stripped, assets }
}
