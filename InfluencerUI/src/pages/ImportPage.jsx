import { useMemo, useRef, useState } from 'react'
import { MdsInlineCode, MdsKicker, MdsNote, MdsSectionRule } from '../components/Mds'

const ENTITY_ATTRIBUTE_OPTIONS = {
  campaign: [
    { value: '', label: 'Skip column' },
    { value: 'name', label: 'Campaign name' },
    { value: 'budget', label: 'Budget' },
    { value: 'status', label: 'Status' },
    { value: 'customAttributes', label: 'Custom attributes (JSON)' },
  ],
  creator: [
    { value: '', label: 'Skip column' },
    { value: 'name', label: 'Creator name' },
    { value: 'handle', label: 'Handle' },
    { value: 'platform', label: 'Platform' },
    { value: 'email', label: 'Email' },
    { value: 'customAttributes', label: 'Custom attributes (JSON)' },
  ],
  campaign_creator: [
    { value: '', label: 'Skip column' },
    { value: 'stage', label: 'Stage' },
    { value: 'agreedFee', label: 'Agreed fee' },
    { value: 'contentDueAt', label: 'Content due at' },
    { value: 'discountCode', label: 'Discount code' },
    { value: 'link', label: 'Link' },
    { value: 'postUrl', label: 'Post URL' },
    { value: 'customAttributes', label: 'Custom attributes (JSON)' },
  ],
}

