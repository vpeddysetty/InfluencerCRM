import test from 'node:test'
import assert from 'node:assert/strict'

import { PAGE_TEMPLATES, templateForCampaignType, applyTemplate, stripForTemplate, sectionsFromTemplate } from './pageTemplates.js'
import { SECTION_TYPES, sectionType, blankSection, tokensIn, sectionIssues } from './sectionTypes.js'

/**
 * The section vocabulary and the templates built on it (roadmap PR-39, pieces B and D).
 *
 * The properties worth pinning here are the ones whose failure is SILENT: a template naming a
 * section type that does not exist, a variant the server has no styling for, or a template switch
 * that quietly throws away copy the brand wrote.
 */

test('every template names only real section types and real variants', () => {
  for (const template of PAGE_TEMPLATES) {
    for (const [type, variant] of template.sections) {
      const spec = sectionType(type)
      assert.ok(spec, `template ${template.id} names unknown section type "${type}"`)
      if (variant) {
        assert.ok(
          spec.variants.some((v) => v.id === variant),
          `template ${template.id} names unknown variant "${variant}" for ${type}`,
        )
      }
    }
  }
})

test('each of the six campaign types has exactly one template', () => {
  const types = ['product_launch', 'creator_takeover', 'coupon_offer', 'email_signup', 'waitlist', 'affiliate']
  for (const type of types) {
    const matches = PAGE_TEMPLATES.filter((t) => t.campaignType === type)
    assert.equal(matches.length, 1, `${type} should map to exactly one template`)
    assert.equal(templateForCampaignType(type).campaignType, type)
  }
})

test('the two page-shape templates carry no campaign mapping', () => {
  // Photo-led and Story-led are shapes, not campaign kinds. Giving them a mapping would force a
  // false choice on someone whose product launch happens to be photograph-driven.
  const unmapped = PAGE_TEMPLATES.filter((t) => t.campaignType === null)
  assert.deepEqual(unmapped.map((t) => t.id).sort(), ['photo-led', 'story-led'])
})

test('every template ends with a disclosure', () => {
  // Paid-partnership disclosure is legally required in most markets. A template that omits it
  // makes the omission the default, which is exactly the wrong default.
  for (const template of PAGE_TEMPLATES) {
    const last = template.sections[template.sections.length - 1]
    assert.equal(last[0], 'legal', `template ${template.id} should end with the disclosure`)
  }
})

test('a template produces empty sections, never placeholder copy', () => {
  // Placeholder words either get published verbatim by someone in a hurry, or have to be deleted
  // before the template is usable. Both are worse than empty fields with placeholder hints.
  for (const section of sectionsFromTemplate(PAGE_TEMPLATES[0])) {
    for (const value of Object.values(section.fields)) {
      if (Array.isArray(value)) {
        assert.ok(value.every((i) => Object.keys(i).length === 0))
      } else {
        assert.equal(value, '')
      }
    }
  }
})

// ---- switching templates keeps the words -------------------------------

test('switching templates carries written copy onto the new order', () => {
  const written = [
    { ...blankSection('hero'), fields: { ...blankSection('hero').fields, headline: 'The linen shirt' } },
    { ...blankSection('offer'), fields: { ...blankSection('offer').fields, headline: '20% off' } },
  ]

  const { sections, discarded } = applyTemplate(templateForCampaignType('coupon_offer'), written)

  assert.equal(sections.find((s) => s.type === 'hero').fields.headline, 'The linen shirt')
  assert.equal(sections.find((s) => s.type === 'offer').fields.headline, '20% off')
  assert.deepEqual(discarded, [], 'nothing should be discarded when the new template has both types')
})

test("the new template's layout wins, but the old words stay", () => {
  const hero = blankSection('hero')
  const written = [{ ...hero, variant: 'centred', fields: { ...hero.fields, headline: 'Kept' } }]

  // affiliate opens with hero/split.
  const { sections } = applyTemplate(templateForCampaignType('affiliate'), written)

  assert.equal(sections[0].variant, 'split', 'the layout is the choice the user just made')
  assert.equal(sections[0].fields.headline, 'Kept', 'the words are not a layout choice')
})

test('copy with nowhere to go is reported rather than silently dropped', () => {
  const creator = blankSection('creator')
  const written = [{ ...creator, fields: { ...creator.fields, quote: 'I wear it constantly' } }]

  // email_signup has no creator section.
  const { discarded } = applyTemplate(templateForCampaignType('email_signup'), written)

  assert.equal(discarded.length, 1)
  assert.equal(discarded[0].type, 'creator')
})

