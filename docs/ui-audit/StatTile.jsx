import { joinClassNames } from './Primitives'

/**
 * A single KPI.
 *
 * <p>Proposed for components/ui/. Replaces the inline tile markup in DashboardPage
 * (`<div className="kpi-tile"><span className="kpi-label">…`), which is repeated six
 * times per page against CSS that hardcodes #64748b and #0f172a — so the tiles do not
 * follow the dark theme.
 *
 * <p>Adds the thing the current tile has no room for: a delta. "Revenue $48,210" is a
 * fact; "Revenue $48,210, up 12% on last month" is the reason someone opened the page.
 * Direction is carried by an arrow glyph as well as colour, so it survives greyscale
 * and colour-blindness — the same rule Badge already follows.
 */
function StatTile({ label, value, delta, deltaLabel, loading = false, hint }) {
  // Direction is derived from the sign so callers pass a number, not a mood.
  const direction = delta == null ? null : delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat'
  const arrow = { up: '↑', down: '↓', flat: '→' }[direction]

  return (
    <div className="stat-tile" data-loading={loading ? 'true' : undefined}>
      <span className="stat-label">{label}</span>

      {/* aria-live so a refresh announces the new figure to a screen reader rather
          than silently swapping it. "polite" — a KPI update should wait its turn. */}
      <strong className="stat-value" aria-live="polite">
        {loading ? '—' : value}
      </strong>

      {direction && !loading ? (
        <span className={joinClassNames('stat-delta', `stat-delta-${direction}`)}>
          <span aria-hidden="true">{arrow}</span>
          {/* The visible text is the bare percentage; the accessible name spells out
              the direction, because "↑ 12%" alone reads as "12%" and loses the point. */}
          <span className="visually-hidden">
            {direction === 'up' ? 'Up' : direction === 'down' ? 'Down' : 'Unchanged'}
          </span>
          {Math.abs(delta)}%
          {deltaLabel ? <span className="stat-delta-period"> {deltaLabel}</span> : null}
        </span>
      ) : null}

      {hint ? <span className="stat-hint">{hint}</span> : null}
    </div>
  )
}

/**
 * The row of KPIs at the top of a page.
 *
 * <p>A plain grid wrapper, but it carries the one decision worth centralising: the
 * tiles are a `<dl>`-shaped list of label/value pairs, so `role="group"` plus a label
 * gives the whole row a name in the accessibility tree instead of six orphan numbers.
 */
function StatGrid({ label = 'Key metrics', children }) {
  return (
    <div className="stat-grid" role="group" aria-label={label}>
      {children}
    </div>
  )
}

export { StatTile, StatGrid }
