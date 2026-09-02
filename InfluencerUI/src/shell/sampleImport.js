/**
 * A creator CSV a new workspace can download, fill in, and upload (roadmap PR-02).
 *
 * <p><b>Why this rather than seeding demo data.</b> The obvious version of "help someone start" is
 * to write sample creators into their workspace. That was considered and rejected: seeded rows spend
 * a free tier's 25-creator budget, are indistinguishable from real ones the moment they exist, and
 * would tick the activation checklist's first step with a creator the user never added — the exact
 * "checklist that lies" failure `activation.js` is built to avoid. A file they download, edit and
 * own has none of those problems and answers the same question: what is this supposed to look like?
 *
 * <p><b>The columns are the ones the importer actually recognises.</b> `agent_service`'s mapper
 * maps `handle`, `name`, `email`, `platform`, `follower_count` and `engagement_rate` onto creator
 * attributes, and accepts common aliases (`username`, `followers`, `engagement`). Using the
 * canonical spellings means the sample maps cleanly with no correction step — a sample file whose
 * own columns need remapping teaches the wrong lesson on the first try.
 *
 * <p><b>Three rows, not thirty.</b> The file is a shape to copy, not a dataset. Enough rows to make
 * the pattern obvious — two platforms, one creator missing an optional field — and few enough that
 * nobody mistakes it for data worth keeping.
 */

/** Canonical headers, in the order a person reads them: who, where, how big. */
export const SAMPLE_IMPORT_HEADERS = Object.freeze([
  'handle',
  'name',
  'email',
  'platform',
  'follower_count',
  'engagement_rate',
])

/**
 * The example rows.
 *
 * <p>Deliberately obvious placeholders. A sample that reads like real data is a sample somebody
 * uploads unedited, and then a workspace has three creators who do not exist — which is the failure
 * mode of the demo-seed idea this replaces, arrived at by a longer road.
 *
 * <p>The third row leaves `email` and `engagement_rate` empty on purpose: the importer accepts a
 * partial row, and showing that is more useful than three complete ones, because a real roster
 * always has gaps and the first question is whether they are allowed.
 */
export const SAMPLE_IMPORT_ROWS = Object.freeze([
  ['@example_creator_one', 'Example Creator One', 'one@example.com', 'instagram', '18500', '3.4'],
  ['@example_creator_two', 'Example Creator Two', 'two@example.com', 'tiktok', '42000', '5.1'],
  ['@example_creator_three', 'Example Creator Three', '', 'youtube', '7300', ''],
])

/**
 * Quote a field for CSV.
 *
 * <p>Only when it needs it, so the file stays readable in a text editor. A name carrying a comma is
 * the ordinary case this exists for; a quote inside a quoted field is doubled, per RFC 4180, which
 * is what every spreadsheet expects.
 */
function csvField(value) {
  const text = String(value ?? '')
  return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/**
 * The sample file as CSV text.
 *
 * <p>CRLF line endings, deliberately: RFC 4180 specifies them, and Excel on Windows — which is what
 * a brand manager opens a .csv with — treats a bare LF file as one long line in some locales. This
 * is the one place in this repo where CRLF is the correct output rather than an accident.
 */
export function sampleImportCsv() {
  const lines = [SAMPLE_IMPORT_HEADERS, ...SAMPLE_IMPORT_ROWS]
  return lines.map((row) => row.map(csvField).join(',')).join('\r\n') + '\r\n'
}

/** The filename offered to the browser. Dated so a second download does not silently overwrite. */
export function sampleImportFilename() {
  return 'tejdux-creator-import-sample.csv'
}

/**
 * Hand the browser the sample file.
 *
 * <p>Lives here rather than at the call site so both copies of the import page do the same thing:
 * the shell has `api/csv.js` and the CampaignsUI remote does not, and a caller-side implementation
 * would mean two downloads that differ in whether Excel can read them.
 *
 * <p>The BOM is why: without it Excel reads a UTF-8 CSV as the system codepage, and the first
 * creator name carrying an accent becomes mojibake. `api/csv.js` records the same reasoning for
 * exports, which land in front of the customer's client rather than the customer.
 */
export function downloadSampleImport() {
  const blob = new Blob([`﻿${sampleImportCsv()}`], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = sampleImportFilename()
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // Revoked on the next tick rather than immediately: Safari has historically cancelled the
  // download if the object URL is released in the same frame as the click.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
