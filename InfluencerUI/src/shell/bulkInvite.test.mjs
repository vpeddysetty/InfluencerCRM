import test from 'node:test'
import assert from 'node:assert/strict'

import {
  MAX_BULK_INVITE,
  buildInviteRows,
  describeBatch,
  describeOutcome,
  formatBatchSummary,
  inferInviteColumns,
  looksLikeEmail,
  parseEmailList,
  rowsFromCsv,
  summarizeResult,
} from './bulkInvite.js'
import { describeUsage } from './plan.js'

const ROLES = ['ADMIN', 'MANAGER', 'MARKETER', 'ANALYST', 'FINANCE']
const opts = { roles: ROLES }

// ---------------------------------------------------------------- parsing input

test('a pasted list splits on every separator people actually paste', () => {
  // A spreadsheet column, a mail client "to" line, and a Slack message all arrive differently.
  const emails = parseEmailList('a@x.com, b@x.com; c@x.com\nd@x.com e@x.com')
  assert.deepEqual(emails, ['a@x.com', 'b@x.com', 'c@x.com', 'd@x.com', 'e@x.com'])
})

test('a name-wrapped address yields the address', () => {
  assert.deepEqual(parseEmailList('<ana@x.com>, "bo@x.com"'), ['ana@x.com', 'bo@x.com'])
})

test('column headers are recognised however they are written', () => {
  assert.deepEqual(inferInviteColumns(['Email Address', 'Role', 'Brand ID']),
    { email: 0, role: 1, brandId: 2 })
  assert.deepEqual(inferInviteColumns(['E-Mail', 'Access Level']),
    { email: 0, role: 1, brandId: null })
})

test('a header-less file keeps its first address instead of eating it', () => {
  // parseCsv always treats line 1 as a header. A bare list of addresses is the most common paste,
  // and swallowing its first line would silently drop one invitee — the kind of bug nobody reports
  // because nothing visibly failed.
  const parsed = { headers: ['first@x.com'], rows: [['second@x.com']] }
  assert.deepEqual(rowsFromCsv(parsed).map((row) => row.email), ['first@x.com', 'second@x.com'])
})

test('a headed file reads the columns rather than the positions', () => {
  const parsed = {
    headers: ['Role', 'Email'],
    rows: [['ADMIN', 'ana@x.com']],
  }
  assert.deepEqual(rowsFromCsv(parsed), [{ email: 'ana@x.com', role: 'ADMIN', brandId: '' }])
})

// ---------------------------------------------------------------- row decisions

test('duplicates collapse case-insensitively and name the first row', () => {
  // The column is citext and the server revokes an existing pending invitation before inserting a
  // new one, so two rows for one address would leave one live invitation charged as two seats.
  const preview = buildInviteRows(
    [{ email: 'bob@x.com' }, { email: 'Bob@X.com' }, { email: 'BOB@X.COM' }], opts)

  assert.equal(preview.sendable.length, 1)
  assert.equal(preview.duplicates.length, 2)
  assert.equal(preview.duplicates[0].issue.message, 'Duplicate of row 1.')
})

test('a repeated address keeps the first row\'s role', () => {
  // Matching the server. A file naming one person twice at two roles is a copy-paste artifact far
  // more often than a promotion, and taking the later, broader row would grant more than asked.
  const preview = buildInviteRows(
    [{ email: 'bob@x.com', role: 'MARKETER' }, { email: 'bob@x.com', role: 'ADMIN' }], opts)

  assert.equal(preview.sendable.length, 1)
  assert.equal(preview.sendable[0].role, 'MARKETER')
})

test('a row without a role takes the default rather than failing', () => {
  const preview = buildInviteRows([{ email: 'a@x.com' }], { ...opts, defaultRole: 'ANALYST' })
  assert.equal(preview.sendable[0].role, 'ANALYST')
})

test('an explicit role beats the default', () => {
  const preview = buildInviteRows([{ email: 'a@x.com', role: 'finance' }],
    { ...opts, defaultRole: 'MARKETER' })
  assert.equal(preview.sendable[0].role, 'FINANCE')
})

test('OWNER is called out on the row rather than bouncing the upload', () => {
  // The server refuses the whole batch for this. Naming it here means one row gets fixed instead of
  // an entire file being rejected after the fact.
  const preview = buildInviteRows([{ email: 'boss@x.com', role: 'OWNER' }], opts)
  assert.equal(preview.sendable.length, 0)
  assert.equal(preview.invalid[0].issue.kind, 'owner')
})

test('what is not an address is caught before sending', () => {
  const preview = buildInviteRows(
    [{ email: 'Ana Ruiz' }, { email: 'no-at-sign' }, { email: 'a@b' }, { email: 'ok@x.com' }], opts)

  assert.equal(preview.sendable.length, 1)
  assert.equal(preview.invalid.length, 3)
})

test('blank rows are dropped, not counted or refused', () => {
  // Every CSV and every paste ends with trailing newlines.
  const preview = buildInviteRows([{ email: 'a@x.com' }, { email: '   ' }, { email: '' }], opts)
  assert.equal(preview.rows.length, 1)
})

test('rows keep their order so results line up against the uploaded file', () => {
  const preview = buildInviteRows(
    [{ email: 'a@x.com' }, { email: 'a@x.com' }, { email: 'b@x.com' }], opts)
  assert.deepEqual(preview.rows.map((row) => row.index), [0, 1, 2])
})

