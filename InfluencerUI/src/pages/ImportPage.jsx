import { useMemo } from 'react'
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
  onImport,
  onImportMappingChange,
  onSaveImportMapping,
  onRegenerateImportMapping,
  onPreviewImport,
  onHydrateImport,
  importAction,
}) {
  const isBusy = importAction !== 'idle'
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

  return (
    <article className="card mds-surface mds-prose import-card page-stack">
      <MdsKicker>Import Flow</MdsKicker>
      <h3>1. Import spreadsheet</h3>
      <MdsSectionRule />
      <p>
        Upload <MdsInlineCode>CSV</MdsInlineCode>, <MdsInlineCode>XLS</MdsInlineCode>, or{' '}
        <MdsInlineCode>XLSX</MdsInlineCode> and preview import columns before mapping to CRM entities.
      </p>
      <label className="file-drop">
        <span>Drop file or click to browse</span>
        <input type="file" accept=".csv,.xls,.xlsx" onChange={onImport} />
      </label>
      <MdsNote>{importSummary.message}</MdsNote>
      {importSummary.filename ? (
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
                {importSummary.rows.map((row, index) => (
                  <tr key={`${index}-${row.join('-')}`}>
                    {importSummary.headers.map((_, cellIndex) => (
                      <td key={`${index}-${cellIndex}`}>{row[cellIndex] || '-'}</td>
                    ))}
                  </tr>
                ))}
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

            {importSummary.diagnostics ? (
              <section className="import-diagnostics-panel">
                <div className="mapping-visual-header">
                  <span className="auth-label">Import diagnostics</span>
                  <span className="mapping-helper-text">Agent mapping, preview, and hydrate telemetry for this batch.</span>
                </div>

                <div className="import-diagnostics-grid">
                  <article className="import-diagnostic-card">
                    <div className="panel-heading-row">
                      <MdsKicker>Batch</MdsKicker>
                      <span className="diag-icon-badge">tray</span>
                    </div>
                    <div className="status-chip-row">
                      <span className={`status-chip ${importSummary.sourceFileStored ? 'success' : 'warning'}`}>
                        {importSummary.sourceFileStored ? 'check File stored' : 'alert File missing'}
                      </span>
                      <span className={`status-chip ${importSummary.mappingSaved ? 'success' : 'warning'}`}>
                        {importSummary.mappingSaved ? 'check Mapping saved' : 'edit Save needed'}
                      </span>
                    </div>
                    <span>Batch ID: {importSummary.diagnostics.batchId || 'n/a'}</span>
                    <span>Headers: {importSummary.diagnostics.headerCount || 0}</span>
                    <span>Rows prepared: {importSummary.diagnostics.rowPayloadCount || 0}</span>
                    <span>Last action: {importSummary.diagnostics.lastAction || 'n/a'}</span>
                  </article>

                  <article className="import-diagnostic-card">
                    <div className="panel-heading-row">
                      <MdsKicker>Mapping</MdsKicker>
                      <span className="diag-icon-badge">map</span>
                    </div>
                    <div className="status-chip-row">
                      <span className={`status-chip ${importSummary.diagnostics.mappingMode === 'agent_assisted' ? 'success' : 'warning'}`}>
                        {importSummary.diagnostics.mappingMode === 'agent_assisted' ? 'spark Agent assisted' : 'tool Local fallback'}
                      </span>
                    </div>
                    <span>Mode: {importSummary.diagnostics.mappingMode || 'n/a'}</span>
                    <span>Recommendations: {importSummary.diagnostics.recommendationCount || 0}</span>
                    <span>Agent available: {importSummary.diagnostics.agentDebug?.llm_available === undefined ? 'n/a' : importSummary.diagnostics.agentDebug.llm_available ? 'Yes' : 'No'}</span>
                    <span>Retrieval available: {importSummary.diagnostics.agentDebug?.retrieval_available === undefined ? 'n/a' : importSummary.diagnostics.agentDebug.retrieval_available ? 'Yes' : 'No'}</span>
                    {importSummary.diagnostics.agentError ? <span className="mapping-error-text">{importSummary.diagnostics.agentError}</span> : null}
                  </article>

                  <article className="import-diagnostic-card">
                    <div className="panel-heading-row">
                      <MdsKicker>Agent debug</MdsKicker>
                      <span className="diag-icon-badge">wave</span>
                    </div>
                    <div className="status-chip-row">
                      <span className={`status-chip ${importSummary.diagnostics.agentDebug?.llm_enhanced ? 'success' : 'neutral'}`}>
                        {importSummary.diagnostics.agentDebug?.llm_enhanced ? 'spark LLM enhanced' : 'dot Heuristic only'}
                      </span>
                      <span className={`status-chip ${importSummary.diagnostics.agentDebug?.fallback_used ? 'warning' : 'success'}`}>
                        {importSummary.diagnostics.agentDebug?.fallback_used ? 'alert Fallback used' : 'check No fallback'}
                      </span>
                    </div>
                    <span>LLM enhanced: {importSummary.diagnostics.agentDebug?.llm_enhanced === undefined ? 'n/a' : importSummary.diagnostics.agentDebug.llm_enhanced ? 'Yes' : 'No'}</span>
                    <span>Fallback used: {importSummary.diagnostics.agentDebug?.fallback_used === undefined ? 'n/a' : importSummary.diagnostics.agentDebug.fallback_used ? 'Yes' : 'No'}</span>
                    <span>Retrieved examples: {importSummary.diagnostics.agentDebug?.retrieved_examples_count ?? 0}</span>
                    <span>Review candidates: {importSummary.diagnostics.agentDebug?.review_candidates?.length ?? 0}</span>
                  </article>

                  <article className="import-diagnostic-card">
                    <div className="panel-heading-row">
                      <MdsKicker>Execution</MdsKicker>
                      <span className="diag-icon-badge">bolt</span>
                    </div>
                    <div className="status-chip-row">
                      <span className="status-chip info">eye Preview</span>
                      <span className="status-chip success">check Hydrate</span>
                    </div>
                    <span>Preview planned ops: {importSummary.diagnostics.previewPlannedOperationCount ?? 0}</span>
                    <span>Preview skipped: {importSummary.diagnostics.previewSkippedCount ?? 0}</span>
                    <span>Hydrate created: {importSummary.diagnostics.hydrateCreatedCount ?? 0}</span>
                    <span>Hydrate updated: {importSummary.diagnostics.hydrateUpdatedCount ?? 0}</span>
                  </article>
                </div>
              </section>
            ) : null}
          </div>
        </>
      ) : null}
    </article>
  )
}

export default ImportPage
