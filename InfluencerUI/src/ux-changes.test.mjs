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
import { EVENTS, analyticsProvider, identify, resetIdentity, track } from './api/analytics.js'
import { toCsv } from './api/csv.js'
import { rangeToParams, toIsoDate } from './shell/dateRange.js'
import { accessTokenExpiryMs, msUntilRefresh, REFRESH_LEAD_MS } from './shell/sessionExpiry.js'
import { formatFetchedAt, isPlatformVerified } from './shell/provenance.js'
import {
  PUBLIC_TIERS,
  UNLIMITED,
  describePlan,
  describeUsage,
  formatUsage,
  pressuredResources,
  usageMessage,
  usageTone,
  describeSubscription,
  formatAmount,
  formatDate,
  visiblePublicTiers,
} from './shell/plan.js'

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

  // Setup survives on /settings alone, which carries NO permission: it manages the caller's own
  // sign-in methods, not the workspace, and every operation behind it is scoped server-side to the
  // user in the token. Money is still absent, which is what this test is actually about — a group
  // whose every route is gated disappears for someone holding none of those gates.
  assert.deepEqual(grouped.map((g) => g.group), ['Work', 'Setup'])
  assert.deepEqual(grouped[0].routes.map((r) => r.path), ['/workflow', '/campaigns'])
  assert.deepEqual(grouped[1].routes.map((r) => r.path), ['/settings'])
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

// ── Audit remediation ───────────────────────────────────────────────────────

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const SRC = dirname(fileURLToPath(import.meta.url))
const read = (relative) => readFileSync(join(SRC, relative), 'utf8')

