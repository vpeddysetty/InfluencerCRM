/**
 * The top of a workspace page: what this page is, how much is in it, and the one action that
 * creates more.
 *
 * <p>Pages used to open with a create form, pushing the data someone came to read below the
 * fold. Creation is rare; finding and editing is the daily loop. This header is what makes the
 * inversion possible — the primary action stays one click away without occupying a screen.
 */
function PageHeader({ title, count, description, action }) {
  return (
    <header className="page-header">
      <div className="page-header-text">
        <div className="page-header-title-row">
          <h1 className="page-header-title">{title}</h1>
          {/* Rendered only when a count exists. `count === 0` is meaningful — "0 creators" is
              information — so this tests for null/undefined rather than truthiness. */}
          {count !== undefined && count !== null ? (
            <span className="page-header-count">{count}</span>
          ) : null}
        </div>
        {description ? <p className="page-header-description">{description}</p> : null}
      </div>
      {action ? <div className="page-header-action">{action}</div> : null}
    </header>
  )
}

export default PageHeader