test('an address is judged the same way the server judges it', () => {
  // If these two disagree, the preview promises to send a row the server then refuses — the wasted
  // upload this module exists to prevent.
  assert.equal(looksLikeEmail('ana@x.com'), true)
  assert.equal(looksLikeEmail('ana@sub.example.co.uk'), true)
  assert.equal(looksLikeEmail('a@b'), false)
  assert.equal(looksLikeEmail('two@@x.com'), false)
  assert.equal(looksLikeEmail('trailing@x.'), false)
  assert.equal(looksLikeEmail('has space@x.com'), false)
  assert.equal(looksLikeEmail('@x.com'), false)
})

// ---------------------------------------------------------------- capacity

test('a batch that lands exactly on the limit may be sent', () => {
  // The boundary that matters. Blocking here would refuse the batch an admin sized deliberately
  // after reading the seat count on this very screen.
  const preview = buildInviteRows(list(7), opts)
  const batch = describeBatch(preview, 7)

  assert.equal(batch.canSend, true)
  assert.equal(batch.overCapacity, false)
})

test('one row past the limit is blocked, and says how many to remove', () => {
  const preview = buildInviteRows(list(8), opts)
  const batch = describeBatch(preview, 7)

  assert.equal(batch.canSend, false)
  assert.equal(batch.overCapacity, true)
  assert.match(batch.blocker, /Remove 1/)
})

test('duplicates and invalid rows do not consume seats', () => {
  // Only what would actually be created counts. Charging for a row the server will skip would block
  // a re-uploaded roster that asks for almost nothing.
  const preview = buildInviteRows(
    [{ email: 'a@x.com' }, { email: 'a@x.com' }, { email: 'b@x.com' }], opts)

  assert.equal(describeBatch(preview, 2).canSend, true)
})

test('an unlimited plan is never blocked on seats', () => {
  const preview = buildInviteRows(list(40), opts)
  assert.equal(describeBatch(preview, null).canSend, true)
  assert.equal(describeBatch(preview, null).unlimited, true)
})

test('an oversized batch is blocked at the same cap the server uses', () => {
  const preview = buildInviteRows(list(MAX_BULK_INVITE + 1), opts)
  const batch = describeBatch(preview, null)

  assert.equal(batch.canSend, false)
  assert.match(batch.blocker, new RegExp(String(MAX_BULK_INVITE)))
})

test('a batch is blocked while any row still needs fixing', () => {
  // The server refuses the whole upload for an unreadable row, so sending would waste the trip.
  const preview = buildInviteRows([{ email: 'ok@x.com' }, { email: 'not-an-email' }], opts)
  assert.equal(describeBatch(preview, 10).canSend, false)
})

test('an empty batch is blocked rather than sent', () => {
  assert.equal(describeBatch(buildInviteRows([], opts), 10).canSend, false)
})

test('the seat summary never renders unlimited as a number', () => {
  const preview = buildInviteRows(list(3), opts)
  assert.equal(formatBatchSummary(describeBatch(preview, 8)), '3 seats needed — 8 available')
  assert.match(formatBatchSummary(describeBatch(preview, null)), /unlimited/)
  assert.equal(formatBatchSummary(describeBatch(buildInviteRows(list(1), opts), 4)),
    '1 seat needed — 4 available')
})

// ---------------------------------------------------------------- remaining seats

test('remaining seats come from the plan and never go negative', () => {
  // An account CAN be over a limit: the free member cap dropped from 3 to 1 beneath accounts that
  // already had more. Negative room would render as nonsense and break the batch arithmetic.
  assert.equal(describeUsage({ resource: 'member', used: 3, limit: 10 }).remaining, 7)
  assert.equal(describeUsage({ resource: 'member', used: 10, limit: 10 }).remaining, 0)
  assert.equal(describeUsage({ resource: 'member', used: 6, limit: 1 }).remaining, 0)
})

test('an unlimited resource reports null remaining, not a large number', () => {
  // A number here would be rendered as a cap by anything that formats it.
  assert.equal(describeUsage({ resource: 'member', used: 400, limit: -1 }).remaining, null)
})

// ---------------------------------------------------------------- results

test('a skipped row reads as neutral, only a failure as danger', () => {
  // Nothing went wrong when a row is skipped. A screen of yellow badges for a correct outcome is
  // how a UI teaches people to ignore colour.
  assert.equal(describeOutcome('skipped_already_member').tone, 'neutral')
  assert.equal(describeOutcome('skipped_duplicate').tone, 'neutral')
  assert.equal(describeOutcome('invited').tone, 'success')
  assert.equal(describeOutcome('failed').tone, 'danger')
})

test('an unknown outcome renders rather than blanking the row', () => {
  assert.equal(describeOutcome('something_new').label, 'something_new')
})

test('the summary mentions failures last, where they stay read', () => {
  assert.equal(summarizeResult({ invited: 24, skipped: 6, failed: 0 }), '24 invited, 6 skipped.')
  assert.equal(summarizeResult({ invited: 2, skipped: 0, failed: 1 }), '2 invited, 1 failed.')
  assert.equal(summarizeResult({ invited: 3, skipped: 0, failed: 0 }), '3 invited.')
})

function list(count) {
  return Array.from({ length: count }, (unused, i) => ({ email: `person${i}@x.com` }))
}
