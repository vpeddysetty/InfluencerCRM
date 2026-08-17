import test from 'node:test'
import assert from 'node:assert/strict'

import { CARRIED_FIELDS, metricsFromLookup, lookupMatchesHandle } from './handleLookup.js'

// A resolved Instagram lookup, shaped as CreatorOnboardingService.resolveHandle returns it.
// demographics is absent on purpose: business_discovery cannot supply it for a creator who has
// not authorised the app, so the adapter leaves it null rather than inventing a distribution.
const RESOLVED = {
  handle: 'aririvera',
  platform: 'instagram',
  resolved: true,
  followerCount: 48210,
  engagementRate: 3.42,
  averageViews: 15300,
  lastActiveAt: '2026-08-14T10:00:00Z',
  metricsSource: 'platform_api',
  metricsFetchedAt: '2026-08-16T12:00:00Z',
  // Classification is NESTED and snake_case on this endpoint — the flat camelCase shape an earlier
  // version of this fixture used does not exist on any real response.
  classification: {
    niche: 'fitness',
    content_themes: ['gym', 'nutrition'],
    source: 'llm',
  },
}

test('carries the platform-reported metrics', () => {
  const metrics = metricsFromLookup(RESOLVED)
  assert.equal(metrics.followerCount, 48210)
  assert.equal(metrics.engagementRate, 3.42)
  assert.equal(metrics.averageViews, 15300)
  assert.deepEqual(metrics.contentThemes, ['gym', 'nutrition'])
})

test('the provenance stamp travels with the numbers', () => {
  const metrics = metricsFromLookup(RESOLVED)
  // The whole point. A follower count without its source renders as Unknown forever, and a
  // simulated figure becomes indistinguishable from one Instagram answered with.
  assert.equal(metrics.metricsSource, 'platform_api')
  assert.equal(metrics.metricsFetchedAt, '2026-08-16T12:00:00Z')
})

test('a simulated lookup stays labelled as simulated', () => {
  const metrics = metricsFromLookup({ ...RESOLVED, metricsSource: 'mock' })
  // The adapter falls back to the simulation whenever it is not fully configured. That fallback
  // must remain visible on the saved row: `mock` is what renders the warning badge.
  assert.equal(metrics.metricsSource, 'mock')
})

test('does not forward request bookkeeping as creator columns', () => {
  const metrics = metricsFromLookup(RESOLVED)
  assert.equal(metrics.resolved, undefined)
  assert.equal(metrics.reason, undefined)
  // handle and platform are the form's own fields; the lookup must not fight the user for them.
  assert.equal(metrics.handle, undefined)
  assert.equal(metrics.platform, undefined)
})

test('an unresolved handle contributes nothing', () => {
  // Null rather than an object of blanks: "nobody could be found" must not be recorded as
  // "this creator has no audience", which is what a zeroed follower count would claim.
  assert.equal(metricsFromLookup({ resolved: false, reason: 'Not found' }), null)
  assert.equal(metricsFromLookup(null), null)
  assert.equal(metricsFromLookup(undefined), null)
})

test('absent fields are omitted rather than written as null', () => {
  const metrics = metricsFromLookup(RESOLVED)
  // demographics is null for a discovered creator by design. Writing the null would overwrite
  // anything already known about them.
  assert.ok(!('audienceDemographics' in metrics))
  assert.ok(!('averageViews' in metricsFromLookup({ ...RESOLVED, averageViews: null })))
})

test('demographics are serialized to text for the jsonb column', () => {
  // Regression: production returned 400 "Cannot deserialize value of type String from Object
  // value" on every save after a resolved lookup. audience_demographics is jsonb mapped to a Java
  // String on the DAO entity, so an object here rejects the WHOLE creator, not just the field.
  const metrics = metricsFromLookup({
    ...RESOLVED,
    audienceDemographics: { age: { '18-24': 0.4 }, country: { US: 0.6 } },
  })
  assert.equal(typeof metrics.audienceDemographics, 'string')
  assert.deepEqual(JSON.parse(metrics.audienceDemographics), {
    age: { '18-24': 0.4 },
    country: { US: 0.6 },
  })
})

