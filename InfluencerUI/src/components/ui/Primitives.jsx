function joinClassNames(...parts) {
  return parts.filter(Boolean).join(' ')
}

/**
 * A labelled form control.
 *
 * <p>Wraps the label, the control, and its error in one element so the three cannot drift apart.
 * Inputs across the app previously relied on placeholders alone as labels, which vanish the
 * moment someone types — leaving a filled form whose fields no longer say what they hold.
 */
export function Field({ label, hint, error, htmlFor, children, required }) {
  const hintId = hint && htmlFor ? `${htmlFor}-hint` : undefined
  const errorId = error && htmlFor ? `${htmlFor}-error` : undefined

  return (
    <div className={joinClassNames('field', error && 'has-error')}>
      <label className="field-label" htmlFor={htmlFor}>
        {label}
        {required ? <span className="field-required" aria-hidden="true"> *</span> : null}
      </label>
      {hint ? <p className="field-hint" id={hintId}>{hint}</p> : null}
      {children}
      {/* role="alert" so a validation failure is announced when it appears, rather than sitting
          silently below a control the user has already tabbed past. */}
      {error ? <p className="field-error" id={errorId} role="alert">{error}</p> : null}
    </div>
  )
}

/** Status pill. Encodes state in colour *and* text, so it survives greyscale and colour-blindness. */
export function Badge({ tone = 'neutral', children }) {
  return <span className={`badge badge-${tone}`}>{children}</span>
}

/**
 * What a page shows instead of an empty table.
 *
 * <p>An empty table teaches nothing. This names why the space is empty and gives the one action
 * that fills it — the pattern the Revenue page already used, generalised so every page can.
 */
export function EmptyState({ title, description, action, icon }) {
  return (
    <div className="empty-state">
      {icon ? <div className="empty-state-icon" aria-hidden="true">{icon}</div> : null}
      <h3 className="empty-state-title">{title}</h3>
      {description ? <p className="empty-state-description">{description}</p> : null}
      {action ? <div className="empty-state-action">{action}</div> : null}
    </div>
  )
}

/** Row of filter controls above a table. */
export function FilterBar({ children }) {
  return <div className="filter-bar">{children}</div>
}

/**
 * The toolbar that appears once rows are selected.
 *
 * <p>Renders nothing at zero selection, so it costs no vertical space until it has something to
 * say. `role="status"` announces the count as it changes — a keyboard user ticking rows otherwise
 * gets no feedback that the selection grew, which is the moment they most need it.
 */
export function BulkActionBar({ count, onClear, children }) {
  if (!count) {
    return null
  }

  return (
    <div className="bulk-action-bar" role="status" aria-live="polite">
      <span className="bulk-action-count">
        {count} selected
      </span>
      <div className="bulk-action-buttons">
        {children}
        <button type="button" className="ghost-btn" onClick={onClear}>
          Clear
        </button>
      </div>
    </div>
  )
}

export { joinClassNames }
