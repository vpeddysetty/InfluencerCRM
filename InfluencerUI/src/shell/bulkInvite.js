/**
 * Turning a pasted list or an uploaded file into invitations.
 *
 * <p>Plain `.js` for the same reason `plan.js` is: the test runner is bare `node --test` with no
 * JSX loader, so the logic worth asserting has to be importable by Node. And this is worth
 * asserting — it decides which rows a user is told will be sent, and every failure here is quiet.
 * A duplicate that slips through gets silently dropped by the server; a seat count that is one out
 * turns a confident "send" into a 402.
 *
 * <p><b>The server is the authority.</b> Nothing here enforces anything — `BulkMemberInvitationService`
 * does, and it re-checks every one of these rules. This exists so the answer is visible BEFORE the
 * batch is sent, because an all-or-nothing refusal after the fact is a wasted upload.
 */

/** Must match `BulkMemberInvitationService.MAX_BATCH` in the BFF. */
export const MAX_BULK_INVITE = 50

/** Must match `BulkMemberInvitationService.DEFAULT_ROLE`. */
export const DEFAULT_INVITE_ROLE = 'MARKETER'

/** Header names that mean each field. Matched loosely — a spreadsheet column is hand-typed. */
const COLUMN_ALIASES = Object.freeze({
  email: ['email', 'email address', 'e mail', 'mail', 'work email', 'member email'],
  role: ['role', 'access', 'permission', 'permissions', 'access level'],
  brandId: ['brand id', 'brand', 'brandid', 'workspace', 'workspace id'],
})