test('an already-serialized demographics string is left alone', () => {
  // Double-encoding would store a quoted blob that reads back as a string, not an object.
  const already = '{"age":{"25-34":0.5}}'
  const metrics = metricsFromLookup({ ...RESOLVED, audienceDemographics: already })
  assert.equal(metrics.audienceDemographics, already)
})

test('unserializable demographics are dropped, not saved broken', () => {
  const cyclic = {}
  cyclic.self = cyclic
  const metrics = metricsFromLookup({ ...RESOLVED, audienceDemographics: cyclic })
  // The creator still saves; only the enrichment is lost.
  assert.ok(!('audienceDemographics' in metrics))
  assert.equal(metrics.followerCount, 48210)
})

test('nested snake_case classification is flattened to the DAO shape', () => {
  // resolveHandle nests this under `classification` with the model's own key names; captureLead
  // flattens the same object to camelCase columns. Reading the flat names off the preview response
  // matched nothing, so niche and themes were silently dropped from every saved creator.
  const metrics = metricsFromLookup({
    ...RESOLVED,
    classification: {
      niche: 'fitness',
      content_themes: ['gym', 'nutrition'],
      risk_flags: ['none'],
      summary: 'Fitness creator posting workouts.',
      source: 'llm',
    },
  })
  assert.equal(metrics.niche, 'fitness')
  assert.deepEqual(metrics.contentThemes, ['gym', 'nutrition'])
  // Both columns, in step — the directory reads the older one.
  assert.deepEqual(metrics.contentCategories, ['gym', 'nutrition'])
  assert.deepEqual(metrics.riskFlags, ['none'])
  assert.equal(metrics.safetyNotes, 'Fitness creator posting workouts.')
  assert.equal(metrics.classificationSource, 'llm')
})

test('classification provenance stays separate from metrics provenance', () => {
  // A platform answering about followers and a model guessing at content are different claims.
  const metrics = metricsFromLookup({
    ...RESOLVED,
    metricsSource: 'platform_api',
    classification: { niche: 'beauty', source: 'llm' },
  })
  assert.equal(metrics.metricsSource, 'platform_api')
  assert.equal(metrics.classificationSource, 'llm')
})

test('an absent classifier leaves no classification fields', () => {
  // classify() returns null when the agent is down, and that must not write empty columns.
  const { classification, ...withoutClassifier } = RESOLVED
  const metrics = metricsFromLookup(withoutClassifier)
  assert.equal(metrics.followerCount, 48210)   // the metrics still land
  assert.ok(!('niche' in metrics))
  assert.ok(!('classificationSource' in metrics))
})

test('a follower count of zero survives', () => {
  // Zero followers is a real measurement and a different fact from "we did not look". A truthiness
  // filter here would silently discard it.
  const metrics = metricsFromLookup({ ...RESOLVED, followerCount: 0 })
  assert.equal(metrics.followerCount, 0)
})

test('the preview follows the handle it was read for', () => {
  assert.ok(lookupMatchesHandle('aririvera', 'aririvera'))
  // Same account, typed differently — hiding the panel over an @ or a capital would read as a bug.
  assert.ok(lookupMatchesHandle('aririvera', '@AriRivera'))
  assert.ok(lookupMatchesHandle('@ari', ' ari '))
  // A different creator entirely: the numbers on screen are not theirs.
  assert.ok(!lookupMatchesHandle('aririvera', 'someoneelse'))
  assert.ok(!lookupMatchesHandle('', 'aririvera'))
  assert.ok(!lookupMatchesHandle('aririvera', ''))
})

test('the carried list stays an allow-list', () => {
  // A guard against someone "simplifying" this to a spread of the response later: the two
  // provenance fields are the ones whose loss is silent and permanent.
  assert.ok(CARRIED_FIELDS.includes('metricsSource'))
  assert.ok(CARRIED_FIELDS.includes('metricsFetchedAt'))
  assert.ok(!CARRIED_FIELDS.includes('resolved'))
})
