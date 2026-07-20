function ImportPage({ importSummary, onImport }) {
  return (
    <article className="card mdx-surface mdx-prose import-card page-stack">
      <p className="mdx-kicker">Import Flow</p>
      <h3>1. Import spreadsheet</h3>
      <div className="mdx-section-rule" />
      <p>
        Upload <span className="mdx-inline-code">CSV</span>, <span className="mdx-inline-code">XLS</span>, or{' '}
        <span className="mdx-inline-code">XLSX</span> and preview import columns before mapping to CRM entities.
      </p>
      <label className="file-drop">
        <span>Drop file or click to browse</span>
        <input type="file" accept=".csv,.xls,.xlsx" onChange={onImport} />
      </label>
      <p className="mdx-note">{importSummary.message}</p>
      {importSummary.filename ? (
        <div className="preview-table-wrap">
          <p className="preview-meta">
            {importSummary.filename} ({importSummary.type})
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
      ) : null}
    </article>
  )
}

export default ImportPage
