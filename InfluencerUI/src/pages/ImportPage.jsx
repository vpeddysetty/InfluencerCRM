import { useMemo, useRef, useState } from 'react'
import { MdsInlineCode, MdsKicker, MdsNote, MdsSectionRule } from '../components/Mds'
import { MAPPING_CONFIDENCE_THRESHOLD } from '../constants'
import { downloadSampleImport } from '../shell/sampleImport'

// Labels are written for someone looking at their own spreadsheet, not at the schema. "Campaign
// relationship" is the row that ties a creator to a campaign — described here by what it holds
// rather than by its table name.
const ENTITY_ATTRIBUTE_OPTIONS = {
  campaign: [
    { value: '', label: 'Skip this column' },
    { value: 'name', label: 'Campaign name' },
    { value: 'budget', label: 'Budget' },
    { value: 'status', label: 'Status' },
    { value: 'customAttributes', label: 'Keep as extra detail' },
  ],
  creator: [
    { value: '', label: 'Skip this column' },
    { value: 'name', label: 'Creator name' },
    { value: 'handle', label: 'Handle' },
    { value: 'platform', label: 'Platform' },
    { value: 'email', label: 'Email' },
    { value: 'customAttributes', label: 'Keep as extra detail' },
  ],
  campaign_creator: [
    { value: '', label: 'Skip this column' },
    { value: 'stage', label: 'Stage in your pipeline' },
    { value: 'agreedFee', label: 'Agreed fee' },
    { value: 'contentDueAt', label: 'Content due date' },
    { value: 'discountCode', label: 'Discount code' },
    { value: 'link', label: 'Affiliate link' },
    { value: 'postUrl', label: 'Post URL' },
    { value: 'customAttributes', label: 'Keep as extra detail' },
  ],
}

const ENTITY_LABELS = {
  campaign: 'The campaign',
  creator: 'The creator',
  campaign_creator: "This creator's role in a campaign",
}

function parseMappingRows(mappingText, headers) {
  try {
    const parsed = JSON.parse(mappingText || '[]')
    const items = Array.isArray(parsed) ? parsed : []
    const byHeader = new Map(items.map((item) => [item.spreadsheetColumn, item]))

    return headers.map((header) => {
      const mapped = byHeader.get(header)
      const confidence = Number(mapped?.confidence)
      return {
        spreadsheetColumn: header,
        targetEntity: mapped?.targetEntity || 'campaign',
        targetAttribute: mapped?.targetAttribute || '',
        confidence: Number.isFinite(confidence) ? confidence : null,
      }
    })
  } catch {
    return headers.map((header) => ({
      spreadsheetColumn: header,
      targetEntity: 'campaign',
      targetAttribute: '',
      confidence: null,
    }))
  }
}

/**
 * A row needs attention when the agent was unsure, or when it resolved to nothing at all.
 * Rows the user has since edited by hand count as settled — once someone has chosen, the
 * model's original uncertainty is no longer interesting.
 */
function needsAttention(row, touchedColumns) {
  if (touchedColumns.has(row.spreadsheetColumn)) {
    return false
  }
  if (!row.targetAttribute) {
    return true
  }
  return row.confidence !== null && row.confidence < MAPPING_CONFIDENCE_THRESHOLD
}

