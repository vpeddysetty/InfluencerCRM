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
