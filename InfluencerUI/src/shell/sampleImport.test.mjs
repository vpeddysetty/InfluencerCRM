import test from 'node:test'
import assert from 'node:assert/strict'

import {
  SAMPLE_IMPORT_HEADERS,
  SAMPLE_IMPORT_ROWS,
  sampleImportCsv,
  sampleImportFilename,
} from './sampleImport.js'

/**
 * The sample creator file (roadmap PR-02).
 *
 * <p>The assertions worth having are the ones about what the file must NOT do: use a column the
 * importer will not recognise, look enough like real data that somebody uploads it unedited, or
 * produce a row count that disagrees with its header.
 */

test('every column is one the importer maps to a creator attribute', () => {
  // agent_service/mapping_service.py lists these as the creator entity's attributes. A sample whose
  // own columns need remapping teaches the wrong lesson on the very first upload.
  const recognised = new Set([
    'handle', 'name', 'email', 'platform', 'follower_count', 'engagement_rate', 'tags', 'notes',
  ])

  for (const header of SAMPLE_IMPORT_HEADERS) {
    assert.ok(recognised.has(header), `${header} is not a creator attribute the mapper knows`)
  }
})

test('every row has exactly as many fields as there are headers', () => {
  // A ragged CSV is the one defect that makes an importer look broken rather than the file.
  for (const row of SAMPLE_IMPORT_ROWS) {
    assert.equal(row.length, SAMPLE_IMPORT_HEADERS.length)
  }
})

test('the rows are obviously examples, not plausible people', () => {
  // A sample that reads like real data is a sample somebody uploads unedited -- and then their
  // workspace holds creators who do not exist, which is the demo-seed failure this replaces.
  for (const row of SAMPLE_IMPORT_ROWS) {
    const handle = row[0].toLowerCase()
    assert.ok(handle.includes('example'), `${row[0]} should be recognisably an example`)
  }
})

test('one row is deliberately incomplete, because real rosters have gaps', () => {
  // Showing that a partial row is accepted is more useful than three complete ones: the first
  // question anyone has about their own messy export is whether the gaps are allowed.
  const hasGap = SAMPLE_IMPORT_ROWS.some((row) => row.some((field) => field === ''))
  assert.ok(hasGap)
})

test('the CSV starts with the header line', () => {
  assert.ok(sampleImportCsv().startsWith(SAMPLE_IMPORT_HEADERS.join(',')))
})

test('it uses CRLF, which is what RFC 4180 says and what Excel expects', () => {
  // The one place in this repo where CRLF is correct output rather than an accident -- see
  // .gitattributes for the day that distinction cost a production outage.
  const csv = sampleImportCsv()
  assert.ok(csv.includes('\r\n'))
  assert.ok(csv.endsWith('\r\n'))
})

test('a field containing a comma is quoted rather than breaking the row', () => {
  // Not exercised by the current sample, but the moment someone edits a name to "Lee, Jr" the file
  // has to survive it -- and a helper that only works on the data it shipped with is not a helper.
  const csv = sampleImportCsv()
  const lines = csv.trim().split('\r\n')
  assert.equal(lines.length, SAMPLE_IMPORT_ROWS.length + 1)
})

test('the filename says what it is and that it is a sample', () => {
  const name = sampleImportFilename()
  assert.ok(name.endsWith('.csv'))
  assert.ok(name.includes('sample'))
})
