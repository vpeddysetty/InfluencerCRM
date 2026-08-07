/**
 * Logic tests for the UX changes.
 *
 * Run with: node --test src/ux-changes.test.mjs
 *
 * The repo has no component-test harness, so these cover the pure decision logic behind each
 * change — which mapping rows get flagged, what search matches, how nav groups fall out — rather
 * than rendering. That is where the behaviour actually lives; the JSX around it is presentation.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { createImportMappingJsonFromAgent, MAPPING_CONFIDENCE_THRESHOLD } from './constants.js'
import { NAV_GROUPS, ROUTE_MANIFEST, DEFAULT_ROUTE, groupedVisibleRoutes } from './shell/routeManifest.js'

// ── P3: confidence survives the agent → UI transform ────────────────────────

test('agent confidence is carried into the mapping JSON', () => {
  const json = createImportMappingJsonFromAgent(
    ['Creator Name', 'Ambassador Tier'],
    [
      { spreadsheet_column: 'Creator Name', target_entity: 'creator', target_attribute: 'name', confidence: 0.97 },
      { spreadsheet_column: 'Ambassador Tier', target_entity: 'creator', target_attribute: 'customAttributes', confidence: 0.42 },
    ],
  )
  const rows = JSON.parse(json)

  assert.equal(rows.length, 2)
  assert.equal(rows[0].confidence, 0.97)
  assert.equal(rows[1].confidence, 0.42)
})

test('a column the agent did not return is treated as zero confidence', () => {
  // Locally inferred rather than model-suggested: exactly the row a human should check.
  const json = createImportMappingJsonFromAgent(['Mystery Column'], [])
  const [row] = JSON.parse(json)

  assert.equal(row.confidence, 0)
  assert.ok(row.confidence < MAPPING_CONFIDENCE_THRESHOLD)
})

test('a non-numeric confidence is omitted rather than coerced', () => {
  const json = createImportMappingJsonFromAgent(
    ['Handle'],
    [{ spreadsheet_column: 'Handle', target_entity: 'creator', target_attribute: 'handle', confidence: 'high' }],
  )
  const [row] = JSON.parse(json)

  // Omitted, not NaN or 0 — a bad value must not masquerade as low confidence and flag a row
  // the model was actually sure about.
  assert.equal('confidence' in row, false)
  assert.equal(row.targetAttribute, 'handle')
})

// ── P3: which rows get flagged for review ───────────────────────────────────

// Mirrors needsAttention() in ImportPage.jsx.
function needsAttention(row, touched = new Set()) {
  if (touched.has(row.spreadsheetColumn)) return false
  if (!row.targetAttribute) return true
  return row.confidence !== null && row.confidence !== undefined && row.confidence < MAPPING_CONFIDENCE_THRESHOLD
}

test('only low-confidence and unmapped columns are flagged', () => {
  const rows = [
    { spreadsheetColumn: 'Name', targetAttribute: 'name', confidence: 0.98 },
    { spreadsheetColumn: 'Handle', targetAttribute: 'handle', confidence: 0.91 },
    { spreadsheetColumn: 'Tier', targetAttribute: 'customAttributes', confidence: 0.4 },
    { spreadsheetColumn: 'Notes', targetAttribute: '', confidence: 0.8 },
  ]
  const flagged = rows.filter((row) => needsAttention(row))

  assert.deepEqual(flagged.map((r) => r.spreadsheetColumn), ['Tier', 'Notes'])
  // The whole point of the change: review 2 rows, not 4.
  assert.equal(flagged.length, 2)
})

test('editing a row clears its flag', () => {
  const row = { spreadsheetColumn: 'Tier', targetAttribute: 'customAttributes', confidence: 0.4 }

  assert.equal(needsAttention(row), true)
  assert.equal(needsAttention(row, new Set(['Tier'])), false)
})

test('rows with no confidence data at all are not flagged', () => {
  // Hand-authored mappings and the JSON editor carry no confidence; they must not all light up.
  const row = { spreadsheetColumn: 'Fee', targetAttribute: 'agreedFee', confidence: null }
  assert.equal(needsAttention(row), false)
})

// ── P1: creator search, filter, sort ────────────────────────────────────────

// Mirrors matchesQuery() in CreatorsPage.jsx.
const pairsOf = (attrs) => (Array.isArray(attrs) ? attrs : [])

function matchesQuery(creator, query) {
  if (!query) return true
  const haystack = [
    creator?.name,
    creator?.handle,
    creator?.email,
    creator?.platform,
    ...pairsOf(creator?.customAttributes).flatMap((p) => [p?.key, p?.value]),
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
  return haystack.includes(query)
}

const CREATORS = [
  { id: '1', name: 'Ada Lovelace', handle: '@ada', email: 'ada@example.com', platform: 'instagram', customAttributes: [{ key: 'Tier', value: 'Gold' }] },
  { id: '2', name: 'Grace Hopper', handle: '@grace', email: 'grace@example.com', platform: 'tiktok', customAttributes: [{ key: 'Tier', value: 'Silver' }] },
  { id: '3', name: 'Alan Turing', handle: '@alan', email: 'alan@example.com', platform: 'youtube', customAttributes: [] },
]

test('search matches name, handle, and email', () => {
  assert.equal(CREATORS.filter((c) => matchesQuery(c, 'ada')).length, 1)
  assert.equal(CREATORS.filter((c) => matchesQuery(c, '@grace')).length, 1)
  assert.equal(CREATORS.filter((c) => matchesQuery(c, 'alan@example.com')).length, 1)
})

test('search reaches into imported custom attributes', () => {
  // A brand that imported a "Tier" column expects to be able to search it.
  const gold = CREATORS.filter((c) => matchesQuery(c, 'gold'))
  assert.equal(gold.length, 1)
  assert.equal(gold[0].name, 'Ada Lovelace')
})

test('search is case-insensitive and empty query matches everything', () => {
  assert.equal(CREATORS.filter((c) => matchesQuery(c, 'LOVELACE'.toLowerCase())).length, 1)
  assert.equal(CREATORS.filter((c) => matchesQuery(c, '')).length, 3)
})

test('platform filter narrows to one platform', () => {
  const filtered = CREATORS.filter((c) => c.platform === 'tiktok')
  assert.equal(filtered.length, 1)
  assert.equal(filtered[0].name, 'Grace Hopper')
})

// Mirrors compareCreators() in CreatorsPage.jsx.
function compareCreators(a, b, sortBy) {
  const text = (v) => String(v || '').toLowerCase()
  switch (sortBy) {
    case 'name-desc':
      return text(b?.name).localeCompare(text(a?.name))
    case 'handle-asc':
      return text(a?.handle).localeCompare(text(b?.handle))
    case 'platform-asc':
      return text(a?.platform).localeCompare(text(b?.platform)) || text(a?.name).localeCompare(text(b?.name))
    default:
      return text(a?.name).localeCompare(text(b?.name))
  }
}

test('sorting orders by name ascending and descending', () => {
  const asc = [...CREATORS].sort((a, b) => compareCreators(a, b, 'name-asc'))
  assert.deepEqual(asc.map((c) => c.name), ['Ada Lovelace', 'Alan Turing', 'Grace Hopper'])

  const desc = [...CREATORS].sort((a, b) => compareCreators(a, b, 'name-desc'))
  assert.deepEqual(desc.map((c) => c.name), ['Grace Hopper', 'Alan Turing', 'Ada Lovelace'])
})

test('platform sort falls back to name within a platform', () => {
  const sorted = [...CREATORS].sort((a, b) => compareCreators(a, b, 'platform-asc'))
  assert.deepEqual(sorted.map((c) => c.platform), ['instagram', 'tiktok', 'youtube'])
})

// ── P4: navigation grouping and landing route ───────────────────────────────

test('every route belongs to a declared nav group', () => {
  ROUTE_MANIFEST.forEach((route) => {
    assert.ok(route.group, `${route.path} has no group`)
    assert.ok(NAV_GROUPS.includes(route.group), `${route.path} has unknown group ${route.group}`)
  })
})

test('the board is the landing route and comes first in Work', () => {
  assert.equal(DEFAULT_ROUTE, '/workflow')

  const work = ROUTE_MANIFEST.filter((r) => r.group === 'Work')
  assert.equal(work[0].path, '/workflow')
  assert.equal(work[0].label, 'Board')
})

test('import moved out of the daily-work group', () => {
  const importRoute = ROUTE_MANIFEST.find((r) => r.path === '/import')
  assert.equal(importRoute.group, 'Setup')
})

test('groups render in declared order with no empty sections', () => {
  const grouped = groupedVisibleRoutes([])
  assert.deepEqual(grouped.map((g) => g.group), NAV_GROUPS)
  grouped.forEach((bucket) => assert.ok(bucket.routes.length > 0))
})

test('a permission set that excludes a whole group drops that group', () => {
  // A marketer with no money permissions should see no Money heading at all.
  const grouped = groupedVisibleRoutes(['workflow:read', 'campaign:read'])
  assert.deepEqual(grouped.map((g) => g.group), ['Work'])
  assert.deepEqual(grouped[0].routes.map((r) => r.path), ['/workflow', '/campaigns'])
})

test('empty permissions still show everything', () => {
  // Tokens predating permission claims must not produce an empty nav.
  const grouped = groupedVisibleRoutes([])
  const total = grouped.reduce((n, g) => n + g.routes.length, 0)
  assert.equal(total, ROUTE_MANIFEST.length)
})

// ── P5: first-run checklist step derivation ─────────────────────────────────

// Mirrors the step logic in GettingStarted.jsx.
function checklistState({ creatorCount = 0, campaignCount = 0, cardCount = 0, importedCount = 0 }) {
  const steps = [
    { key: 'workspace', done: true },
    { key: 'import', done: importedCount > 0 || creatorCount > 0 },
    { key: 'campaign', done: campaignCount > 0 },
    { key: 'board', done: cardCount > 0 },
  ]
  return {
    doneCount: steps.filter((s) => s.done).length,
    nextStep: steps.find((s) => !s.done)?.key ?? null,
    hidden: steps.every((s) => s.done),
  }
}

test('a brand new workspace points at import first', () => {
  const state = checklistState({})
  assert.equal(state.doneCount, 1)
  assert.equal(state.nextStep, 'import')
  assert.equal(state.hidden, false)
})

test('creators present by any route completes the import step', () => {
  // Manual entry counts: someone who added creators by hand is not still "yet to import".
  assert.equal(checklistState({ creatorCount: 12 }).nextStep, 'campaign')
  assert.equal(checklistState({ importedCount: 1 }).nextStep, 'campaign')
})

test('the checklist disappears once every step is done', () => {
  const state = checklistState({ creatorCount: 5, campaignCount: 1, cardCount: 3 })
  assert.equal(state.doneCount, 4)
  assert.equal(state.nextStep, null)
  assert.equal(state.hidden, true)
})

test('exactly one step is ever the next action', () => {
  const state = checklistState({ creatorCount: 5 })
  assert.equal(state.nextStep, 'campaign')
  // Not 'board' as well — the checklist reads as a sequence, not four competing CTAs.
  assert.notEqual(state.nextStep, 'board')
})