test('the kanban grid sizes itself to however many stages exist', () => {
  const css = read('App.css')
  const columns = css.slice(css.indexOf('.columns {'), css.indexOf('.columns >'))

  // A fixed track count wrapped stage 6 onto a second row inside a container that only
  // scrolls sideways, which put it out of reach on any board past the default template.
  assert.ok(!/grid-template-columns:\s*repeat\(\s*5/.test(columns), 'must not hard-code five columns')
  assert.match(columns, /grid-auto-flow:\s*column/)
  assert.match(columns, /grid-auto-columns:/)
})

test('signup never pre-fills the platform domain as a brand name', () => {
  const landing = read('pages/LandingPage.jsx')
  const app = read('App.jsx')

  // A pre-filled value gets tabbed past, and every account that did would be named after
  // this platform rather than the brand signing up.
  assert.ok(!/defaultValue="tejdux\.io"/.test(landing), 'brand field must not be pre-filled')
  assert.ok(!/form\.get\('brand'\)\s*\|\|\s*'tejdux\.io'/.test(app), 'brand fallback must not be the platform domain')
})

test('signup and the manifest agree on where a user lands', () => {
  const landing = read('pages/LandingPage.jsx')

  // Signup used to navigate to a literal '/import' while the manifest sent everyone else to
  // the board, so "where a user starts" had two answers that could drift apart.
  assert.match(landing, /navigate\(DEFAULT_ROUTE\)/)
  assert.ok(!/navigate\('\/import'\)/.test(landing))
})

test('nothing delays navigation for the sake of the CTA animation', () => {
  const landing = read('pages/LandingPage.jsx')
  assert.ok(!/setTimeout/.test(landing), 'signup must not be held back by a decoration')
})

test('destructive actions no longer use unstyled browser dialogs', () => {
  for (const page of [
    'pages/WorkflowPage.jsx',
    'pages/CouponsPage.jsx',
    'pages/MarketplacePage.jsx',
    'pages/MembersPage.jsx',
    'pages/CreatorsPage.jsx',
    'pages/CampaignsPage.jsx',
  ]) {
    const source = read(page)
    const calls = source.match(/window\.confirm\(/g) || []
    assert.equal(calls.length, 0, `${page} still calls window.confirm`)
  }
})

test('every confirm dialog names the consequence, not just the object', () => {
  // "Delete board?" told the user nothing about the stages and cards attached to it.
  for (const page of ['pages/WorkflowPage.jsx', 'pages/CouponsPage.jsx', 'pages/MarketplacePage.jsx']) {
    const source = read(page)
    assert.match(source, /consequence=/, `${page} must explain what a delete takes with it`)
  }
})

test('the drawer traps focus and restores it on close', () => {
  const drawer = read('components/ui/Drawer.jsx')

  assert.match(drawer, /role="dialog"/)
  assert.match(drawer, /aria-modal="true"/)
  // Tab must cycle inside the panel; without this a keyboard user leaves the dialog on the
  // first keystroke and lands in a page they cannot see is still behind an overlay.
  assert.match(drawer, /event\.key !== 'Tab'/)
  assert.match(drawer, /openerRef\.current\.focus\(\)/)
})

test('a card can be moved without a pointer', () => {
  const workflow = read('pages/WorkflowPage.jsx')

  // draggable is a pointer-only gesture. The select is the keyboard and touch path onto
  // the board, and it announces the move rather than completing in silence.
  assert.match(workflow, /moveCardToStage/)
  assert.match(workflow, /Move to…/)
  assert.match(workflow, /toast\.success\(`\$\{card\?\.name \|\| 'Card'\} moved to/)
})

test('a successful save outlives the drawer that raised it', () => {
  for (const page of ['pages/CreatorsPage.jsx', 'pages/CampaignsPage.jsx']) {
    const source = read(page)
    // The old bug: set local feedback state, then call the close handler that resets it, so
    // the confirmation never rendered. A shell-level toast survives the unmount.
    assert.match(source, /toast\.success\(/, `${page} must confirm through the toast layer`)
    assert.ok(!/setRowFeedback\(/.test(source), `${page} must not use unmount-scoped feedback`)
  }
})

test('the token system defines scales, not just five ink values', () => {
  const css = read('index.css')

  for (const token of ['--space-1', '--space-8', '--radius-sm', '--radius-lg', '--focus']) {
    assert.ok(css.includes(token), `missing ${token}`)
  }
  // Semantic state is separate from the brand accent, so "interactive" and "went wrong"
  // cannot collapse into one colour.
  for (const token of ['--success', '--warning', '--danger', '--info']) {
    assert.ok(css.includes(token), `missing ${token}`)
  }
})

test('legacy ink aliases resolve to the new tokens rather than duplicating values', () => {
  const css = read('index.css')
  // The 2,700-line stylesheet still references --ink-*. Pointing them at the new tokens is
  // what lets both names describe one value while call sites migrate.
  assert.match(css, /--ink-4:\s*var\(--fg\)/)
  assert.match(css, /--line:\s*var\(--border\)/)
})

test('dark mode is a token redefinition, not a second stylesheet', () => {
  const css = read('index.css')
  const darkStart = css.indexOf('@media (prefers-color-scheme: dark)')
  assert.ok(darkStart > -1, 'no dark theme defined')

  const dark = css.slice(darkStart)
  // An explicit choice must win over the OS preference in both directions.
  assert.match(dark, /data-theme='light'/)
  assert.match(dark, /--surface:/)
})

test('the workspace exposes one focus ring and a skip link', () => {
  const css = read('index.css')
  assert.match(css, /:focus-visible\s*\{/)
  assert.match(css, /\.skip-link/)

  const layout = read('components/WorkspaceLayout.jsx')
  assert.match(layout, /className="skip-link"/)
  assert.match(layout, /id="workspace-main"/)
})

test('the page names itself instead of greeting the user on every route', () => {
  const layout = read('components/WorkspaceLayout.jsx')
  // Comments explain the old greeting, so strip them before asserting it is gone — otherwise
  // the rationale for the change reads as evidence the change was not made.
  const rendered = layout.replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '')

  // The greeting was the largest text on all ten routes and carried no information after the
  // first visit, while the page itself went unnamed.
  assert.ok(!/Welcome back/.test(rendered), 'the greeting must not be rendered')
  assert.ok(!/isFirstVisit/.test(rendered), 'the first-visit greeting variant must be gone too')
  assert.match(rendered, /ROUTE_MANIFEST\.find/)
})

// ── M0.2: product analytics ─────────────────────────────────────────────────
//
// Every validation signal in EXECUTION-ROADMAP.md is unmeasurable without these events, and a
// funnel with a silent hole in it is worse than no funnel — it reads as evidence.

test('the roadmap M0.2 event list is complete', () => {
  // Named in EXECUTION-ROADMAP.md M0.2. If an event is dropped, the milestone that depends on
  // it silently loses its validation signal; M7 alone is 20 dev-days gated on domain-bind.
  const required = [
    'signup', 'import-completed', 'card-moved', 'coupon-created',
    'order-attributed', 'export-clicked', 'publish-clicked', 'domain-bind-clicked',
  ]
  const defined = Object.values(EVENTS)

  for (const name of required) {
    assert.ok(defined.includes(name), `roadmap event "${name}" is not defined in EVENTS`)
  }
})

test('EVENTS is frozen so a call site cannot invent a name', () => {
  // A misspelled event does not throw — it produces a funnel with a hole, noticed weeks later.
  assert.ok(Object.isFrozen(EVENTS))
})

test('tracking never throws, whatever it is handed', () => {
  // Analytics must not be able to break a coupon from being created. Every one of these is a
  // call a tired call site could plausibly make.
  assert.doesNotThrow(() => track(EVENTS.SIGNUP))
  assert.doesNotThrow(() => track(EVENTS.SIGNUP, null))
  assert.doesNotThrow(() => track(EVENTS.SIGNUP, undefined))
  assert.doesNotThrow(() => track('not-a-real-event'))
  assert.doesNotThrow(() => track(undefined))
  assert.doesNotThrow(() => track(null, { a: 1 }))
})

test('identity can be set and cleared without throwing', () => {
  // resetIdentity runs at sign-out; on a shared browser, failing to clear would attribute the
  // next person's events to whoever signed in last.
  assert.doesNotThrow(() => identify({ userId: 'u1', accountId: 'a1', brandId: 'b1' }))
  assert.doesNotThrow(() => identify({}))
  assert.doesNotThrow(() => identify())
  assert.doesNotThrow(() => resetIdentity())
})

test('the default provider is log-only, never a silent no-op', () => {
  // `none` would make every event vanish with nothing on screen to say so. The console default
  // is the same honesty principle as FilesystemAssetStorage and ManualPayoutProvider: a local
  // implementation that visibly works, rather than a stub pretending to be a backend.
  assert.equal(analyticsProvider(), 'console')
})

test('no vendor SDK is imported by the analytics port', () => {
  // The point of the port is that swapping providers is a config change. An SDK imported at the
  // top level would make it a dependency of every bundle that touches instrumentation.
  const source = read('api/analytics.js')
  const imports = source.match(/^import .*/gm) || []

  assert.equal(imports.length, 0, 'analytics.js must not import a vendor SDK')
  assert.match(source, /VITE_ANALYTICS_PROVIDER/)
})

test('analytics identity carries ids, never email or name', () => {
  // Analytics backends are a third party with a different retention policy. Sending PII there
  // is a decision nobody made and it is hard to walk back.
  const source = read('api/analytics.js')
  const identifyBlock = source.slice(source.indexOf('export function identify'))
    .slice(0, source.slice(source.indexOf('export function identify')).indexOf('\n}'))

  assert.ok(!/email/i.test(identifyBlock), 'identify must not accept an email')
  assert.match(identifyBlock, /userId/)
})

test('the simulated-order event is flagged as simulated', () => {
  // Without the flag, debug-simulator orders inflate the same attribution metric that real
  // Shopify orders land in from M3 — and the number looks best exactly when it is least real.
  const app = read('App.jsx')
  const simBlock = app.slice(app.indexOf('const simulateOrderRecord'))
    .slice(0, 600)

  assert.match(simBlock, /ORDER_ATTRIBUTED/)
  assert.match(simBlock, /simulated:\s*true/)
})

test('card-moved is tracked after the server confirms, not on the optimistic update', () => {
  // placeCardRecord rolls back on failure. An event emitted before confirmation would count
  // moves that never happened, in the metric M4 uses to judge week-two retention.
  const app = read('App.jsx')
  const block = app.slice(app.indexOf('const placeCardRecord'))
  const body = block.slice(0, block.indexOf('const deleteCardRecord'))

  const awaitAt = body.indexOf('await placeWorkflowCard')
  const trackAt = body.indexOf('EVENTS.CARD_MOVED')
  // Anchor on the catch clause itself. `setWorkspaceError('')` also appears *before* the await
  // to clear any prior error, so searching for that name finds the wrong occurrence.
  const catchAt = body.indexOf('} catch (error) {')

  assert.ok(awaitAt > -1 && trackAt > -1, 'both the call and the event must be present')
  assert.ok(catchAt > -1, 'the rollback catch block must be present')
  assert.ok(trackAt > awaitAt, 'the event must fire after the awaited server call')
  assert.ok(trackAt < catchAt, 'the event must be inside the success path, not the catch')
})

// ── M1.2: create-a-brand ────────────────────────────────────────────────────
//
// The tenancy spine took four migration phases and is isolation-verified. The form that
// exercises it was never built, which made the whole multi-brand capability undemonstrable.

test('createBrand is wired to a call site', () => {
  // It existed as an API client function with zero call sites — dead code. The endpoint worked;
  // nothing reached it. This test is the guard against it going dead again.
  const app = read('App.jsx')

  assert.match(app, /createBrand,/, 'createBrand must be imported')
  assert.match(app, /const createBrandRecord/, 'a handler must exist')
  assert.match(app, /onCreateBrand=\{createBrandRecord\}/, 'the handler must be passed to the layout')
})

test('the add-brand control renders for single-brand accounts too', () => {
  // The switcher only renders at >1 brand. Putting the control inside that branch would leave a
  // solo account — the exact account that needs a second brand — with no path to one.
  const layout = read('components/WorkspaceLayout.jsx')

  const switcherAt = layout.indexOf('showSwitcher ? (')
  const plainNameAt = layout.indexOf('rail-brand-name')
  const addControlAt = layout.indexOf('canCreateBrand ?')

  assert.ok(switcherAt > -1 && plainNameAt > -1 && addControlAt > -1)
  // The control must sit after BOTH branches of the switcher conditional, not inside either.
  assert.ok(addControlAt > plainNameAt, 'the add control must be outside the >1-brand branch')
})

test('the add-brand control is gated on the permission the server enforces', () => {
  // BRAND_CREATE is OWNER/ADMIN only. Hiding it from a MARKETER avoids offering an action that
  // would 403; the server check remains the actual boundary.
  const layout = read('components/WorkspaceLayout.jsx')

  assert.match(layout, /permissions\.includes\('brand:create'\)/)
})

test('creating a brand switches into it', () => {
  // A brand created and left in the background looks like nothing happened, and the account
  // still reads as single-brand until a reload.
  const app = read('App.jsx')
  const handler = app.slice(app.indexOf('const createBrandRecord'))
  const body = handler.slice(0, handler.indexOf('const establishSession'))

  assert.match(body, /await createBrand\(/)
  assert.match(body, /listBrands\(/, 'the brand list must be refreshed from the server')
  assert.match(body, /handleSwitchBrand\(/, 'the new brand must become active')
})

// ── M1.3 / M1.4: invitations that actually send and can be redeemed ──────────
//
// The flow was broken at both ends: nothing sent, and acceptInvitation had zero call sites, so
// even a token that reached its recipient could not be redeemed through the product.

test('acceptInvitation is wired to a call site', () => {
  const app = read('App.jsx')

  assert.match(app, /acceptInvitation,/, 'acceptInvitation must be imported')
  assert.match(app, /const acceptInvitationRecord/, 'a handler must exist')
  assert.match(app, /onAccept=\{acceptInvitationRecord\}/, 'the handler must reach the page')
})

test('the accept route is reachable while signed out', () => {
  // An invitee usually has no account yet. If this route only existed in the signed-in branch,
  // the landing-page catch-all would swallow the link and drop the token.
  const app = read('App.jsx')
  const signedOutBranch = app.slice(app.indexOf('{!isLoggedIn ? ('), app.indexOf('      ) : ('))

  assert.match(signedOutBranch, /path="\/accept-invitation"/)
})

test('the accept route is declared before the catch-all in both branches', () => {
  // Route order decides this. A catch-all declared first wins and the token is lost — signed out
  // to the landing page, signed in to a dashboard redirect.
  const app = read('App.jsx')

  const signedOut = app.slice(app.indexOf('{!isLoggedIn ? ('), app.indexOf('      ) : ('))
  assert.ok(
    signedOut.indexOf('path="/accept-invitation"') < signedOut.indexOf('path="*"'),
    'signed-out: accept route must precede the catch-all',
  )

  const signedIn = app.slice(app.indexOf('      ) : ('))
  assert.ok(
    signedIn.indexOf('path="/accept-invitation"') < signedIn.indexOf('path="*"'),
    'signed-in: accept route must precede the catch-all',
  )
})

test('the invite screen does not claim an email was sent when none was', () => {
  // With the log-only provider nothing is delivered. Saying "sent" leaves the inviter waiting on
  // a reply to an email that never left the building.
  const members = read('pages/MembersPage.jsx')

  assert.match(members, /emailDelivered/, 'the page must read the delivery flag')
  assert.match(members, /No email was sent/, 'the fallback must say so plainly')
  // The token is only worth showing when nothing was sent; otherwise it puts a one-time
  // credential on screen for no reason.
  assert.match(members, /delivered \? '' : created\?\.token/)
})

test('the invite fallback shows a full link, not a bare token', () => {
  // A bare token is not actionable — the invitee must also be told which page to paste it into.
  const members = read('pages/MembersPage.jsx')

  assert.match(members, /function inviteLinkFor/)
  assert.match(members, /accept-invitation\?token=\$\{encodeURIComponent\(token\)\}/)
})

test('accepting an invitation refreshes the reachable brands', () => {
  // Joining an account adds a brand. Without re-reading the list, the switcher would not show it
  // until the next full reload, making a successful acceptance look like nothing happened.
  const app = read('App.jsx')
  const handler = app.slice(app.indexOf('const acceptInvitationRecord'))
  const body = handler.slice(0, 900)

  assert.match(body, /await acceptInvitation\(/)
  assert.match(body, /listBrands\(/)
})

// ── M1.5: CSV export ────────────────────────────────────────────────────────
//
// Zero export of any kind existed repo-wide, in any format, from any screen — disqualifying for
// the agency segment, which has to hand a client something.

test('CSV quotes only the fields that need it', () => {
  const csv = toCsv(
    [{ key: 'a', header: 'A' }, { key: 'b', header: 'B' }],
    [{ a: 'plain', b: 'has,comma' }, { a: 'has"quote', b: 'has\nnewline' }],
  )
  const [header, row1, ...rest] = csv.split('\r\n')

  assert.equal(header, 'A,B')
  assert.equal(row1, 'plain,"has,comma"')
  // Embedded quotes double, per RFC 4180.
  assert.ok(rest.join('\r\n').includes('"has""quote"'))
})

test('formula-injection payloads are neutralised', () => {
  // The one that matters. A field starting =, +, - or @ executes when the file opens in Excel or
  // Sheets — so a creator named =HYPERLINK(...) becomes code running on the client's machine,
  // from a file our customer sent them in good faith.
  const csv = toCsv(
    [{ key: 'name', header: 'Name' }],
    [
      { name: '=HYPERLINK("http://evil","click")' },
      { name: '+1234' },
      { name: '-1234' },
      { name: '@SUM(A1:A9)' },
    ],
  )

  for (const line of csv.split('\r\n').slice(1)) {
    assert.ok(
      line.startsWith('\t') || line.startsWith('"\t'),
      `dangerous prefix not neutralised: ${line}`,
    )
  }
})

test('a legitimate negative number still round-trips readably', () => {
  // The guard prefixes a tab rather than stripping or quoting away the value — a clawback of
  // -50 must still read as -50 to a human and to a re-import.
  const csv = toCsv([{ key: 'v', header: 'V' }], [{ v: -50 }])
  const [, row] = csv.split('\r\n')

  assert.ok(row.includes('-50'))
})

test('null and undefined become empty cells, not the strings "null"/"undefined"', () => {
  // An unset preferred rate must not export as the word "null" into a client-facing file.
  const csv = toCsv(
    [{ key: 'a', header: 'A' }, { key: 'b', header: 'B' }, { key: 'c', header: 'C' }],
    [{ a: null, b: undefined, c: 0 }],
  )
  const [, row] = csv.split('\r\n')

  // `0` is a real value and must survive; only null/undefined blank out.
  assert.equal(row, ',,0')
})

test('a computed column overrides the raw key', () => {
  const csv = toCsv(
    [{ key: 'x', header: 'X', value: (row) => `${row.x}!` }],
    [{ x: 'a' }],
  )

  assert.equal(csv.split('\r\n')[1], 'a!')
})

test('empty rows still produce a header line', () => {
  // An export of a filtered-to-nothing list should be an empty spreadsheet, not a broken file.
  const csv = toCsv([{ key: 'a', header: 'A' }], [])

  assert.equal(csv, 'A')
})

test('export is wired on all four surfaces the roadmap names', () => {
  // creators, campaigns, attribution, commissions — M1.5's scope.
  assert.match(read('pages/CreatorsPage.jsx'), /exportCsv\(\{[\s\S]*prefix: 'creators'/)
  assert.match(read('pages/CampaignsPage.jsx'), /exportCsv\(\{[\s\S]*prefix: 'campaigns'/)
  // The dashboard's prefix is a conditional — a ranged export is named for its window
  // (`attribution-30d`) so two files taken a month apart are not both `attribution.csv` with
  // silently different meanings. Matched loosely enough to allow that, strictly enough to still
  // fail if the attribution export is removed.
  assert.match(read('pages/DashboardPage.jsx'), /exportCsv\(\{[\s\S]*prefix:[^\n]*'attribution/)
  assert.match(read('pages/PayoutsPage.jsx'), /exportCsv\(\{[\s\S]*prefix: 'commissions'/)
})

test('every export is counted, because tracking lives in the download helper', () => {
  // If each call site tracked its own, a new export could be added without being counted — and
  // M1's validation signal depends on knowing whether anyone exports at all.
  const csv = read('api/csv.js')
  const downloadFn = csv.slice(csv.indexOf('export function downloadCsv'))

  assert.match(downloadFn, /track\(EVENTS\.EXPORT_CLICKED/)
})

test('the CSV carries a BOM so Excel reads it as UTF-8', () => {
  // Without it, any non-ASCII creator name renders as mojibake — in a file that lands in front
  // of the customer's client, not the customer.
  const csv = read('api/csv.js')

  assert.match(csv, /\ufeff|﻿/, 'downloadCsv must prepend a BOM')
})

// ── Benchmark remediation: date range, URL filters, bulk selection ──────────

test('a 7-day window spans seven days, not eight', () => {
  // `days - 1` is the whole point: today counts as one of the seven. Using `days` directly is
  // the off-by-one that quietly widens every window and inflates the KPI above it.
  const params = rangeToParams('7d', new Date(2026, 7, 7))

  assert.equal(params.from, '2026-08-01')
  assert.equal(params.to, '2026-08-07')
})

test('a 30-day window crosses a month boundary correctly', () => {
  const params = rangeToParams('30d', new Date(2026, 2, 5))

  // March 5 back 29 days lands in February — and 2026 is not a leap year.
  assert.equal(params.from, '2026-02-04')
  assert.equal(params.to, '2026-03-05')
})

test('all-time sends no bounds at all', () => {
  // Not a very old `from`: an unfiltered query is what keeps attributions with no recorded
  // occurredAt visible in the default view rather than silently dropping them.
  assert.deepEqual(rangeToParams('all', new Date(2026, 7, 7)), {})
})

test('an unknown range is treated as all-time rather than throwing', () => {
  // A stale URL or a hand-edited query param must not break the dashboard.
  assert.deepEqual(rangeToParams('nonsense', new Date(2026, 7, 7)), {})
})

test('the ISO date is local, not UTC-shifted', () => {
  // toISOString() would report the next day for anyone west of Greenwich late in the evening —
  // an export labelled with a date the data does not cover.
  const lateEvening = new Date(2026, 7, 7, 23, 30)

  assert.equal(toIsoDate(lateEvening), '2026-08-07')
})

test('the dashboard asks the server for its window rather than slicing locally', () => {
  // The payload is pre-aggregated rollups; the per-order rows a client-side filter would need
  // never reach the browser. Filtering here would silently produce wrong totals.
  const page = read('pages/DashboardPage.jsx')

  assert.match(page, /onLoadRevenue\(rangeToParams\(range\)\)/)
})

test('every KPI states the window it covers', () => {
  // A revenue figure with no stated period is the easiest dashboard number to misread.
  const page = read('pages/DashboardPage.jsx')

  assert.match(page, /Showing \{rangeLabel/)
})

test('the dashboard renders through the shared table, not a second table system', () => {
  const page = read('pages/DashboardPage.jsx')

  assert.match(page, /import \{[^}]*DataTable/)
  // The hand-rolled markup it replaced. Its absence is what makes sorting, sticky headers, and
  // keyboard support arrive on this page for free.
  assert.doesNotMatch(page, /<table className="dash-table"/)
  assert.doesNotMatch(page, /className="simple-list"/)
})

test('an empty window offers to widen, an empty account offers to set up', () => {
  // Showing "connect your store" to someone with a year of data who picked last week would be
  // wrong; the two emptinesses have different causes and different fixes.
  const page = read('pages/DashboardPage.jsx')

  assert.match(page, /No sales attributed in the \$\{rangeLabel/)
  assert.match(page, /Show all time/)
  assert.match(page, /Connect your store/)
})

test('bulk selection acts only on rows the filter still shows', () => {
  // Narrowing the filter after selecting must narrow what an action applies to — acting on rows
  // the user can no longer see is how bulk operations go wrong.
  const page = read('pages/CreatorsPage.jsx')

  assert.match(page, /visibleCreators\.filter\(\(creator\) => selectedIds\.has\(creator\.id\)\)/)
})

test('the select-all header covers the filtered rows, not the whole table', () => {
  const table = read('components/ui/DataTable.jsx')

  assert.match(table, /const visibleKeys = rows\.map/)
  // Indeterminate is a DOM property with no HTML attribute, so it can only be set via a ref.
  // Without it a partial selection looks identical to an empty one.
  assert.match(table, /node\.indeterminate = someSelected/)
})

test('ticking a checkbox does not also open the row drawer', () => {
  // stopPropagation, not preventDefault — the click must still reach the checkbox. Without it,
  // selecting forty rows would open forty drawers.
  const table = read('components/ui/DataTable.jsx')

  assert.match(table, /className="data-table-select" onClick=\{\(event\) => event\.stopPropagation\(\)\}/)
})

test('filters live in the URL so a filtered view can be linked', () => {
  const page = read('pages/CreatorsPage.jsx')

  assert.match(page, /useUrlFilters\(\{/)
  // The old component-local state, which could not be linked or restored by the back button.
  assert.doesNotMatch(page, /const \[search, setSearch\] = useState/)
  assert.doesNotMatch(page, /const \[platformFilter, setPlatformFilter\] = useState/)
})

test('a filter at its default is dropped from the query string', () => {
  // Otherwise clearing a filter leaves `?platform=` behind, and two users describing "the
  // default view" produce different links.
  const hook = read('shell/useUrlFilters.js')

  assert.match(hook, /value === parsed\[key\] \|\| value === ''/)
  // replace, so typing in a search box does not push one history entry per keystroke.
  assert.match(hook, /\{ replace: true \}/)
})

test('the theme is applied before first paint, not in a React effect', () => {
  // A React effect lands a frame or two after the document renders, so a dark-mode user would
  // see a white flash on every load. This is the case where a blocking inline script is right.
  const html = read('../index.html')

  assert.match(html, /localStorage\.getItem\('tejdux\.theme'\)/)
  assert.match(html, /document\.documentElement\.setAttribute\('data-theme'/)
})

test('choosing "system" removes the attribute rather than setting a value', () => {
  // The CSS keys off :root:not([data-theme='light']) inside a prefers-color-scheme query, so
  // absence is what hands control back to the OS.
  const toggle = read('components/ui/ThemeToggle.jsx')

  assert.match(toggle, /root\.removeAttribute\('data-theme'\)/)
  // localStorage throws — not returns null — in Safari private browsing and under policy blocks.
  assert.match(toggle, /catch \{/)
})

test('fonts are self-hosted, not fetched from Google at runtime', () => {
  // The CDN import was render-blocking on first paint and disclosed every visitor's IP to
  // fonts.googleapis.com — a question EU brand customers raise during procurement.
  const index = read('index.css')
  const fonts = read('fonts.css')

  // Matches a live @import/url() only — the prose above it names the domain it replaced, and a
  // bare domain match would fail on the comment explaining the fix.
  assert.doesNotMatch(index, /@import[^;]*fonts\.googleapis\.com/)
  assert.doesNotMatch(fonts, /url\([^)]*fonts\.gstatic\.com/)
  assert.match(index, /@import url\('\.\/fonts\.css'\)/)
})

test('every self-hosted font file referenced by the stylesheet exists', () => {
  // A missing woff2 does not error — the browser silently falls back to Georgia, which looks
  // like nothing is wrong. This is the check that catches it.
  const fonts = read('fonts.css')
  const referenced = [...new Set([...fonts.matchAll(/url\('\/fonts\/([^']+)'\)/g)].map((m) => m[1]))]

  assert.ok(referenced.length >= 9, `expected the full subset set, found ${referenced.length}`)
  referenced.forEach((file) => {
    const bytes = readFileSync(join(SRC, '../public/fonts', file))
    // woff2 files begin with the magic number "wOF2". A truncated or HTML-error-page download
    // would still be a file on disk but would not render.
    assert.equal(bytes.subarray(0, 4).toString('latin1'), 'wOF2', `${file} is not a valid woff2`)
  })
})

test('the unicode-range subsets survive self-hosting', () => {
  // Dropping them would make the browser download every subset on every page, turning a
  // performance fix into a regression — and losing the Vietnamese/Latin-Ext faces entirely.
  const fonts = read('fonts.css')

  assert.match(fonts, /unicode-range:/)
  assert.ok(fonts.match(/@font-face/g).length >= 20, 'subset blocks were collapsed')
})

test('the content grid track has a zero minimum so wide tables do not stretch the page', () => {
  // An implicit `auto` track is sized by its widest content, so a wide table propagated its
  // intrinsic width outward and gave the whole page 300+px of horizontal scroll on a phone —
  // which also defeated the overflow:auto on .data-table-wrap, since a scroll container only
  // scrolls when something upstream constrains it.
  const app = read('App.css')
  const ui = read('components/ui/ui.css')
  const content = app.slice(app.indexOf('.workspace-content {'), app.indexOf('.page-stack'))
  const main = ui.slice(ui.indexOf('.workspace-main {'), ui.indexOf('.workspace-main > *'))
  // .page-stack is the third such grid, found on the kanban board: 7x260px of columns resolved
  // its track to 1926px inside a 1136px column and pushed 758px of scroll onto the page, while
  // .columns (overflow-x: auto) had nothing constraining it to scroll against.
  const stack = app.slice(app.indexOf('.page-stack {'), app.indexOf('.page-form-grid'))

  assert.match(content, /grid-template-columns: minmax\(0, 1fr\)/)
  assert.match(main, /grid-template-columns: minmax\(0, 1fr\)/)
  assert.match(stack, /grid-template-columns: minmax\(0, 1fr\)/)
})

test('the legacy prose-table rule does not repaint the design system table', () => {
  // `.mds-theme table` set a hardcoded near-white background that beat .data-table-wrap's
  // tokenised one on specificity — a white sheet under light dark-mode text, which made every
  // leaderboard cell unreadable.
  const app = read('App.css')

  assert.match(app, /\.mds-theme table:not\(\.data-table\)/)
  const rule = app.slice(app.indexOf('.mds-theme table:not(.data-table)'))
  assert.doesNotMatch(rule.slice(0, 240), /rgba\(255, 255, 255/)
})

test('workspace strong text follows the theme token', () => {
  // #0f172a is the light-mode ink. Every KPI value is a <strong>, so the literal rendered them
  // near-black on a near-black tile in dark mode.
  const app = read('App.css')
  const rule = app.slice(app.indexOf('.mds-theme strong {'), app.indexOf('.mds-theme em {') + 60)

  assert.match(rule, /\.mds-theme strong \{\s*color: var\(--fg\)/)
  assert.doesNotMatch(rule, /#0f172a/)
})

test('inline feedback banners follow the theme', () => {
  // The literals these replaced (#0f5132, #7f1d1d) are light-mode inks over a translucent tint.
  // Composited against the dark background they measured 1.65:1 and 1.54:1 — far under the 4.5:1
  // AA floor, on the one element whose entire job is telling the user something went wrong. Found
  // by screenshotting an expired-session error; the e2e assertions passed because the text was in
  // the DOM, just unreadable.
  const app = read('App.css')
  const block = app.slice(app.indexOf('.row-save-feedback.success'), app.indexOf('.custom-attributes-label'))

  assert.match(block, /color: var\(--success-on-tint\)/)
  assert.match(block, /color: var\(--danger-on-tint\)/)
  assert.doesNotMatch(block, /#0f5132|#7f1d1d/)
})

test('on-tint text tokens are defined for both themes', () => {
  // Distinct from --success/--danger, which double as the accent and the button fill: those are
  // tuned to carry a large shape at 3:1, while a 13px sentence on a 92%-opaque tint needs 4.5:1
  // and lands at 4.35:1 if it reuses them.
  const index = read('index.css')

  assert.match(index, /--success-on-tint: #134e4a/)
  assert.match(index, /--danger-on-tint: #991b1b/)
  // The dark block must override both, or the light inks render near-black on near-black.
  const dark = index.slice(index.indexOf('@media (prefers-color-scheme: dark)'))
  assert.match(dark, /--success-on-tint: #5eead4/)
  assert.match(dark, /--danger-on-tint: #fca5a5/)
})

// ── Session expiry: proactive refresh and the re-prompt ─────────────────────

/** Builds an unsigned JWT whose payload carries the given `exp`. Only the claims are read here. */
function tokenExpiringAt(epochSeconds) {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64({ alg: 'RS256' })}.${b64({ exp: epochSeconds, perms: [] })}.sig`
}

test('token expiry is read from the exp claim in seconds, not milliseconds', () => {
  // RFC 7519 says exp is seconds. Treating it as ms puts expiry in 1970 and makes every token
  // look already-expired, which would spin the refresh timer.
  const expSeconds = 1786140000
  assert.equal(accessTokenExpiryMs(tokenExpiringAt(expSeconds)), expSeconds * 1000)
})

test('refresh is scheduled ahead of expiry, not at it', () => {
  // Refreshing at expiry means the token dies in flight for any request already on the wire.
  const now = 1786140000000
  const token = tokenExpiringAt((now + 30 * 60 * 1000) / 1000)

  assert.equal(msUntilRefresh(token, now), 30 * 60 * 1000 - REFRESH_LEAD_MS)
})

test('a token already inside the lead window schedules a floored delay, not a negative one', () => {
  // setTimeout with a negative delay fires immediately; combined with a refresh that returns
  // another near-expired token that is a hot loop.
  const now = 1786140000000
  const token = tokenExpiringAt((now + 10 * 1000) / 1000)
  const delay = msUntilRefresh(token, now)

  assert.ok(delay >= 5000, `expected a floored delay, got ${delay}`)
})

test('an unreadable token schedules nothing rather than guessing', () => {
  // Falls back to the API layer's reactive 401 retry. A guessed cadence would either spin or
  // never fire, and both are worse than the existing behaviour.
  assert.equal(msUntilRefresh('not-a-jwt'), null)
  assert.equal(msUntilRefresh(''), null)
  assert.equal(accessTokenExpiryMs('a.b.c'), null)
})

test('expiry detection does not throw on malformed input', () => {
  // An unreadable token is a 401 for the request layer to surface, not a render crash.
  for (const bad of ['', 'x', 'a.b', 'a.!!!.c', null, undefined]) {
    assert.doesNotThrow(() => accessTokenExpiryMs(bad))
  }
})

test('a failed refresh prompts instead of clearing the session', () => {
  // The old onSessionExpired cleared every field and returned the user to the login screen
  // mid-task, taking any open drawer with it. A failed refresh is often recoverable.
  const app = read('App.jsx')
  const handler = app.slice(app.indexOf('onSessionExpired:'), app.indexOf('onSessionExpired:') + 400)

  assert.match(handler, /setSessionPrompt\(true\)/)
  assert.doesNotMatch(handler, /setIsLoggedIn\(false\)/)
})

test('the session dialog renders over the workspace, not in place of it', () => {
  // Outside <Routes>, so the page underneath stays mounted and unsaved work survives the
  // decision. Gated on isLoggedIn so a stale flag cannot cover the login screen.
  const app = read('App.jsx')

  assert.match(app, /\{isLoggedIn && sessionPrompt \? \(\s*<SessionExpiredDialog/)
  assert.ok(app.indexOf('<SessionExpiredDialog') > app.indexOf('</Routes>'),
    'the dialog must render after </Routes> so it overlays the current page')
})

test('the session dialog cannot be dismissed without choosing', () => {
  // Dismissing does not give the session back — it hides the only control that can recover it
  // and leaves a workspace whose every request 401s.
  const dialog = read('components/ui/SessionExpiredDialog.jsx')

  // Escape is swallowed, not forwarded to a cancel handler.
  assert.match(dialog, /if \(event\.key === 'Escape'\) \{\s*event\.preventDefault\(\)\s*return/)
  // No overlay click-to-close, unlike ConfirmDialog.
  assert.doesNotMatch(dialog, /className="confirm-overlay"[^>]*onClick=\{[^}]*onCancel/)
  assert.match(dialog, /role="alertdialog"/)
  assert.match(dialog, /aria-modal="true"/)
})

test('the proactive refresh timer is cleared on unmount', () => {
  // Without the cleanup a token change would stack timers, each firing its own refresh and
  // rotating the refresh token out from under the others.
  const app = read('App.jsx')
  const effect = app.slice(app.indexOf('const delay = msUntilRefresh(authToken)'))

  assert.match(effect.slice(0, 900), /return \(\) => clearTimeout\(timer\)/)
})

// ── M6 / U4 / U1: provenance and the creator record page ───────────────────

test('provenance ages are rounded to the decision-relevant unit', () => {
  // A real number of unknown age is its own trust problem. "3 days ago" is what a decision turns
  // on; a precise timestamp would imply a precision the refresh cadence does not have.
  const now = Date.parse('2026-08-07T12:00:00Z')
  const at = (iso) => formatFetchedAt(iso, now)

  assert.equal(at('2026-08-07T11:59:30Z'), 'just now')
  assert.equal(at('2026-08-07T11:30:00Z'), '30 minutes ago')
  assert.equal(at('2026-08-07T09:00:00Z'), '3 hours ago')
  assert.equal(at('2026-08-04T12:00:00Z'), '3 days ago')
  assert.equal(at('2026-06-07T12:00:00Z'), '2 months ago')
})

test('a future-dated read reads as "just now" rather than negative time', () => {
  // Clock skew between the browser and the server is ordinary. "in -3 seconds" is not a thing to
  // put in front of a customer.
  const now = Date.parse('2026-08-07T12:00:00Z')

  assert.equal(formatFetchedAt('2026-08-07T12:00:20Z', now), 'just now')
})

test('a missing or unparseable timestamp renders nothing, not "Invalid Date"', () => {
  assert.equal(formatFetchedAt(null), '')
  assert.equal(formatFetchedAt(''), '')
  assert.equal(formatFetchedAt('not-a-date'), '')
})

test('only a platform read counts as verified', () => {
  // The single invariant the whole provenance design protects: a simulated number must never be
  // presentable as a measured one.
  assert.equal(isPlatformVerified('platform_api'), true)
  assert.equal(isPlatformVerified('PLATFORM_API'), true)
  assert.equal(isPlatformVerified('mock'), false)
  assert.equal(isPlatformVerified('manual'), false)
  assert.equal(isPlatformVerified('import'), false)
  assert.equal(isPlatformVerified(null), false)
  assert.equal(isPlatformVerified(''), false)
})

test('badge text colours use the on-tint tokens, not the semantic accents', () => {
  // --success and --warning are tuned to carry a large shape at 3:1 and measure 4.35:1 and
  // 4.24:1 as 11px badge text — both under AA. Caught by measuring before shipping the
  // provenance badges, which use exactly those two tones.
  const ui = read('components/ui/ui.css')
  const badges = ui.slice(ui.indexOf('.badge-success'), ui.indexOf('.badge-info') + 200)

  assert.match(badges, /\.badge-success[^}]*color: var\(--success-on-tint\)/)
  assert.match(badges, /\.badge-warning[^}]*color: var\(--warning-on-tint\)/)
  assert.doesNotMatch(badges, /color: var\(--success\)/)
  assert.doesNotMatch(badges, /color: var\(--warning\)/)
})

test('every on-tint token is defined for both themes', () => {
  const index = read('index.css')
  const dark = index.slice(index.indexOf('@media (prefers-color-scheme: dark)'))

  for (const token of ['success-on-tint', 'danger-on-tint', 'warning-on-tint', 'info-on-tint']) {
    assert.match(index, new RegExp(`--${token}: #`), `${token} missing from the light palette`)
    assert.match(dark, new RegExp(`--${token}: #`), `${token} missing from the dark palette`)
  }
})

test('the record page is a route but never a nav entry', () => {
  // ROUTE_MANIFEST drives the rail. A detail route has no place there — it is reached from a row
  // or a pasted link, which is the entire reason it has a URL.
  const app = read('App.jsx')

  assert.match(app, /path="creators\/:creatorId"/)
  assert.ok(!ROUTE_MANIFEST.some((route) => route.path.includes(':')),
    'no manifest entry may carry a path parameter')
})

test('a row click opens the record, with editing still one click away', () => {
  // Finding and reading is the daily loop; editing is occasional. The row used to open the edit
  // drawer, which is what made a creator unlinkable.
  const page = read('pages/CreatorsPage.jsx')

  assert.match(page, /onRowClick=\{\(creator\) => navigate\(`\/creators\/\$\{creator\.id\}`\)\}/)
  // stopPropagation, or the Edit button would also fire the row's navigation.
  assert.match(page, /event\.stopPropagation\(\)\s*\n\s*openEdit\(creator\)/)
})

test('an unknown creator id gets an answer, not a blank screen', () => {
  // Stale links are the expected cost of making records shareable, so the miss case is part of
  // the feature rather than an edge case.
  const page = read('pages/CreatorRecordPage.jsx')

  assert.match(page, /No such creator/)
  assert.match(page, /Back to creators/)
})

test('the record page never shows zeroed metrics for an unmeasured creator', () => {
  // 0 followers and "nobody has looked them up" are different facts, and 0 silently fails every
  // vetting rule written as `followers < 5000`.
  const page = read('pages/CreatorRecordPage.jsx')

  assert.match(page, /creator\.followerCount !== null && creator\.followerCount !== undefined/)
})

// ── M2.3: plan limits are visible before they are hit ───────────────────────

test('an account at its limit is full, matching the server', () => {
  // >= not >, the same off-by-one PlanPolicyTest guards on the server. If these disagree the UI
  // shows "24 of 25, room to spare" on the very request that gets refused with a 402.
  assert.equal(describeUsage({ resource: 'creator', used: 24, limit: 25 }).atLimit, false)
  assert.equal(describeUsage({ resource: 'creator', used: 25, limit: 25 }).atLimit, true)
  assert.equal(describeUsage({ resource: 'creator', used: 26, limit: 25 }).atLimit, true)
})

test('unlimited is never rendered as a number that looks like a cap', () => {
  // The server sends -1. Formatting it literally would put "5 of -1" on screen; treating it as a
  // large number would invent a ceiling the account does not have.
  const usage = describeUsage({ resource: 'creator', label: 'creators', used: 5, limit: UNLIMITED })

  assert.equal(usage.unlimited, true)
  assert.equal(usage.atLimit, false)
  assert.equal(usage.ratio, null, 'ratio must be null, not 0 — an unlimited row is not an empty one')
  assert.match(formatUsage(usage), /unlimited/)
})

test('a near-limit resource warns while there is still room to act', () => {
  // 80%: a notice at 95% of a 25-creator plan arrives with one slot left, which is narration
  // rather than warning.
  assert.equal(describeUsage({ resource: 'creator', used: 19, limit: 25 }).nearLimit, false)
  assert.equal(describeUsage({ resource: 'creator', used: 20, limit: 25 }).nearLimit, true)
  // At the limit the stronger message takes over — "running low" is the wrong tense.
  assert.equal(describeUsage({ resource: 'creator', used: 25, limit: 25 }).nearLimit, false)
})

test('tones escalate, and unlimited stays neutral', () => {
  // Unlimited is a fact about the plan, not good news about this account. A page of green ticks
  // devalues the one tone that should mean something.
  assert.equal(usageTone(describeUsage({ used: 1, limit: 25 })), 'neutral')
  assert.equal(usageTone(describeUsage({ used: 20, limit: 25 })), 'warning')
  assert.equal(usageTone(describeUsage({ used: 25, limit: 25 })), 'danger')
  assert.equal(usageTone(describeUsage({ used: 9999, limit: UNLIMITED })), 'neutral')
})

test('only rows worth commenting on get a message', () => {
  // A note on every row is noise, and noise is what makes the row that matters invisible.
  assert.equal(usageMessage(describeUsage({ used: 2, limit: 25 }), 'free'), null)
  assert.ok(usageMessage(describeUsage({ used: 21, limit: 25, label: 'creators' }), 'free'))
})

test('the at-limit message says existing data is safe and names the upgrade', () => {
  // The fear on hitting a cap is that something gets deleted. The server guarantees it does not,
  // so the UI must say so in the same breath rather than leaving it to be inferred.
  const message = usageMessage(describeUsage({ used: 25, limit: 25, label: 'creators' }), 'free')

  assert.match(message, /Existing creators are unaffected/)
  assert.match(message, /Pro/, 'it must name the tier that fixes it')
  assert.match(message, /cannot add more/)
})

test('the at-limit message reads as a sentence at every count', () => {
  // Caught by rendering the real payload rather than by a test: the free tier's brand limit is 1,
  // so "all 1 brands" was the common case, not an edge case. And the message ended with "cannot
  // add more. Upgrade to Pro to add more." — the same clause twice.
  const oneBrand = usageMessage(describeUsage({ used: 1, limit: 1, label: 'brands' }), 'free')
  assert.match(oneBrand, /includes 1 brand and/)
  assert.doesNotMatch(oneBrand, /1 brands/, 'a limit of 1 must not be pluralised')
  assert.doesNotMatch(oneBrand, /add more\..*add more/, 'the remedy must not be stated twice')

  // Over-limit is reachable with no downgrade at all — introducing a limit above accounts that
  // already exceed it is exactly what shipping M2.3 did to two live accounts. Telling them they
  // have "used them all" when they are past the cap would be wrong.
  const overLimit = usageMessage(describeUsage({ used: 6, limit: 3, label: 'team members' }), 'free')
  assert.match(overLimit, /you have 6/)
  assert.doesNotMatch(overLimit, /used them all/)
})

test('the upgrade suggested points upward and stops at agency', () => {
  // Suggesting "upgrade to Pro" to a Pro account, or anything at all to an unlimited one, reads
  // as a broken funnel.
  assert.equal(describePlan('free').nextTier, 'Pro')
  assert.equal(describePlan('pro').nextTier, 'Agency')
  assert.equal(describePlan('agency').nextTier, null)
})

test('an unknown plan renders readably rather than blank', () => {
  // The server falls back to the free LIMITS for an unrecognised plan, but it echoes the resolved
  // key. A blank heading where the plan name goes would look broken.
  assert.equal(describePlan('').label, 'Free')
  assert.equal(describePlan(null).label, 'Free')
  assert.equal(describePlan('enterprise').label, 'Enterprise')
})

test('pressured resources come back most urgent first', () => {
  // What a banner should lead with. At-limit outranks near-limit regardless of ratio.
  const rows = pressuredResources([
    { resource: 'creator', used: 21, limit: 25 },
    { resource: 'brand', used: 1, limit: 1 },
    { resource: 'page', used: 1, limit: 25 },
  ])

  assert.equal(rows.length, 2, 'the comfortable resource is not mentioned')
  assert.equal(rows[0].resource, 'brand', 'at-limit leads')
})

test('the public tier table matches the limits the server enforces', () => {
  // These numbers are duplicated from PlanPolicy because the landing page is signed out and has
  // no account to ask. Advertising a limit the server does not enforce is the failure this guards.
  const free = PUBLIC_TIERS.find((tier) => tier.key === 'free')
  const pro = PUBLIC_TIERS.find((tier) => tier.key === 'pro')
  const agency = PUBLIC_TIERS.find((tier) => tier.key === 'agency')

  assert.ok(free.highlights.some((line) => line.includes('25 creators')))
  assert.ok(free.highlights.some((line) => line.includes('1 brand')))
  assert.ok(pro.highlights.some((line) => line.includes('250 creators')))
  assert.ok(agency.highlights.some((line) => line.includes('Unlimited brands')))

  // Free is single-user: PlanPolicy.FREE caps members at 1, so the page must not imply a team.
  // This is the pairing that would otherwise drift — the page advertising seats the server
  // refuses is exactly the failure this test exists for.
  assert.ok(
    free.highlights.some((line) => /single login|just you/i.test(line)),
    'the free tier must say it is for one person',
  )
  assert.ok(
    !free.highlights.some((line) => /\d+ team members/.test(line)),
    'free must not advertise a team-member count',
  )
  assert.ok(
    pro.highlights.some((line) => line.includes('10 team members')),
    'Pro is where teammates start',
  )
})

test('roles are advertised as a paid capability, not a free one', () => {
  // The product decision: one person runs the free tier; deciding what OTHER people may do is
  // what Pro sells. If roles ever appear in the free highlights, the page is promising a feature
  // EntitlementService.requireRoleBasedAccess refuses with a 402.
  const free = PUBLIC_TIERS.find((tier) => tier.key === 'free')
  const pro = PUBLIC_TIERS.find((tier) => tier.key === 'pro')

  assert.ok(pro.highlights.some((line) => /roles and permissions/i.test(line)))
  assert.ok(!free.highlights.some((line) => /role|permission/i.test(line)))
})

test('the landing page states no price, because none has been decided', () => {
  // A UI file is not where a pricing commitment should get made. The paid tiers say what they
  // lift, not what they cost — if this fails, someone invented a number.
  const page = read('pages/LandingPage.jsx')
  const tiers = read('shell/plan.js')

  assert.doesNotMatch(page, /\$\d/, 'no price may appear in the landing markup')
  assert.doesNotMatch(tiers, /\$\d/, 'no price may appear in the tier table')
  assert.ok(PUBLIC_TIERS.filter((t) => t.key !== 'free')
    .every((tier) => /not published/i.test(tier.note)))
})

test('the free tier is described by its ceiling, not by a countdown', () => {
  // It is capped by size, not by a clock. Implying a trial that expires would be false, and the
  // kind of false that gets noticed on day 15.
  const free = PUBLIC_TIERS.find((tier) => tier.key === 'free')

  assert.match(free.note, /no time limit/i)
  assert.doesNotMatch(free.note, /trial|\bdays?\b/i)
})

test('the invite form closes at the seat limit instead of failing', () => {
  // The server returns 402 either way, but a form that accepts an email, sends it, and then
  // reports failure wastes attention on a request that could never have succeeded.
  const page = read('pages/MembersPage.jsx')

  assert.match(page, /atMemberLimit/)
  assert.match(page, /disabled=\{busyId === 'invite' \|\| atMemberLimit\}/)
  // Pending invitations hold seats, so revoking is the immediate remedy and the copy says so.
  assert.match(page, /revoking one below frees a seat/)
})

// ── M2.1/M2.2: subscription state as the user sees it ──────────────────────

test('an account with no subscription is described as free, not as broken', () => {
  // A free account is a normal state, not a missing one. Framing it as absence would make the
  // largest group of users feel like something failed.
  const state = describeSubscription({ subscribed: false, canManage: true })

  assert.equal(state.subscribed, false)
  assert.equal(state.plan, 'free')
  assert.equal(state.tone, 'neutral')
  assert.match(state.summary, /free plan/i)
  assert.doesNotMatch(state.summary, /error|problem|missing/i)
})

test('a scheduled cancellation says what is kept and until when', () => {
  // The product feature. The competitor's most-cited complaint is cancellation being "impossible
  // to stop"; a cancel that silently confiscates paid time would be the same failure in reverse.
  const state = describeSubscription({
    subscribed: true,
    canManage: true,
    subscription: {
      plan: 'pro', status: 'active', statusLabel: 'Active',
      cancelAtPeriodEnd: true, currentPeriodEnd: '2026-09-15T00:00:00Z',
      canCancel: true, chargesMoney: true,
    },
  })

  assert.equal(state.cancelAtPeriodEnd, true)
  assert.equal(state.tone, 'warning', 'a decision the user made, not a failure')
  assert.match(state.summary, /keep full access/i)
  assert.match(state.summary, /Sep/, 'the date they keep access until must be named')
  assert.match(state.summary, /Nothing is deleted/i)
})

test('a failed payment does not read as an immediate shutdown', () => {
  // Usually an expired card, and the provider retries for days. Telling someone their workspace
  // has stopped when it has not is how a recoverable payment problem becomes a cancellation.
  const state = describeSubscription({
    subscribed: true,
    canManage: true,
    subscription: { plan: 'pro', status: 'past_due', statusLabel: 'Payment failed', chargesMoney: true },
  })

  assert.equal(state.tone, 'danger')
  assert.match(state.summary, /still active/i)
})

test('a paused subscription says the data is untouched', () => {
  // The fear on pausing is that pausing deletes something. It does not — only the limits change.
  const state = describeSubscription({
    subscribed: true,
    canManage: true,
    subscription: { plan: 'pro', status: 'paused', statusLabel: 'Paused', canResume: true, chargesMoney: true },
  })

  assert.equal(state.tone, 'warning')
  assert.match(state.summary, /data is untouched/i)
  assert.equal(state.canResume, true)
})

test('the UI never decides for itself what the lifecycle allows', () => {
  // canPause/canResume/canCancel come from the server, which owns SubscriptionState. Deriving
  // them here would be a second copy of the rules, free to disagree with the real one.
  const state = describeSubscription({
    subscribed: true,
    canManage: true,
    // Server says no to everything despite an "active" status.
    subscription: { plan: 'pro', status: 'active', canPause: false, canResume: false, canCancel: false },
  })

  assert.equal(state.canPause, false)
  assert.equal(state.canCancel, false)
})

test('an unpaid subscription is never presented as a paid one', () => {
  // The rule the whole BillingProvider design exists to hold. chargesMoney=false must survive
  // all the way to the screen.
  const unpaid = describeSubscription({
    subscribed: true,
    canManage: true,
    subscription: { plan: 'pro', status: 'active', chargesMoney: false, providerName: 'Manual / invoiced' },
  })

  assert.equal(unpaid.chargesMoney, false)

  const page = read('pages/BillingPage.jsx')
  assert.match(page, /No payment was taken/i)
  assert.match(page, /!state\.chargesMoney/)
})

test('an admin sees billing but gets no buttons', () => {
  // ACCOUNT_BILLING is OWNER-only and ACCOUNT_BILLING_READ is OWNER+ADMIN. The page renders
  // actions from the server's canManage rather than re-deriving that rule.
  const state = describeSubscription({
    subscribed: true,
    canManage: false,
    subscription: { plan: 'pro', status: 'active', canPause: true, canCancel: true },
  })

  assert.equal(state.canManage, false)

  const page = read('pages/BillingPage.jsx')
  assert.match(page, /state\.canManage \? \(/, 'actions must be gated on the server flag')
  assert.match(page, /Only the account owner can change/i)
})

test('the billing route is gated on the read permission, not the write one', () => {
  // Gating the page on account:billing would hide it from an admin entirely, when what they
  // actually need is to see it without being able to end it.
  const route = ROUTE_MANIFEST.find((entry) => entry.path === '/billing')

  assert.ok(route, 'the billing route must exist')
  assert.equal(route.permission, 'account:billing:read')
  assert.notEqual(route.permission, 'account:billing')
})

test('money is formatted from integer cents', () => {
  // Never a float: 79.99 is not representable in binary floating point, and money that does not
  // sum exactly is a reconciliation bug that surfaces months later.
  assert.match(formatAmount(7900, 'USD'), /79\.00/)
  assert.match(formatAmount(19999, 'USD'), /199\.99/)
  assert.match(formatAmount(0, 'USD'), /0\.00/)
  // An unknown currency must not blank the amount out.
  assert.match(formatAmount(7900, 'XYZ'), /79\.00/)
})

test('an unparseable date renders as nothing rather than "Invalid Date"', () => {
  assert.equal(formatDate(null), '')
  assert.equal(formatDate('not-a-date'), '')
})

// ── Stripe sandbox: paid tiers stay hidden until billing is live ───────────

test('only the free tier is advertised while billing is not live', () => {
  // Advertising a plan nobody can buy is worse than advertising nothing: someone who wants to pay
  // finds no way to, and someone who signs up expecting those limits gets the free ones.
  const hidden = visiblePublicTiers(false)

  assert.equal(hidden.length, 1)
  assert.equal(hidden[0].key, 'free')
  assert.ok(!hidden.some((tier) => tier.key === 'pro' || tier.key === 'agency'))
})

test('all tiers return once billing is live', () => {
  const live = visiblePublicTiers(true)

  assert.equal(live.length, 3)
  assert.deepEqual(live.map((tier) => tier.key), ['free', 'pro', 'agency'])
})

test('the free-only landing copy does not promise plans that cannot be bought', () => {
  // The heading and footnote both change: "Grow when the ceiling gets close" implies a purchasable
  // next step, and there is not one yet.
  const page = read('pages/LandingPage.jsx')

  assert.match(page, /billingLive/, 'the tier section must be gated')
  assert.match(page, /visiblePublicTiers/)
  assert.match(page, /Paid plans are not open yet/)
})

test('the free tier still reads correctly as the only tier', () => {
  // It is described by its ceiling rather than as a lesser version of something unavailable.
  const [free] = visiblePublicTiers(false)

  assert.match(free.note, /no time limit/i)
  assert.doesNotMatch(free.tagline, /upgrade|paid|pro\b/i)
})

// ── Landing page: the accessibility pass ──────────────────────────────────

test('hero text does not use a token that inverts with the theme', () => {
  // --ink-0 aliases --fg-inverse, which flips to #0b1017 in dark mode. The hero gradient is
  // dark in BOTH themes, so that combination painted the headline near-black on dark teal.
  // Twice regressed; this pins the fixed value instead.
  const css = read('App.css')
  const hero = css.slice(css.indexOf('.hero-panel {'), css.indexOf('.hero-panel::before'))

  assert.match(hero, /--hero-fg:\s*#fffdf7/, 'the hero needs a theme-independent foreground')
  assert.doesNotMatch(hero, /color:\s*var\(--ink-0\)/, '--ink-0 inverts and must not paint the hero')

  for (const selector of ['.landing-title', '.landing-stat-value', '.landing-tier-name']) {
    const rule = css.slice(css.indexOf(`${selector} {`), css.indexOf('}', css.indexOf(`${selector} {`)))
    assert.match(rule, /var\(--hero-fg\)/, `${selector} must take the fixed hero foreground`)
  }
})

test('the auth panel follows the theme instead of a hard-coded cream', () => {
  // It used to be rgba(255, 252, 249, 0.78) in both themes, which read as a lit rectangle
  // inside a dark window, with black-on-black social buttons.
  const css = read('App.css')
  const panel = css.slice(css.indexOf('.auth-panel {'), css.indexOf('.landing-auth-header'))

  assert.match(panel, /background:\s*var\(--surface\)/)
  assert.doesNotMatch(panel, /rgba\(255,\s*252,\s*249/)
})

test('the landing legal footnote links somewhere', () => {
  // Asking people to agree to terms the page gives them no way to read is a compliance
  // problem, not only a UX one. These are the same URLs registered with Meta and TikTok.
  const page = read('pages/LandingPage.jsx')

  assert.match(page, /https:\/\/www\.tejdux\.com\/terms\//)
  assert.match(page, /https:\/\/www\.tejdux\.com\/privacy\//)
  assert.match(page, /rel="noreferrer noopener"/)
})

test('the data deletion page is reachable from the product', () => {
  // Meta requires this page to exist AND expects a reviewer to be able to find it from the app,
  // not only from the App Dashboard field. It was linked nowhere in the UI until this was added.
  const page = read('pages/LandingPage.jsx')

  assert.match(page, /https:\/\/www\.tejdux\.com\/data-deletion\//)
})

test('one consent governs both sign-up paths', () => {
  // The checkbox used to sit INSIDE the form, above the social buttons, which read as though it
  // applied only to email sign-up while the buttons underneath silently required the same
  // agreement. It now sits below both, and both are gated on it.
  const page = read('pages/LandingPage.jsx')

  const consentAt = page.indexOf('auth-consent-shared')
  const socialAt = page.indexOf('auth-alt-actions')
  assert.ok(consentAt > -1, 'the shared consent block must exist')
  assert.ok(socialAt > -1 && consentAt > socialAt, 'consent must render below the social buttons')

  // Both providers refuse to start a sign-up until the box is ticked, matching the email path's
  // disabled CTA and the BFF, which rejects the redirect outright.
  const gated = page.match(/disabled=\{Boolean\(socialProvider\) \|\| \(isSignUp && !acceptedTerms\)\}/g)
  assert.equal(gated?.length, 2, 'both social buttons must be gated on consent')
})

test('social sign-up no longer refuses agencies', () => {
  // Federated signup always provisions a `brand` account, so an agency selection used to be
  // rejected with "use email and password instead". The post-OAuth onboarding step promotes it
  // instead, so the refusal is gone and the selection survives the redirect.
  const app = read('App.jsx')

  assert.doesNotMatch(app, /Agency workspaces are created with email and password/)
  assert.match(app, /PENDING_ACCOUNT_TYPE_KEY/, 'the choice must survive the provider redirect')
  assert.match(app, /PENDING_SOCIAL_SIGNUP_KEY/, 'the return must be recognisable as a sign-up')
})

test('the onboarding marker is consumed so a reload cannot re-ask', () => {
  // Left in place, the step would reappear on every reload of a workspace the user already named.
  const app = read('App.jsx')
  const at = app.indexOf('PENDING_SOCIAL_SIGNUP_KEY)')
  const block = app.slice(at, at + 600)

  assert.match(block, /removeItem\(PENDING_SOCIAL_SIGNUP_KEY\)/)
  assert.match(block, /removeItem\(PENDING_ACCOUNT_TYPE_KEY\)/)
})

test('onboarding re-mints the token so the header stops showing the provider name', () => {
  // The session carries brandName. Renaming the workspace without swapping the token leaves the
  // old provider-derived name on screen until the next sign-in.
  const core = read('api/core.js')
  const app = read('App.jsx')

  assert.match(core, /\/api\/brands\/onboarding/)
  const handler = app.slice(app.indexOf('const handleCompleteOnboarding'), app.indexOf('const handleCompleteOnboarding') + 700)
  assert.match(handler, /setAuthToken\(updated\.accessToken\)/)
  assert.match(handler, /applyBrandFromAuth\(updated\)/)
})

test('no hook reads a const declared later in the component', () => {
  // A `const` is not hoisted, so an effect referencing one declared below it throws ReferenceError
  // on the FIRST render — and React unmounts the whole tree, so the entire app renders blank. This
  // shipped: linkedProviderNotice was used on line 422 and declared on line 440, and every check
  // that could have caught it passed. Node's test runner never mounts the component, the Vite build
  // does not evaluate it, and every curl returned 200 because the HTML and assets were all fine.
  //
  // Checks declaration order for the state this component reads inside effects. Deliberately narrow:
  // a general "no TDZ anywhere" rule needs a parser, and the failure mode worth guarding is the one
  // that already happened.
  const app = read('App.jsx')
  const componentAt = app.indexOf('function App(')
  const body = app.slice(componentAt)

  for (const name of ['oauthErrorFromUrl', 'linkedProviderNotice', 'onboarding']) {
    const declaredAt = body.search(new RegExp(`const \\[${name}[,\\]]`))
    assert.ok(declaredAt > -1, `${name} must be declared`)

    // First use inside a useEffect body, which is what runs during render.
    const effectUse = body.indexOf(`!${name}`)
    if (effectUse > -1) {
      assert.ok(
        declaredAt < effectUse,
        `${name} is used at ${effectUse} but declared at ${declaredAt}: a const read before its ` +
          'declaration throws ReferenceError on first render and blanks the entire page',
      )
    }
  }
})

test('a failed social sign-in has somewhere to land and says what happened', () => {
  // The DPS redirects failures to /login?error=<reason>. With no such route the redirect hit the
  // catch-all, rendered the signed-out landing page, and dropped the message — so "an account
  // already exists for this email, sign in with your password then link facebook" was
  // indistinguishable from being randomly logged out.
  const app = read('App.jsx')

  assert.match(app, /path="\/login"/, 'the DPS error redirect needs a route to land on')
  assert.match(app, /searchParams.*\.get\('error'\)|get\('error'\)/, 'the reason must be read from the URL')
  assert.match(app, /authError=\{oauthErrorFromUrl \|\| authError\}/, 'and passed to the page that renders it')
})

test('the OAuth error is cleared from the URL once read', () => {
  // Left in the query string it survives a reload and a bookmark, so a user who later returns to
  // the URL is told again that a sign-in they have since completed had failed.
  const app = read('App.jsx')
  const at = app.indexOf("url.searchParams.delete('error')")
  assert.ok(at > -1, 'the error parameter must be stripped after being read')
  assert.match(app.slice(at, at + 400), /replaceState/, 'stripped via history, not a navigation')
})

test('connecting a provider goes through the DPS, never with a token in the URL', () => {
  // The BFF endpoint needs a verified caller, and being signed in is the whole proof that the
  // account belongs to whoever is attaching a provider to it. Sending the browser straight at the
  // BFF would mean putting the access token in a query string — history, Referer, access logs.
  const app = read('App.jsx')

  assert.match(app, /\/dps\/auth\/connected-accounts\/\$\{provider\}\/start/)
  assert.doesNotMatch(app, /connected-accounts.*access_token=/, 'no bearer token may travel in the URL')
})

test('settings is reachable without a workspace permission', () => {
  // visibleRoutes filters on permissions.includes(route.permission). A route with no permission
  // would match `includes(undefined)` and be hidden from everyone — including the page that manages
  // the caller's own sign-in methods, which no workspace permission should gate.
  const manifest = read('shell/routeManifest.js')

  assert.match(manifest, /path: '\/settings'/)
  assert.match(manifest, /!route\.permission \|\| permissions\.includes\(route\.permission\)/)
})

test('landing controls clear the 44px touch target floor', () => {
  const css = read('App.css')

  for (const selector of ['.landing-cta-btn', '.auth-alt-btn']) {
    const rule = css.slice(css.indexOf(`${selector} {`), css.indexOf('}', css.indexOf(`${selector} {`)))
    assert.match(rule, /min-height:\s*44px/, `${selector} was under the WCAG 2.2 SC 2.5.8 minimum`)
  }

  const tab = css.slice(css.indexOf('.auth-switch button {'), css.indexOf('.auth-switch button:not'))
  assert.match(tab, /min-height:\s*44px/)
})

test('the hero stat cards do not present rhetoric as measurement', () => {
  // They read 5x / 1 / 0. "1" and "0" are not statistics, and they sat in the largest type
  // on the page while the real enforced limits were bullets further down.
  const page = read('pages/LandingPage.jsx')
  const values = [...page.matchAll(/landing-stat-value">([^<]+)</g)].map((m) => m[1].trim())

  assert.equal(values.length, 3)
  for (const value of values) {
    assert.doesNotMatch(value, /^[01]$/, `"${value}" is a rhetorical device, not a metric`)
  }
})

test('the tier heading is a subsection of the hero, not a sibling of the form', () => {
  // As an h2 it was a peer of the auth panel's own h2, so navigating by heading presented a
  // pricing title and a form title as the same level.
  const page = read('pages/LandingPage.jsx')

  assert.match(page, /<h3 className="landing-tiers-title">/)
  assert.equal((page.match(/<h2>/g) || []).length, 1, 'the auth header should own the only h2')
})

test('the signup form comes before the pitch on a phone', () => {
  // Stacked, the hero ran 1179px tall and pushed the form 1.6 viewports down, so a returning
  // user scrolled past the whole pitch to reach a password field.
  const css = read('App.css')
  const mobile = css.slice(css.indexOf('@media (max-width: 900px)'))
  const shell = mobile.slice(mobile.indexOf('.app-shell {'), mobile.indexOf('.hero-panel,'))

  assert.match(shell, /flex-direction:\s*column/)
  assert.match(mobile, /\.landing-auth-panel\s*\{\s*order:\s*-1/)
})

test('the workspace copy avoids internal vocabulary', () => {
  // Nobody self-identifies as an "operator", and the log-in title said "tejdux.io" while the
  // badge said "Tejdux Influencer CRM" — one spelling of the brand is enough.
  // Matched against the rendered heading rather than the whole file: the comment above the
  // JSX explains the old wording and would otherwise trip a plain file-wide search.
  const page = read('pages/LandingPage.jsx')
  const heading = page.slice(page.indexOf('<h2>'), page.indexOf('</h2>'))

  assert.doesNotMatch(heading, /operator workspace/i)
  assert.doesNotMatch(heading, /tejdux\.io/)
  assert.match(heading, /Create your workspace/)
})

test('the hero shows the product, and only where it is legible', () => {
  // A visible product UI is table stakes in this category; its absence reads as an immature
  // product. But seven stage columns scaled below roughly 0.3 of source stop resolving, so
  // the shot is hidden rather than shipped as a dark band with a caption.
  const page = read('pages/LandingPage.jsx')
  const css = read('App.css')

  assert.match(page, /marketing\/workflow-board\.png/)
  assert.match(page, /<figcaption>/, 'the caption carries the meaning for assistive tech')
  assert.match(page, /alt=""/, 'decorative: the caption already says what it is')
  assert.match(page, /loading="lazy"/)

  const narrow = css.slice(css.indexOf('@media (max-width: 1160px)'), css.indexOf('@media (max-width: 900px)'))
  assert.match(narrow, /\.landing-shot\s*\{\s*display:\s*none/)
})

// ── A hard reload no longer leaves a workspace with no token ─────────────────
//
// isLoggedIn was persisted to localStorage while the access and refresh tokens deliberately were
// not. On reload the shell rebuilt itself in its signed-in state with nothing to authenticate
// with, so every request went out bare and the user got a workspace full of error banners rather
// than a login screen. The claim and the proof came from sources that could not agree.

test('isLoggedIn is not restored from the persisted snapshot', () => {
  const app = read('App.jsx')

  // The one that mattered: this flag alone gates the entire workspace branch of the router.
  assert.match(
    app,
    /const \[isLoggedIn, setIsLoggedIn\] = useState\(false\)/,
    'isLoggedIn must start false — a snapshot that cannot carry the token must not carry the claim',
  )
  assert.doesNotMatch(
    app,
    /useState\(persistedState\?\.isLoggedIn/,
    'restoring isLoggedIn is exactly the bug',
  )
})

test('isLoggedIn is not written to the snapshot either', () => {
  // Writing a value nothing reads back would leave a stale `true` in storage that still reads as
  // authoritative to anyone inspecting it.
  const app = read('App.jsx')
  const opensAt = app.indexOf('const snapshot = {')
  // Anchored forward from the opening brace: `window.localStorage.setItem` also appears earlier,
  // in loadPersistedState, and searching from zero slices backwards to an empty string that
  // passes every doesNotMatch below for the wrong reason.
  const snapshot = app.slice(opensAt, app.indexOf('window.localStorage.setItem', opensAt))
  assert.ok(snapshot.length > 100, 'the snapshot block must actually be found')

  assert.doesNotMatch(snapshot, /^\s*isLoggedIn,$/m)
  // Display state is still restored — that is what lets a re-login land back in place.
  assert.match(snapshot, /brandName,/)
  assert.match(snapshot, /campaigns,/)
})

test('tokens are still never persisted', () => {
  // The property the above depends on. If tokens ever start being written, the reasoning for
  // dropping isLoggedIn changes and this pairing should be revisited deliberately.
  const app = read('App.jsx')

  assert.match(app, /const \[authToken, setAuthToken\] = useState\(''\)/)
  assert.match(app, /const \[refreshToken, setRefreshToken\] = useState\(''\)/)
})

test('establishSession is the only path that starts a session', () => {
  // It existed, set every field correctly, and had zero call sites, while handleAuthSubmit
  // carried a near-identical copy. Two of these is how they drifted.
  const app = read('App.jsx')

  assert.match(app, /const establishSession = \(authResponse, overrides = \{\}\) =>/)
  assert.match(app, /establishSession\(authResponse, \{ userName: name/, 'the auth form must use it')

  const submit = app.slice(app.indexOf('const handleAuthSubmit'), app.indexOf('// Captures the active brand'))
  assert.doesNotMatch(submit, /setIsLoggedIn\(true\)/, 'the copy inside handleAuthSubmit must be gone')
  assert.doesNotMatch(submit, /setRefreshToken\(authResponse/, 'token handling belongs in one place')
})

test('establishSession sets the token before flipping isLoggedIn', () => {
  // The workspace-loading effect keys on both. Flipping isLoggedIn first would give it one
  // render with a stale token and fire a batch of doomed requests.
  const app = read('App.jsx')
  const body = app.slice(app.indexOf('const establishSession'))
  const fn = body.slice(0, body.indexOf('\n  }'))

  assert.ok(
    fn.indexOf('setAuthToken(') < fn.indexOf('setIsLoggedIn(true)'),
    'the token must be in place before the workspace is allowed to render',
  )
  // Every sign-in path runs through here, so this is what keeps a returning user's events from
  // arriving anonymous — the exact population week-two retention is measured over.
  assert.match(fn, /identify\(\{/, 'identify must run on every session start')
})

// ── Step 1: the SPA asks the DPS whether a session already exists ────────────
//
// The DPS sets an httpOnly cookie at sign-in and, at the end of the OAuth flow, redirects to "/"
// specifically so the SPA's first /dps/session call sees an authenticated user. Nothing was making
// that call, so a reload and a social sign-in both landed signed-out despite a valid session.

test('the app asks the DPS for an existing session at boot', () => {
  const app = read('App.jsx')

  assert.match(app, /fetchDpsSession\(DPS_BASE_URL\)/, 'boot must query /dps/session')
  assert.match(app, /useCookieSession\(DPS_BASE_URL\)/, 'a restored session must switch transport')
})

test('the app falls back to the browser origin when no DPS URL is provided', () => {
  const app = read('App.jsx')

  assert.match(app, /window\.location\.origin/, 'the shell should use the current browser origin by default')
})

test('the API transport uses the configured BFF base URL in production', () => {
  const core = read('api/core.js')

  assert.match(core, /VITE_BFF_URL/, 'the transport should read the build-time BFF URL')
  assert.match(core, /resolveApiUrl|BFF_BASE_URL/, 'the transport should resolve API URLs against the BFF origin')
})

test('the restore runs once and cannot re-enter', () => {
  // A dependency on anything the restore itself sets would re-restore over a live session.
  const app = read('App.jsx')
  const at = app.indexOf('const restore = async () =>')
  assert.ok(at > -1, 'the restore effect must exist')

  // 2400, not 1400: the effect grew when the post-social-signup onboarding check was added to it,
  // which pushed the dependency array past the old window and failed this test while the invariant
  // it guards was still perfectly intact. The window only has to reach the end of the effect.
  const effectTail = app.slice(at, at + 2400)
  assert.match(effectTail, /\}, \[\]\)/, 'the restore effect must have an empty dependency array')
})

test('transport switches to cookie mode before the session is marked live', () => {
  // establishSession flips isLoggedIn, which releases the workspace-loading effect. Switching
  // after that would fire one round of requests down the bearer path with no token to send.
  const app = read('App.jsx')
  const body = app.slice(app.indexOf('const restore = async () =>'))
  const fn = body.slice(0, body.indexOf('restore()'))

  assert.ok(
    fn.indexOf('useCookieSession(') < fn.indexOf('establishSession('),
    'cookie mode must be set before establishSession',
  )
})

test('cookie mode proxies through the DPS and sends the CSRF header', () => {
  const core = read('api/core.js')

  assert.match(core, /\$\{dpsBaseUrl\}\/dps\/api/, 'proxied calls go to /dps/api')
  assert.match(core, /credentials: 'include'/, 'the session cookie must be sent')
  assert.match(core, /X-XSRF-TOKEN/, 'double-submit CSRF token must be attached')
})

test('auth endpoints are never proxied', () => {
  // /api/auth/refresh rotates a token the browser is not holding, and login/signup are how a
  // session begins — none can travel through a session that does not exist yet.
  const core = read('api/core.js')

  assert.match(core, /!path\.startsWith\('\/api\/auth\/'\)/)
})

test('a bearer session is unaffected by cookie mode existing', () => {
  // Password sign-in still gets a token directly and must keep using it.
  const core = read('api/core.js')

  assert.match(core, /const proxied = cookieMode && !token/, 'a token always wins over cookie mode')
  assert.match(core, /let cookieMode = false/, 'cookie mode must be off by default')
})

test('permissions fall back to the DPS session when there is no token', () => {
  // Both sources trace to the same token: the DPS reads perms out of it rather than recomputing,
  // deliberately, because two permission matrices disagreeing once locked FINANCE users out.
  const app = read('App.jsx')

  assert.match(app, /authToken \? readPermissionsFromToken\(authToken\) : cookiePermissions/)
})

test('logout ends the DPS session rather than only the local one', () => {
  // Clearing local state alone would leave the session alive server-side, and the next boot would
  // silently restore the person who just signed out.
  const app = read('App.jsx')
  const handler = app.slice(app.indexOf('const handleLogout'), app.indexOf('const handleSessionRetry'))

  assert.match(handler, /dps\/auth\/logout/, 'the DPS session must be ended')
  assert.match(handler, /clearCookieSession\(\)/, 'transport must return to bearer mode')
})

test('the first paint waits for the session answer, except for invitations', () => {
  // Otherwise the login page renders and is replaced by the workspace — a sign-in flashing past
  // on every reload. An invitee has no session and came for that one page, so they do not wait.
  const app = read('App.jsx')

  assert.match(app, /if \(restoringSession && window\.location\.pathname !== '\/accept-invitation'\)/)
  assert.match(app, /Restoring your session/)
})

test('an unreachable DPS resolves to anonymous rather than throwing', () => {
  // On a first visit "no session" is the normal answer, and a network failure means we do not know
  // of one — both should land on the login page, not a console error or a broken shell.
  const core = read('api/core.js')
  const fn = core.slice(core.indexOf('export async function fetchDpsSession'))

  assert.match(fn.slice(0, fn.indexOf('\n}')), /catch \{\s*return \{ authenticated: false \}/)
})

test('the DPS logout carries a CSRF token', () => {
  // Found by running it, not by a test: without the header the DPS answers 403, the session
  // survives, and the user is told they signed out while the cookie stays valid — so the next
  // boot silently restores them. Verified live: 403 without, 204 with.
  const app = read('App.jsx')
  const handler = app.slice(app.indexOf('const handleLogout'), app.indexOf('const handleSessionRetry'))

  assert.match(handler, /headers: dpsCsrfHeaders\(\)/, 'logout must send X-XSRF-TOKEN')
})

test('one implementation reads the CSRF cookie', () => {
  const core = read('api/core.js')
  const occurrences = core.match(/document\.cookie\.match/g) || []

  assert.equal(occurrences.length, 1, 'a second copy would be the one that drifts')
  assert.match(core, /export function dpsCsrfHeaders/)
})