function normalizeHeader(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

/**
 * Whether a string is plausibly an address.
 *
 * <p>Deliberately not an RFC 5322 validator. The real test of an address is whether mail to it
 * arrives, and an over-strict pattern rejects valid addresses for nothing. This catches what a
 * spreadsheet column actually contains when it is wrong: a person's name, a header row that was not
 * recognised, a phone number.
 *
 * <p>Kept in step with the same check on the server. If they disagree, the preview promises to send
 * a row the server then refuses — which is exactly the wasted upload this module exists to prevent.
 */
export function looksLikeEmail(value) {
  const email = String(value || '').trim().toLowerCase()
  const at = email.indexOf('@')
  return at > 0
    && at === email.lastIndexOf('@')
    && email.indexOf('.', at) > at + 1
    && !email.endsWith('.')
    && !email.includes(' ')
}

/**
 * Which column holds what.
 *
 * <p>Returns `null` for `email` when no header matches, which is the signal that the file has no
 * header row at all — a bare list of addresses, one per line, is the most common thing someone
 * pastes, and treating its first line as a header would silently swallow one invitee.
 */
export function inferInviteColumns(headers = []) {
  const normalized = headers.map(normalizeHeader)
  const find = (aliases) => {
    const index = normalized.findIndex((header) => aliases.includes(header))
    return index === -1 ? null : index
  }
  return {
    email: find(COLUMN_ALIASES.email),
    role: find(COLUMN_ALIASES.role),
    brandId: find(COLUMN_ALIASES.brandId),
  }
}

/**
 * Splits a pasted blob into addresses.
 *
 * <p>Commas, semicolons, newlines and spaces all separate, because all four are what people
 * actually paste — a column copied out of a spreadsheet, a mail client's "to" line, a Slack message.
 * Angle brackets are stripped so `Ana <ana@x.com>` yields the address rather than being refused.
 */
export function parseEmailList(text) {
  return String(text || '')
    .split(/[\s,;]+/)
    .map((token) => token.replace(/^[<("']+|[>)"']+$/g, '').trim())
    .filter(Boolean)
}

/**
 * Rows from a parsed CSV, whether or not it had a header.
 *
 * @param parsed `{ headers, rows }` from `parseCsv` — which always treats line 1 as a header, so a
 *               header-less file arrives with its first address sitting in `headers`
 */
export function rowsFromCsv(parsed) {
  const headers = parsed?.headers || []
  const body = parsed?.rows || []
  const columns = inferInviteColumns(headers)

  if (columns.email === null) {
    // No recognisable header, so line 1 was data. Put it back rather than losing it.
    return [headers, ...body]
      .filter((cells) => cells && cells.some((cell) => String(cell || '').trim()))
      .map((cells) => ({ email: cells[0], role: cells[1], brandId: cells[2] }))
  }

  return body.map((cells) => ({
    email: cells[columns.email],
    role: columns.role === null ? '' : cells[columns.role],
    brandId: columns.brandId === null ? '' : cells[columns.brandId],
  }))
}

/**
 * Everything the preview needs to show before anything is sent.
 *
 * <p>Mirrors the server's gates in the same order, so the preview and the eventual response agree.
 * The one thing it cannot know is who is already a member or already invited — those live on the
 * server, and guessing at them here would mean showing a row as sendable that comes back skipped.
 *
 * @returns `{ rows, invalid, duplicates, sendable }` where `rows` keeps request order and every row
 *          carries the `issue` that will be shown against it
 */
export function buildInviteRows(input, { defaultRole = DEFAULT_INVITE_ROLE, roles = [] } = {}) {
  const known = new Set(roles.length ? roles : [defaultRole])
  const seen = new Map()
  const rows = []

  input.forEach((raw, position) => {
    const email = String(raw?.email || '').trim()
    if (!email) {
      return
    }
    const key = email.toLowerCase()
    const role = String(raw?.role || '').trim().toUpperCase() || defaultRole
    const brandId = String(raw?.brandId || '').trim() || null
    const index = rows.length

    let issue = null
    if (!looksLikeEmail(email)) {
      issue = { kind: 'invalid_email', message: 'Not an email address.' }
    } else if (role === 'OWNER') {
      // The server refuses the whole batch for this. Naming it here means the user fixes one row
      // rather than having an upload bounced.
      issue = { kind: 'owner', message: 'Ownership cannot be granted by invitation.' }
    } else if (!known.has(role)) {
      issue = { kind: 'unknown_role', message: `Unknown role: ${role}.` }
    } else if (seen.has(key)) {
      // Case-insensitive: the column is citext, so Bob@x.com and bob@x.com are one person. The
      // first occurrence wins, matching the server — including its role.
      issue = { kind: 'duplicate', message: `Duplicate of row ${seen.get(key) + 1}.` }
    }

    if (!seen.has(key)) {
      seen.set(key, index)
    }
    rows.push({ index, position, email, role, brandId, issue })
  })

  const invalid = rows.filter((row) => row.issue && row.issue.kind !== 'duplicate')
  const duplicates = rows.filter((row) => row.issue?.kind === 'duplicate')

  return {
    rows,
    invalid,
    duplicates,
    // What would actually be created, which is the number the seat count has to be compared with.
    sendable: rows.filter((row) => !row.issue),
  }
}

/**
 * Whether a batch may be sent, and why not when it may not.
 *
 * <p>`remaining` is `null` on an unlimited plan, which is never a blocker — an agency account must
 * not be stopped by an arithmetic branch written for metered ones.
 *
 * <p>Uses `>` on the seat comparison, matching the server's `<=` allowance: a batch that lands
 * exactly on the limit fills it and is fine. Getting this backwards blocks the batch an admin sized
 * deliberately after reading the number on this very screen.
 */
export function describeBatch(preview, remaining) {
  const needed = preview.sendable.length
  const overCapacity = remaining !== null && remaining !== undefined && needed > remaining
  const overMax = preview.rows.length > MAX_BULK_INVITE

  let blocker = null
  if (overMax) {
    blocker = `Up to ${MAX_BULK_INVITE} invitations per upload; this has ${preview.rows.length}.`
  } else if (preview.invalid.length) {
    blocker = `${preview.invalid.length} ${preview.invalid.length === 1 ? 'row needs' : 'rows need'} fixing before this can be sent.`
  } else if (!needed) {
    blocker = 'Nothing to send.'
  } else if (overCapacity) {
    blocker = `${needed} seats needed, ${remaining} available. Remove ${needed - remaining} or upgrade.`
  }

  return {
    needed,
    remaining: remaining ?? null,
    unlimited: remaining === null || remaining === undefined,
    overCapacity,
    canSend: !blocker,
    blocker,
  }
}

/** "27 seats needed — 40 available". Unlimited never renders a denominator that looks like a cap. */
export function formatBatchSummary(batch) {
  const seats = `${batch.needed} ${batch.needed === 1 ? 'seat' : 'seats'} needed`
  return batch.unlimited ? `${seats} — unlimited available` : `${seats} — ${batch.remaining} available`
}

/** How a per-row result should read. Keys match `BulkMemberInvitationService.Outcome`. */
const OUTCOME_LABELS = Object.freeze({
  invited: { label: 'Invited', tone: 'success' },
  // Skips are neutral, not warnings. Nothing went wrong, and a screen of yellow badges for a
  // correct outcome is how a UI teaches people to ignore colour.
  skipped_duplicate: { label: 'Duplicate', tone: 'neutral' },
  skipped_already_member: { label: 'Already a member', tone: 'neutral' },
  skipped_already_invited: { label: 'Already invited', tone: 'neutral' },
  failed: { label: 'Failed', tone: 'danger' },
})

export function describeOutcome(outcome) {
  return OUTCOME_LABELS[outcome] || { label: outcome || 'Unknown', tone: 'neutral' }
}

/**
 * The one-line summary of a finished batch.
 *
 * <p>Leads with what was created and mentions failures last, because a failure is the only part
 * that needs acting on and it should be the phrase left in the reader's head.
 */
export function summarizeResult(result) {
  const parts = [`${result?.invited ?? 0} invited`]
  if (result?.skipped) {
    parts.push(`${result.skipped} skipped`)
  }
  if (result?.failed) {
    parts.push(`${result.failed} failed`)
  }
  return `${parts.join(', ')}.`
}