function MappingRowCard({ row, onChange, flagged }) {
  return (
    <div className={`mapping-row-card${flagged ? ' needs-attention' : ''}`}>
      <div className="mapping-row-source">
        <span className="mapping-row-label">Your column</span>
        <strong>{row.spreadsheetColumn}</strong>
        {flagged ? <span className="mapping-flag">Needs your input</span> : null}
      </div>
      <label>
        <span className="mapping-row-label">This column is about</span>
        <select
          value={row.targetEntity}
          onChange={(event) => onChange(row.spreadsheetColumn, 'targetEntity', event.target.value)}
        >
          {Object.entries(ENTITY_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span className="mapping-row-label">And holds</span>
        <select
          value={row.targetAttribute}
          onChange={(event) => onChange(row.spreadsheetColumn, 'targetAttribute', event.target.value)}
        >
          {ENTITY_ATTRIBUTE_OPTIONS[row.targetEntity].map((option) => (
            <option key={`${row.spreadsheetColumn}-${option.value || 'blank'}`} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    </div>
  )
}

function StepHeading({ index, title, state, children }) {
  return (
    <div className={`import-step import-step-${state}`}>
      <div className="import-step-marker" aria-hidden="true">
        {state === 'done' ? '✓' : index}
      </div>
      <div className="import-step-body">
        <h3>{title}</h3>
        {children}
      </div>
    </div>
  )
}

function ImportPage({
  importSummary,
  importBatches,
  importBatchHydrationStatus,
  onImportFiles,
  onSelectImportBatch,
  onDeleteImportBatch,
  onImportMappingChange,
  onSaveImportMapping,
  onRegenerateImportMapping,
  onPreviewImport,
  onHydrateImport,
  importAction,
}) {
  const isBusy = importAction !== 'idle'
  const [isDraggingFiles, setIsDraggingFiles] = useState(false)
  const [showConfident, setShowConfident] = useState(false)
  const [touchedColumns, setTouchedColumns] = useState(() => new Set())
  const fileInputRef = useRef(null)

  const mappingRows = useMemo(
    () => parseMappingRows(importSummary.mappingText || '[]', importSummary.headers || []),
    [importSummary.headers, importSummary.mappingText],
  )

  const hasJsonError = useMemo(() => {
    try {
      JSON.parse(importSummary.mappingText || '[]')
      return false
    } catch {
      return true
    }
  }, [importSummary.mappingText])

  const { flaggedRows, confidentRows } = useMemo(() => {
    const flagged = []
    const confident = []
    mappingRows.forEach((row) => {
      if (needsAttention(row, touchedColumns)) {
        flagged.push(row)
      } else {
        confident.push(row)
      }
    })
    return { flaggedRows: flagged, confidentRows: confident }
  }, [mappingRows, touchedColumns])

  const hasFile = Boolean(importSummary.batchId)
  // `rows` is only a 5-row sample for the preview table — never a count. totalRowCount is the
  // file's real size, which is what the import button must promise.
  const sampleCount = importSummary.rows?.length || 0
  const rowCount = importSummary.totalRowCount ?? sampleCount
  // Preview reports the real number of records; before it runs, the row count is the honest
  // estimate to put on the button.
  const plannedCount = importSummary.previewResult?.plannedOperationCount ?? rowCount
  const hasPreviewed = Boolean(importSummary.previewResult)
  const hasImported = Boolean(importSummary.hydrateResult)
  const mappingReady = hasFile && !hasJsonError && flaggedRows.length === 0

  const stepState = (done, active) => (done ? 'done' : active ? 'active' : 'idle')

  const updateVisualMappingRow = (header, field, value) => {
    setTouchedColumns((prev) => new Set(prev).add(header))
    const nextRows = mappingRows.map((row) => {
      if (row.spreadsheetColumn !== header) {
        return row
      }
      const nextRow = { ...row, [field]: value }
      if (
        field === 'targetEntity' &&
        !ENTITY_ATTRIBUTE_OPTIONS[value]?.some((option) => option.value === nextRow.targetAttribute)
      ) {
        nextRow.targetAttribute = ''
      }
      return nextRow
    })

    onImportMappingChange(JSON.stringify(nextRows, null, 2))
  }

  const handleSelectedFiles = (files) => {
    const items = Array.from(files || []).filter(Boolean)
    if (!items.length) {
      return
    }
    onImportFiles(items)
  }

  const resolveHydrationState = (batchId, batchStatus) => {
    if (importBatchHydrationStatus?.[batchId]?.state) {
      return importBatchHydrationStatus[batchId].state
    }
    if (batchStatus === 'hydrated') {
      return 'hydrated'
    }
    if (batchStatus === 'hydration_failed') {
      return 'failed'
    }
    return 'pending'
  }

  return (
    <article className="card mds-surface mds-prose import-card page-stack">
      <MdsKicker>Import your spreadsheet</MdsKicker>
      <p className="import-intro">
        Bring your existing creator sheet in as it is. We&rsquo;ll match the columns for you, show you what
        will happen, and only then create anything.
      </p>
      <MdsSectionRule />

      {/* ── Step 1: upload ──────────────────────────────────────────────── */}
      <StepHeading index={1} title="Choose your file" state={stepState(hasFile, !hasFile)}>
        <p>
          Drop a <MdsInlineCode>CSV</MdsInlineCode>, <MdsInlineCode>XLS</MdsInlineCode>, or{' '}
          <MdsInlineCode>XLSX</MdsInlineCode> file — the one you already keep your creators in.
        </p>

        <div
          className={`file-drop multi-file-drop${isDraggingFiles ? ' active' : ''}`}
          onDragOver={(event) => {
            event.preventDefault()
            setIsDraggingFiles(true)
          }}
          onDragLeave={(event) => {
            event.preventDefault()
            setIsDraggingFiles(false)
          }}
          onDrop={(event) => {
            event.preventDefault()
            setIsDraggingFiles(false)
            handleSelectedFiles(event.dataTransfer.files)
          }}
          onClick={() => fileInputRef.current?.click()}
        >
          <span>{isBusy ? 'Uploading…' : 'Drag a spreadsheet here, or click to browse'}</span>
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,.xls,.xlsx"
            multiple
            onChange={(event) => handleSelectedFiles(event.target.files)}
          />
        </div>

        {/* PR-02. A file to copy the shape of, for someone whose roster is in a format they are not
            sure about -- which on a first visit is everyone. Deliberately NOT a "seed my workspace
            with examples" button: seeded rows spend a free tier's 25-creator budget, are
            indistinguishable from real ones the moment they exist, and would tick the activation
            checklist's first step with a creator nobody added. A file they download, edit and own
            has none of those problems and answers the same question. */}
        <p className="helper import-sample-hint">
          Not sure what the file should look like?{' '}
          <button
            type="button"
            className="linkish-btn"
            onClick={downloadSampleImport}
          >
            Download a sample CSV
          </button>{' '}
          — the columns are the ones the importer recognises, so it maps with no corrections.
        </p>

        {importSummary.message ? <MdsNote>{importSummary.message}</MdsNote> : null}

        <section className="import-summary-list">
          <div className="mapping-visual-header">
            <span className="auth-label">Your uploaded files</span>
          </div>
          {importBatches?.length ? (
            <ul className="simple-list import-batch-list">
              {importBatches.map((batch) => {
                const hydrationState = resolveHydrationState(batch.id, batch.status)
                const status = importBatchHydrationStatus?.[batch.id]
                return (
                  <li key={batch.id} className={batch.id === importSummary.batchId ? 'active' : ''}>
                    <button type="button" className="file-name-link" onClick={() => onSelectImportBatch(batch.id)}>
                      {batch.sourceFilename || 'Unnamed file'}
                    </button>
                    <span>{batch.rowCount || 0} rows</span>
                    <span>
                      {hydrationState === 'hydrated'
                        ? 'Imported'
                        : hydrationState === 'failed'
                          ? 'Import failed'
                          : 'Not imported yet'}
                    </span>
                    {status?.state === 'hydrated' ? (
                      <span>
                        {status.createdCount || 0} added, {status.updatedCount || 0} updated
                      </span>
                    ) : null}
                    {status?.state === 'failed' ? (
                      <span className="mapping-error-text">{status.message || 'Import failed'}</span>
                    ) : null}
                    <div className="row-actions">
                      <button type="button" className="ghost-btn" onClick={() => onDeleteImportBatch(batch.id)}>
                        Remove
                      </button>
                    </div>
                  </li>
                )
              })}
            </ul>
          ) : (
            <p className="helper">No files yet — drop your creator spreadsheet above to begin.</p>
          )}
        </section>
      </StepHeading>

      {/* ── Step 2: match columns ───────────────────────────────────────── */}
      <StepHeading
        index={2}
        title="Match your columns"
        state={stepState(hasFile && mappingReady, hasFile && !mappingReady)}
      >
        {!hasFile ? (
          <p className="helper">Choose a file first and we&rsquo;ll match its columns automatically.</p>
        ) : (
          <>
            <div className="mapping-confidence-summary">
              {flaggedRows.length === 0 ? (
                <p className="mapping-confidence-line success">
                  <strong>✓ We matched all {mappingRows.length} columns.</strong> Have a quick look if you like,
                  then continue.
                </p>
              ) : (
                <p className="mapping-confidence-line">
                  <strong>
                    ✓ We matched {confidentRows.length} of {mappingRows.length} columns automatically.
                  </strong>{' '}
                  {flaggedRows.length} {flaggedRows.length === 1 ? 'needs' : 'need'} your input.
                </p>
              )}
            </div>

            {flaggedRows.length > 0 ? (
              <div className="mapping-rows mapping-rows-flagged">
                {flaggedRows.map((row) => (
                  <MappingRowCard
                    key={row.spreadsheetColumn}
                    row={row}
                    onChange={updateVisualMappingRow}
                    flagged
                  />
                ))}
              </div>
            ) : null}

            {confidentRows.length > 0 ? (
              <div className="mapping-confident-block">
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => setShowConfident((prev) => !prev)}
                  aria-expanded={showConfident}
                >
                  {showConfident
                    ? `Hide the ${confidentRows.length} we matched`
                    : `Show the ${confidentRows.length} we matched`}
                </button>
                {showConfident ? (
                  <div className="mapping-rows">
                    {confidentRows.map((row) => (
                      <MappingRowCard key={row.spreadsheetColumn} row={row} onChange={updateVisualMappingRow} />
                    ))}
                  </div>
                ) : null}
              </div>
            ) : null}

            <details className="mapping-advanced-panel">
              <summary>Edit the mapping as JSON</summary>
              <label className="import-mapping-field">
                <span className="auth-label">Column mapping JSON</span>
                {hasJsonError ? (
                  <span className="mapping-helper-text mapping-error-text">
                    This JSON isn&rsquo;t valid. Fix it here, or use the pickers above to rebuild it.
                  </span>
                ) : null}
                <textarea
                  className="mapping-editor"
                  value={importSummary.mappingText || ''}
                  onChange={(event) => onImportMappingChange(event.target.value)}
                  spellCheck="false"
                />
              </label>
            </details>

            <div className="import-action-row">
              <button type="button" className="ghost-btn" onClick={onSaveImportMapping} disabled={isBusy}>
                {importAction === 'save-mapping' ? 'Saving…' : 'Save this mapping'}
              </button>
              <button type="button" className="ghost-btn" onClick={onRegenerateImportMapping} disabled={isBusy}>
                {importAction === 'regenerate-mapping' ? 'Matching again…' : 'Match columns again'}
              </button>
            </div>
          </>
        )}
      </StepHeading>

      {/* ── Step 3: review ──────────────────────────────────────────────── */}
      <StepHeading index={3} title="Check what will happen" state={stepState(hasPreviewed, mappingReady && !hasPreviewed)}>
        {!hasFile ? (
          <p className="helper">Nothing to check yet.</p>
        ) : (
          <>
            <p>
              A dry run: nothing is created, and you&rsquo;ll see exactly how many records would be added or
              updated.
            </p>

            {importSummary.headers?.length ? (
              <div className="preview-table-wrap">
                <p className="preview-meta">
                  {importSummary.filename} — first {sampleCount} of {rowCount} rows
                </p>
                <table className="preview-table">
                  <thead>
                    <tr>
                      {importSummary.headers.map((header) => (
                        <th key={header}>{header}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {importSummary.rows.length ? (
                      importSummary.rows.slice(0, 5).map((row, index) => (
                        <tr key={`${index}-${row.join('-')}`}>
                          {importSummary.headers.map((_, cellIndex) => (
                            <td key={`${index}-${cellIndex}`}>{row[cellIndex] || '-'}</td>
                          ))}
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={Math.max(importSummary.headers.length, 1)}>
                          Row preview isn&rsquo;t available for this file.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            ) : null}

            <div className="import-action-row">
              <button
                type="button"
                className="ghost-btn"
                onClick={onPreviewImport}
                disabled={isBusy || !mappingReady}
                title={mappingReady ? undefined : 'Finish matching your columns first'}
              >
                {importAction === 'preview' ? 'Checking…' : 'Check before importing'}
              </button>
            </div>

            {importSummary.previewResult ? (
              <article className="import-result-card">
                <div className="panel-heading-row">
                  <MdsKicker>What will happen</MdsKicker>
                  <span className="status-chip info">Nothing created yet</span>
                </div>
                <strong>{importSummary.previewResult.plannedOperationCount || 0} records ready to import</strong>
                <span>{importSummary.previewResult.createdCount || 0} new</span>
                <span>{importSummary.previewResult.updatedCount || 0} updated</span>
                <span>{importSummary.previewResult.skippedCount || 0} skipped</span>
              </article>
            ) : null}
          </>
        )}
      </StepHeading>

      {/* ── Step 4: import ──────────────────────────────────────────────── */}
      <StepHeading index={4} title="Bring them in" state={stepState(hasImported, hasPreviewed && !hasImported)}>
        {!hasFile ? (
          <p className="helper">Nothing to import yet.</p>
        ) : (
          <>
            <p>This creates the records in your workspace. You can undo it by removing the file above.</p>
            <div className="import-action-row">
              <button
                type="button"
                className="primary-btn"
                onClick={onHydrateImport}
                disabled={isBusy || !mappingReady}
                title={mappingReady ? undefined : 'Finish matching your columns first'}
              >
                {importAction === 'hydrate'
                  ? 'Importing…'
                  : plannedCount
                    ? `Import ${plannedCount} ${plannedCount === 1 ? 'record' : 'records'}`
                    : 'Import records'}
              </button>
              {!hasPreviewed ? (
                <span className="mapping-helper-text">
                  Checking first is optional, but it shows you what will change.
                </span>
              ) : null}
            </div>

            {importSummary.hydrateResult ? (
              <article className="import-result-card success">
                <div className="panel-heading-row">
                  <MdsKicker>Done</MdsKicker>
                  <span className="status-chip success">Imported</span>
                </div>
                <strong>{importSummary.hydrateResult.plannedOperationCount || 0} records imported</strong>
                <span>{importSummary.hydrateResult.createdCount || 0} added</span>
                <span>{importSummary.hydrateResult.updatedCount || 0} updated</span>
                <span>{importSummary.hydrateResult.skippedCount || 0} skipped</span>
              </article>
            ) : null}
          </>
        )}
      </StepHeading>
    </article>
  )
}

export default ImportPage
