/**
 * A scannable record table.
 *
 * <p>Replaces `.simple-list`, which stacked every field of a record vertically — roughly 120px
 * per row, nothing aligned between rows, so the eye could not run down a column to compare
 * platforms or spot a missing email. Columns are the entire point: alignment is what makes a
 * list of 200 creators readable rather than merely present.
 *
 * <p>Column shape: `{ key, header, render?, align?, width?, sortable? }`.
 */
function DataTable({
  columns,
  rows,
  rowKey,
  onRowClick,
  sortBy,
  sortDir = 'asc',
  onSort,
  emptyState,
  caption,
}) {
  if (!rows.length) {
    return emptyState || null
  }

  return (
    <div className="data-table-wrap">
      <table className="data-table">
        {caption ? <caption className="visually-hidden">{caption}</caption> : null}
        <thead>
          <tr>
            {columns.map((column) => {
              const isSorted = sortBy === column.key
              if (!column.sortable || !onSort) {
                return (
                  <th key={column.key} scope="col" style={column.width ? { width: column.width } : undefined}>
                    {column.header}
                  </th>
                )
              }
              return (
                <th
                  key={column.key}
                  scope="col"
                  // aria-sort on the header is what tells a screen reader the table is sorted
                  // and by which column; the arrow glyph alone conveys that to sighted users only.
                  aria-sort={isSorted ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
                  style={column.width ? { width: column.width } : undefined}
                >
                  <button type="button" className="data-table-sort" onClick={() => onSort(column.key)}>
                    {column.header}
                    <span className="data-table-sort-icon" aria-hidden="true">
                      {isSorted ? (sortDir === 'asc' ? '↑' : '↓') : '↕'}
                    </span>
                  </button>
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = rowKey(row)
            return (
              <tr
                key={key}
                className={onRowClick ? 'is-clickable' : undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                // A clickable row must be reachable and activatable from the keyboard. Without
                // these three attributes the row is a mouse-only target, which is how the old
                // list worked — only the small "Edit" button could be reached, and the other
                // 95% of the row was dead space for everyone.
                tabIndex={onRowClick ? 0 : undefined}
                role={onRowClick ? 'button' : undefined}
                onKeyDown={onRowClick ? (event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    onRowClick(row)
                  }
                } : undefined}
              >
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={column.align === 'right' ? 'is-right' : undefined}
                  >
                    {column.render ? column.render(row) : row[column.key]}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export default DataTable
