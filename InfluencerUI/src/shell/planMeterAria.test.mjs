import test from 'node:test'
import assert from 'node:assert/strict'

import { describeUsage, usageMeterAria } from './plan.js'

const row = (entry) => describeUsage(entry)

test('a bounded resource becomes a progressbar with real bounds', () => {
  const aria = usageMeterAria(row({ resource: 'creators', label: 'Creators', used: 3, limit: 25 }))

  assert.equal(aria.role, 'progressbar')
  assert.equal(aria['aria-valuemin'], 0)
  assert.equal(aria['aria-valuemax'], 25)
  assert.equal(aria['aria-valuenow'], 3)
  assert.equal(aria['aria-valuetext'], '3 of 25 Creators used')
})

test('an unlimited resource gets no meter', () => {
  // A meter needs a maximum. Inventing one for an unbounded plan would announce a ceiling the
  // account does not have, which is worse than announcing nothing.
  const aria = usageMeterAria(row({ resource: 'creators', label: 'Creators', used: 9, limit: null }))

  assert.equal(aria, null)
})

test('valuenow is clamped when an account sits over its limit', () => {
  // Real case: the free member cap dropped from 3 to 1 beneath accounts that already had more.
  // aria-valuenow above aria-valuemax is invalid and read unpredictably.
  const aria = usageMeterAria(row({ resource: 'members', label: 'Members', used: 3, limit: 1 }))

  assert.equal(aria['aria-valuenow'], 1)
  assert.equal(aria['aria-valuemax'], 1)
  // The true figure survives in the text, so nothing is hidden from the user.
  assert.equal(aria['aria-valuetext'], '3 of 1 Members used')
})

test('a zero limit gets no meter', () => {
  // 0 is a degenerate maximum: every value is simultaneously empty and full.
  assert.equal(usageMeterAria(row({ resource: 'x', label: 'X', used: 0, limit: 0 })), null)
})

test('missing or malformed input does not throw', () => {
  assert.equal(usageMeterAria(null), null)
  assert.equal(usageMeterAria(undefined), null)
  assert.equal(usageMeterAria(row({ resource: 'x', label: 'X', used: 1, limit: 'nonsense' })), null)
})

test('a resource at exactly its limit still reports a full meter', () => {
  const aria = usageMeterAria(row({ resource: 'pages', label: 'Landing pages', used: 5, limit: 5 }))

  assert.equal(aria['aria-valuenow'], 5)
  assert.equal(aria['aria-valuemax'], 5)
})
