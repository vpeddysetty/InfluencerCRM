import { useEffect, useState } from 'react'
import { MdsKicker, MdsNote } from '../components/Mds'

const EMPTY = { totals: {}, brands: [] }

function money(value) {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return value
  return `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

/**
 * One view across every client an agency manages (roadmap PR-64).
 *
 * The gap this closes: every other screen answers "how is THIS brand doing?", because every read
 * endpoint is brand-scoped. An agency holding eight clients saw eight workspaces and switched
 * between them one at a time. This is the one screen that answers "how is my agency doing?".
 *
 * The row order is the server's, which is the same order the brand switcher uses. Sorting is
 * client-side and additive: the default view must match what an agency already recognises.
 */
function PortfolioPage({ onLoadPortfolio }) {
  const [data, setData] = useState(EMPTY)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [sort, setSort] = useState({ key: '', dir: 'desc' })

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      setLoading(true)
      setError('')
      try {
        const payload = await onLoadPortfolio()
        if (cancelled) return
        setData({
          totals: payload?.totals || {},
          brands: Array.isArray(payload?.brands) ? payload.brands : [],
        })
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load the portfolio.')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [onLoadPortfolio])

  // Unavailable brands sort to the BOTTOM whichever direction is chosen. They carry no figure, and
  // letting a missing value sort as zero would put a client we could not read at the head of an
  // ascending list as though it had sold nothing — the same false claim the server refuses to make.
  const rows = [...data.brands]
  if (sort.key) {
    rows.sort((a, b) => {
      if (a.available !== b.available) return a.available ? -1 : 1
      const av = Number(a[sort.key]) || 0
      const bv = Number(b[sort.key]) || 0
      return sort.dir === 'asc' ? av - bv : bv - av
    })
  }

  const toggleSort = (key) =>
    setSort((s) => ({ key, dir: s.key === key && s.dir === 'desc' ? 'asc' : 'desc' }))

  const totals = data.totals
  const unavailable = Number(totals.brandsUnavailable) || 0

  return (
    <article className="card mds-surface mds-prose form-card page-stack">
      <MdsKicker>Portfolio</MdsKicker>
      <h3>Every client, on one screen</h3>

      {loading && <MdsNote>Loading your portfolio…</MdsNote>}
      {error && <MdsNote className="mds-note-error">{error}</MdsNote>}

      {!loading && !error && data.brands.length === 0 && (
        <MdsNote>
          No client workspaces yet. Brands you are given access to appear here alongside their
          revenue.
        </MdsNote>
      )}

      {!loading && !error && data.brands.length > 0 && (
        <>
          <div className="dash-kpis">
            <div className="dash-kpi">
              <span className="dash-kpi-label">Clients</span>
              <strong className="dash-kpi-value">{totals.brands ?? 0}</strong>
            </div>
            <div className="dash-kpi">
              <span className="dash-kpi-label">Attributed revenue</span>
              <strong className="dash-kpi-value">{money(totals.revenue)}</strong>
            </div>
            <div className="dash-kpi">
              <span className="dash-kpi-label">Orders</span>
              <strong className="dash-kpi-value">{totals.orders ?? 0}</strong>
            </div>
            <div className="dash-kpi">
              <span className="dash-kpi-label">Creator cost</span>
              <strong className="dash-kpi-value">{money(totals.influencerCost)}</strong>
            </div>
            <div className="dash-kpi">
              <span className="dash-kpi-label">ROI</span>
              {/* Null when nothing was spent, and shown as a dash rather than as 0x or ∞ —
                  either of those reads as a result, and there is no result to report. */}
              <strong className="dash-kpi-value">{totals.roi ? `${totals.roi}x` : '—'}</strong>
            </div>
          </div>

          {unavailable > 0 && (
            <MdsNote className="mds-note-warn">
              {unavailable === 1
                ? "1 client's figures could not be read, so it is not counted in the totals above."
                : `${unavailable} clients' figures could not be read, so they are not counted in the totals above.`}
            </MdsNote>
          )}

          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">Client</th>
                  <th scope="col">
                    <button type="button" className="dash-sort" onClick={() => toggleSort('revenue')}>
                      Revenue
                    </button>
                  </th>
                  <th scope="col">
                    <button type="button" className="dash-sort" onClick={() => toggleSort('orders')}>
                      Orders
                    </button>
                  </th>
                  <th scope="col">
                    <button type="button" className="dash-sort" onClick={() => toggleSort('creators')}>
                      Creators
                    </button>
                  </th>
                  <th scope="col">Commission</th>
                  <th scope="col">Creator cost</th>
                  <th scope="col">ROI</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.brandId}>
                    <th scope="row">{row.brandName}</th>
                    {row.available ? (
                      <>
                        <td>{money(row.revenue)}</td>
                        <td>{row.orders ?? 0}</td>
                        <td>{row.creators ?? 0}</td>
                        <td>{money(row.commission)}</td>
                        <td>{money(row.influencerCost)}</td>
                        <td>{row.roi ? `${row.roi}x` : '—'}</td>
                      </>
                    ) : (
                      /* One cell saying so, rather than six dashes that read as six zeroes. */
                      <td colSpan={6} className="dash-unavailable">
                        Figures unavailable — try again shortly.
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </article>
  )
}

export default PortfolioPage