function parseMappingRows(mappingText, headers) {
  try {
    const parsed = JSON.parse(mappingText || '[]')
    const items = Array.isArray(parsed) ? parsed : []
    const byHeader = new Map(items.map((item) => [item.spreadsheetColumn, item]))

    return headers.map((header) => {
      const mapped = byHeader.get(header)
      return {
        spreadsheetColumn: header,
        targetEntity: mapped?.targetEntity || 'campaign',
        targetAttribute: mapped?.targetAttribute || '',
      }
    })
  } catch {
    return headers.map((header) => ({
      spreadsheetColumn: header,
      targetEntity: 'campaign',
      targetAttribute: '',
    }))
  }
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

  const updateVisualMappingRow = (header, field, value) => {
    const nextRows = mappingRows.map((row) => {
      if (row.spreadsheetColumn !== header) {
        return row
      }

      const nextRow = { ...row, [field]: value }
      if (field === 'targetEntity' && !ENTITY_ATTRIBUTE_OPTIONS[value]?.some((option) => option.value === nextRow.targetAttribute)) {
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

  return (
    <article className="card mds-surface mds-prose import-card page-stack">
      <MdsKicker>Import Flow</MdsKicker>
      <h3>1. Upload one or more spreadsheets</h3>
      <MdsSectionRule />
      <p>
        Drop multiple <MdsInlineCode>CSV</MdsInlineCode>, <MdsInlineCode>XLS</MdsInlineCode>, or{' '}
        <MdsInlineCode>XLSX</MdsInlineCode> files to create import batches for your brand workspace.
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
        <span>{isBusy ? 'Uploading files...' : 'Drag and drop files here or click to browse'}</span>
        <input
          ref={fileInputRef}
          type="file"
          accept=".csv,.xls,.xlsx"
          multiple
          onChange={(event) => handleSelectedFiles(event.target.files)}
        />
      </div>

      <MdsNote>{importSummary.message}</MdsNote>

      <section className="import-summary-list">
        <div className="mapping-visual-header">
          <span className="auth-label">Uploaded files (brand scoped)</span>
          <span className="mapping-helper-text">Only files from the authenticated brand workspace are shown.</span>
        </div>
        {importBatches?.length ? (
          <ul className="simple-list import-batch-list">
            {importBatches.map((batch) => (
              <li key={batch.id} className={batch.id === importSummary.batchId ? 'active' : ''}>
                <button
                  type="button"
                  className="file-name-link"
                  onClick={() => onSelectImportBatch(batch.id)}
                >
                  {batch.sourceFilename || 'Unnamed file'}
                </button>
                <span>Rows: {batch.rowCount || 0}</span>
                <span>Stored: {batch.sourceFileStored ? 'Yes' : 'No'}</span>
                <span>
                  Hydration: {
                    importBatchHydrationStatus?.[batch.id]?.state === 'hydrated'
                      ? 'Completed'
                      : importBatchHydrationStatus?.[batch.id]?.state === 'failed'
                        ? 'Failed'
                        : 'Pending'
                  }
                </span>
                {importBatchHydrationStatus?.[batch.id]?.state === 'hydrated' ? (
                  <span>
                    Created {importBatchHydrationStatus[batch.id].createdCount || 0}, Updated {importBatchHydrationStatus[batch.id].updatedCount || 0}
                  </span>
                ) : null}
                {importBatchHydrationStatus?.[batch.id]?.state === 'failed' ? (
                  <span className="mapping-error-text">{importBatchHydrationStatus[batch.id].message || 'Hydration failed'}</span>
                ) : null}
                <div className="row-actions">
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => onDeleteImportBatch(batch.id)}
                  >
                    Remove file
                  </button>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="helper">No files uploaded yet for this brand workspace.</p>
        )}
      </section>

      {importSummary.batchId ? (
        <>
          <div className="preview-table-wrap">
            <p className="preview-meta">
              {importSummary.filename} ({importSummary.type})
            </p>
            <p className="preview-meta preview-meta-secondary">
              Source file persisted: {importSummary.sourceFileStored ? 'Yes' : 'No'}
            </p>
            <p className="preview-meta preview-meta-secondary">
              Column mapping saved to batch: {importSummary.mappingSaved ? 'Yes' : 'No'}
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
                  importSummary.rows.map((row, index) => (
                    <tr key={`${index}-${row.join('-')}`}>
                      {importSummary.headers.map((_, cellIndex) => (
                        <td key={`${index}-${cellIndex}`}>{row[cellIndex] || '-'}</td>
                      ))}
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={Math.max(importSummary.headers.length, 1)}>
                      Row preview is not available for history-only selections.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="import-controls">
            <div className="mapping-visual-editor">
              <div className="mapping-visual-header">
                <span className="auth-label">Visual column mapper</span>
                <span className="mapping-helper-text">Map each spreadsheet column without editing JSON directly.</span>
              </div>

              <div className="mapping-rows">
                {mappingRows.map((row) => (
                  <div className="mapping-row-card" key={row.spreadsheetColumn}>
                    <div className="mapping-row-source">
                      <span className="mapping-row-label">Source column</span>
                      <strong>{row.spreadsheetColumn}</strong>
                    </div>
                    <label>
                      <span className="mapping-row-label">Target entity</span>
                      <select
                        value={row.targetEntity}
                        onChange={(event) => updateVisualMappingRow(row.spreadsheetColumn, 'targetEntity', event.target.value)}
                      >
                        <option value="campaign">Campaign</option>
                        <option value="creator">Creator</option>
                        <option value="campaign_creator">Campaign relationship</option>
                      </select>
                    </label>
                    <label>
                      <span className="mapping-row-label">Target field</span>
                      <select
                        value={row.targetAttribute}
                        onChange={(event) => updateVisualMappingRow(row.spreadsheetColumn, 'targetAttribute', event.target.value)}
                      >
                        {ENTITY_ATTRIBUTE_OPTIONS[row.targetEntity].map((option) => (
                          <option key={`${row.spreadsheetColumn}-${option.value || 'blank'}`} value={option.value}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                ))}
              </div>
            </div>

            <details className="mapping-advanced-panel">
              <summary>Advanced JSON mapping editor</summary>
              <label className="import-mapping-field">
                <span className="auth-label">Column mapping JSON</span>
                {hasJsonError ? <span className="mapping-helper-text mapping-error-text">Current JSON is invalid. Fix it or use the visual mapper to regenerate valid mapping rows.</span> : null}
                <textarea
                  className="mapping-editor"
                  value={importSummary.mappingText || ''}
                  onChange={(event) => onImportMappingChange(event.target.value)}
                  spellCheck="false"
                />
              </label>
            </details>

            <div className="import-action-row">
              <button type="button" className="ghost-btn" onClick={onSaveImportMapping} disabled={isBusy || !importSummary.batchId}>
                {importAction === 'save-mapping' ? 'Saving mapping...' : 'Save mapping'}
              </button>
              <button type="button" className="ghost-btn" onClick={onRegenerateImportMapping} disabled={isBusy || !importSummary.batchId}>
                {importAction === 'regenerate-mapping' ? 'Regenerating...' : 'Regenerate mapping'}
              </button>
              <button type="button" className="ghost-btn" onClick={onPreviewImport} disabled={isBusy || !importSummary.batchId}>
                {importAction === 'preview' ? 'Running preview...' : 'Run preview'}
              </button>
              <button type="button" className="primary-btn" onClick={onHydrateImport} disabled={isBusy || !importSummary.batchId}>
                {importAction === 'hydrate' ? 'Hydrating...' : 'Hydrate records'}
              </button>
            </div>

            <div className="import-result-grid">
              {importSummary.previewResult ? (
                <article className="import-result-card">
                  <div className="panel-heading-row">
                    <MdsKicker>Preview</MdsKicker>
                    <span className="status-chip info">eye Dry run</span>
                  </div>
                  <strong>{importSummary.previewResult.plannedOperationCount || 0} planned ops</strong>
                  <span>Creates: {importSummary.previewResult.createdCount || 0}</span>
                  <span>Updates: {importSummary.previewResult.updatedCount || 0}</span>
                  <span>Skipped: {importSummary.previewResult.skippedCount || 0}</span>
                </article>
              ) : null}

              {importSummary.hydrateResult ? (
                <article className="import-result-card success">
                  <div className="panel-heading-row">
                    <MdsKicker>Hydration</MdsKicker>
                    <span className="status-chip success">check Persisted</span>
                  </div>
                  <strong>{importSummary.hydrateResult.plannedOperationCount || 0} processed ops</strong>
                  <span>Created: {importSummary.hydrateResult.createdCount || 0}</span>
                  <span>Updated: {importSummary.hydrateResult.updatedCount || 0}</span>
                  <span>Skipped: {importSummary.hydrateResult.skippedCount || 0}</span>
                </article>
              ) : null}
            </div>
          </div>
        </>
      ) : null}
    </article>
  )
}

export default ImportPage
