/**
 * A scannable record table.
 *
 * <p>Replaces `.simple-list`, which stacked every field of a record vertically — roughly 120px
 * per row, nothing aligned between rows, so the eye could not run down a column to compare
 * platforms or spot a missing email. Columns are the entire point: alignment is what makes a
 * list of 200 creators readable rather than merely present.
 *
 * <p>Column shape: `{ key, header, render?, align?, width?, sortable? }`.
 *
 * <p>Selection is opt-in: pass `selectedKeys` and `onSelectionChange` and a checkbox column
 * appears. Bulk operations are the reason a CRM uses a table at all — "add these forty creators
 * to a campaign" is one gesture in every incumbent and was previously forty here. Kept out of the
 * page components deliberately: building it once means every table that adopts the kit inherits
 * the same keyboard handling and the same indeterminate-header semantics.
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
  selectedKeys,
  onSelectionChange,
  selectionLabel = (row) => `Select ${rowKey(row)}`,
}) {
  // Selection is on only when the caller supplies both halves. A `selectedKeys` with no change
  // handler would render checkboxes that cannot be ticked.
  const selectable = Boolean(selectedKeys && onSelectionChange)
  const selected = selectedKeys || new Set()

  if (!rows.length) {
    return emptyState || null
  }

  // Header state is derived from the *visible* rows, so "select all" under an active filter means
  // "all of these", not "all in the database" — the same rule the CSV export follows.
  const visibleKeys = rows.map((row) => rowKey(row))
  const selectedVisible = visibleKeys.filter((key) => selected.has(key))
  const allSelected = selectedVisible.length > 0 && selectedVisible.length === visibleKeys.length
  const someSelected = selectedVisible.length > 0 && !allSelected

  const toggleAll = () => {
    const next = new Set(selected)
    if (allSelected) {
      visibleKeys.forEach((key) => next.delete(key))
    } else {
      visibleKeys.forEach((key) => next.add(key))
    }
    onSelectionChange(next)
  }

  const toggleRow = (key) => {
    const next = new Set(selected)
    if (next.has(key)) {
      next.delete(key)
    } else {
      next.add(key)
    }
    onSelectionChange(next)
  }

  return (
    <div className="data-table-wrap">
      <table className="data-table">
        {caption ? <caption className="visually-hidden">{caption}</caption> : null}
        <thead>
          <tr>
            {selectable ? (
              <th scope="col" className="data-table-select">
                <input
                  type="checkbox"
                  checked={allSelected}
                  // Indeterminate is not an HTML attribute — it exists only as a DOM property, so
                  // it has to be set through a ref callback. Without it a partial selection looks
                  // identical to an empty one on the header checkbox.
                  ref={(node) => {
                    if (node) {
                      node.indeterminate = someSelected
                    }
                  }}
                  onChange={toggleAll}
                  aria-label={allSelected ? 'Clear selection' : 'Select all rows'}
                />
              </th>
            ) : null}
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
            const isSelected = selectable && selected.has(key)
            return (
              <tr
                key={key}
                className={[onRowClick ? 'is-clickable' : '', isSelected ? 'is-selected' : '']
                  .filter(Boolean)
                  .join(' ') || undefined}
                // aria-selected states the row's selection to a screen reader; the row's
                // background tint conveys it to sighted users only.
                aria-selected={selectable ? isSelected : undefined}
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
                {selectable ? (
                  // stopPropagation, not preventDefault: the click must still reach the checkbox
                  // and toggle it. Without this, ticking a box on a clickable row would also open
                  // the drawer — selecting forty rows would open forty drawers.
                  <td className="data-table-select" onClick={(event) => event.stopPropagation()}>
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => toggleRow(key)}
                      aria-label={selectionLabel(row)}
                    />
                  </td>
                ) : null}
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
