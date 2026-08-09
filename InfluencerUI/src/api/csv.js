// CSV export (roadmap M1.5).
//
// Zero export of any kind existed anywhere in the repo — no text/csv, no application/pdf, no
// exportCsv, in any format from any screen. For the agency segment that is disqualifying: an
// agency has to give its client something, and "log into our tool" is not an answer.
//
// Client-side by design. Every row shown on these screens is already in the browser, having come
// through the same tenancy-scoped endpoints that filtered it. Adding an export endpoint would
// duplicate that filtering server-side and create a second place for a tenancy bug to live.
// CSV is also what agencies actually paste into client decks — PDF is M8, and is parity, not wedge.
//
// The tradeoff, stated: this exports the CURRENT PAGE of data the screen holds. Where a list is
// paginated, the export matches what the user is looking at. Exporting everything regardless of
// the view would be a different feature and a surprising one.

// Extension included so this module loads under plain `node --test` as well as through Vite.
// The test suite runs on node directly and does not apply Vite's extensionless resolution.
import { EVENTS, track } from './analytics.js'

/**
 * Escapes one CSV field per RFC 4180.
 *
 * The injection guard matters more than the quoting. A field beginning `=`, `+`, `-` or `@` is
 * executed as a formula when the file is opened in Excel or Sheets — so a creator named
 * `=HYPERLINK(...)` becomes code running on the client's machine, from a file our customer sent
 * them. Prefixing a tab neutralises it while displaying identically.
 */
function escapeField(value) {
  if (value === null || value === undefined) {
    return ''
  }

  let text = String(value)

  if (/^[=+\-@\t\r]/.test(text)) {
    text = `\t${text}`
  }

  // Quote when the field contains a delimiter, a quote, or a newline. Embedded quotes double.
  if (/[",\n\r]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`
  }

  return text
}

/**
 * Builds a CSV document.
 *
 * @param {Array<{key: string, header: string, value?: (row: object) => unknown}>} columns
 * @param {Array<object>} rows
 */
export function toCsv(columns, rows) {
  const safeColumns = Array.isArray(columns) ? columns : []
  const safeRows = Array.isArray(rows) ? rows : []

  const lines = [safeColumns.map((column) => escapeField(column.header)).join(',')]

  for (const row of safeRows) {
    lines.push(
      safeColumns
        .map((column) => escapeField(column.value ? column.value(row) : row?.[column.key]))
        .join(','),
    )
  }

  // CRLF per RFC 4180. Excel on Windows is the dominant consumer and is the least forgiving.
  return lines.join('\r\n')
}

/** `creators-2026-08-07.csv` — dated so a client receiving several can tell them apart. */
export function csvFilename(prefix) {
  const stamp = new Date().toISOString().slice(0, 10)
  return `${prefix}-${stamp}.csv`
}

/**
 * Triggers a browser download and records the export event.
 *
 * The analytics call lives here rather than at each call site so a new export cannot be added
 * without being counted — the roadmap's M1 signal depends on knowing whether anyone exports.
 */
export function downloadCsv(filename, csvText, { source } = {}) {
  // A BOM, so Excel reads the file as UTF-8. Without it, any non-ASCII creator name renders as
  // mojibake — and the export lands in front of the customer's client, not the customer.
  const blob = new Blob([`﻿${csvText}`], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)

  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)

  // Released on the next tick: revoking synchronously can cancel the download in some browsers.
  setTimeout(() => URL.revokeObjectURL(url), 0)

  track(EVENTS.EXPORT_CLICKED, { source: source || filename })
}

/** Compose the three steps. The only function most call sites need. */
export function exportCsv({ prefix, columns, rows, source }) {
  const filename = csvFilename(prefix)
  downloadCsv(filename, toCsv(columns, rows), { source: source || prefix })
  return filename
}
