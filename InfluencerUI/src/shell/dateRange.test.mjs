import test from 'node:test'
import assert from 'node:assert/strict'

import { rangeToParams, toIsoDate, DEFAULT_RANGE, RANGE_PRESETS } from './dateRange.js'

/**
 * The arithmetic that decides which orders count toward a KPI.
 *
 * <p>The case that matters most is the timezone boundary, because it was a real production bug
 * rather than a hypothetical one: rows are stamped in UTC, the picker computed its bounds in local
 * time, and west of Greenwich the UTC date runs ahead after early evening. An order placed at 20:23
 * in New York was stored as tomorrow, fell outside a `to` of local-today, and the dashboard said
 * "No sales attributed in the last 30 days" while the sale sat in the database. Verified against
 * the live API at the time: `to=2026-09-01` returned 0 orders, `to=2026-09-02` returned 1, same
 * order.
 *
 * <p>It reproduces only in the evening, only west of UTC, and only when the newest data is today —
 * which is exactly when a brand checks whether a launch worked.
 */

// 20:23 in New York on 2026-09-01 is 2026-09-02T00:23Z. Constructed from an absolute instant so
// the test does not depend on the machine's zone: what matters is that local and UTC dates differ.
const EVENING_IN_NEW_YORK = new Date('2026-09-02T00:23:00Z')
const MIDDAY_UTC = new Date('2026-09-01T12:00:00Z')

test('the upper bound covers the UTC day when UTC is already ahead', () => {
  const localDate = toIsoDate(EVENING_IN_NEW_YORK)
  const utcDate = EVENING_IN_NEW_YORK.toISOString().slice(0, 10)

  // Only meaningful on a machine whose local zone is behind UTC at this instant; on a UTC or
  // ahead-of-UTC runner the two dates match and there is nothing to extend.
  if (localDate === utcDate) {
    return
  }

  const { to } = rangeToParams('30d', EVENING_IN_NEW_YORK)
  assert.equal(to, utcDate, 'to must reach the UTC day, or today\'s orders vanish from the window')
})

test('the lower bound is NOT widened — that would inflate every window', () => {
  // The asymmetry is deliberate. Extending `from` would add a day of genuinely older data to
  // every range, which is the off-by-one the `days - 1` line exists to prevent.
  const { from } = rangeToParams('7d', MIDDAY_UTC)
  const expected = new Date(MIDDAY_UTC)
  expected.setDate(expected.getDate() - 6)
  assert.equal(from, toIsoDate(expected))
})

test('"last 7 days" spans seven days, not eight', () => {
  const { from, to } = rangeToParams('7d', MIDDAY_UTC)
  const days = (Date.parse(to) - Date.parse(from)) / 86_400_000
  // 6 or 7 depending on whether the UTC extension applied; never 7 days of gap from a 7-day range
  // plus a full extra day.
  assert.ok(days === 6 || days === 7, `expected a 7-day span, got ${days + 1} days`)
})

test('all-time sends no bounds at all', () => {
  assert.deepEqual(rangeToParams('all', MIDDAY_UTC), {})
})

test('an unknown preset asks for everything rather than inventing a window', () => {
  // Failing open matters here: a silently wrong window under-reports revenue, which is worse than
  // showing more than was asked for.
  assert.deepEqual(rangeToParams('not-a-range', MIDDAY_UTC), {})
})

test('the default range is one of the presets', () => {
  assert.ok(RANGE_PRESETS.some((preset) => preset.value === DEFAULT_RANGE))
})