test('an empty section is not reported as a loss', () => {
  // Warning about discarding a section the user never filled in trains them to ignore the
  // warning that matters.
  const { discarded } = applyTemplate(templateForCampaignType('email_signup'), [blankSection('creator')])

  assert.deepEqual(discarded, [])
})

test('two sections of the same type keep their own copy, in order', () => {
  // Photo-led carries two media sections; pairing them out of order would shuffle the captions.
  const media = blankSection('media')
  const written = [
    { ...media, fields: { ...media.fields, caption: 'first' } },
    { ...media, fields: { ...media.fields, caption: 'second' } },
  ]

  const { sections } = applyTemplate(PAGE_TEMPLATES.find((t) => t.id === 'photo-led'), written)
  const captions = sections.filter((s) => s.type === 'media').map((s) => s.fields.caption)

  assert.deepEqual(captions, ['first', 'second'])
})

// ---- saving as a template ----------------------------------------------

test('saving as a template clears the creator but keeps the tokens', () => {
  const creator = blankSection('creator')
  const offer = blankSection('offer')
  const page = [
    { ...creator, fields: { ...creator.fields, quote: 'Love it', name: 'Maya Okonjo', handle: 'mayawears', portrait: 'https://cdn.example.com/maya.jpg' } },
    { ...offer, fields: { ...offer.fields, supporting: 'Use {{coupon.code}} at checkout' } },
  ]

  const { sections } = stripForTemplate(page)

  // The quote is the creator's words about the product and survives; their identity does not,
  // because a template that credits the wrong person publishes that mistake under the brand name.
  assert.equal(sections[0].fields.quote, 'Love it')
  assert.equal(sections[0].fields.name, '')
  assert.equal(sections[0].fields.handle, '')
  assert.equal(sections[0].fields.portrait, '')
  assert.equal(sections[1].fields.supporting, 'Use {{coupon.code}} at checkout')
})

test('saving reports which assets the template now depends on', () => {
  const media = blankSection('media')
  const page = [{ ...media, fields: { ...media.fields, asset: 'https://cdn.example.com/a.png' } }]

  const { assets } = stripForTemplate(page)

  // Surfaced so the brand can be told: deleting this image later leaves a hole in every page
  // made from the template.
  assert.deepEqual(assets, ['https://cdn.example.com/a.png'])
})

// ---- tokens -------------------------------------------------------------

test('a known token is recognised and a misspelled one is flagged', () => {
  const section = blankSection('text')
  section.fields.body = 'Use {{coupon.code}} and {{coupon.cod}} today'

  const { used, unknown } = tokensIn(section)

  assert.deepEqual(used, ['{{coupon.code}}'])
  // The misspelled one is the whole point: fill() is a plain string replace, so it publishes as
  // literal text on every creator's page and nothing else in the stack would ever mention it.
  assert.deepEqual(unknown, ['{{coupon.cod}}'])
})

test('tokens inside repeated items are found too', () => {
  const proof = blankSection('proof')
  proof.fields.items = [{ title: 'Save', body: 'with {{coupon.code}}' }]

  assert.deepEqual(tokensIn(proof).used, ['{{coupon.code}}'])
})

// ---- issues mirror the server's own emptiness rules ---------------------

test('a media section with no image is reported as it will not appear', () => {
  // The renderer drops a media section with no asset. The editor has to warn about exactly that,
  // or the brand sees a section that silently vanishes when published.
  const issues = sectionIssues(blankSection('media'))

  assert.ok(issues.some((i) => i.level === 'error' && i.field === 'asset'))
})

test('a creator section with no quote is reported', () => {
  assert.ok(sectionIssues(blankSection('creator')).some((i) => i.level === 'error' && i.field === 'quote'))
})

test('missing alt text warns without blocking', () => {
  const media = blankSection('media')
  media.fields.asset = 'https://cdn.example.com/a.png'

  const issues = sectionIssues(media)

  const alt = issues.find((i) => i.field === 'altText')
  assert.ok(alt, 'empty alt text should be surfaced')
  assert.equal(alt.level, 'warning', 'but it must not stop the page rendering')
})

test('no section type offers a colour, font, size or position field', () => {
  // This is the mechanism by which the editor "cannot look wrong". If a hex field ever appears
  // here, the curated editor has become a box canvas again.
  const banned = /colou?r|font|size|width|height|margin|padding|align|position|style/i
  for (const spec of SECTION_TYPES) {
    for (const field of spec.fields) {
      assert.ok(!banned.test(field.name), `${spec.type}.${field.name} looks like a styling field`)
    }
  }
})
