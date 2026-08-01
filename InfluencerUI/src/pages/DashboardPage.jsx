import { useEffect, useMemo, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from '../components/Mds'

const EMPTY = { kpis: {}, leaderboard: [], channels: [] }

function money(value) {
  const n = Number(value)
  if (Number.isNaN(n)) return value ?? '—'
  return `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function DashboardPage({ coupons = [], onLoadRevenue, onSimulateOrder }) {
  const [data, setData] = useState(EMPTY)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  // Simulate-order tool (drives the attribution pipeline for demos / testing).
  const [sim, setSim] = useState({ code: '', orderId: '', saleAmount: '', discountAmount: '', status: 'purchase' })
  const [simBusy, setSimBusy] = useState(false)
  const [simFeedback, setSimFeedback] = useState({ type: '', message: '' })

  const refresh = async () => {
    setLoading(true)
    setError('')
    try {
      const payload = await onLoadRevenue()
      setData({
        kpis: payload?.kpis || {},
        leaderboard: Array.isArray(payload?.leaderboard) ? payload.leaderboard : [],
        channels: Array.isArray(payload?.channels) ? payload.channels : [],
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to load analytics.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const maxRevenue = useMemo(
    () => data.leaderboard.reduce((m, r) => Math.max(m, Number(r.revenue) || 0), 0),
    [data.leaderboard],
  )

  const kpis = data.kpis || {}

  const submitSim = async (event) => {
    event.preventDefault()
    if (!sim.code.trim() || !sim.orderId.trim()) {
      setSimFeedback({ type: 'error', message: 'Coupon code and order id are required.' })
      return
    }
    setSimBusy(true)
    setSimFeedback({ type: '', message: '' })
    try {
      const order = {
        code: sim.code.trim(),
        orderId: sim.orderId.trim(),
        status: sim.status,
      }
      if (sim.saleAmount.trim()) order.saleAmount = sim.saleAmount.trim()
      if (sim.discountAmount.trim()) order.discountAmount = sim.discountAmount.trim()
      const result = await onSimulateOrder({ providerKey: 'mock', order })
      setSimFeedback({ type: 'success', message: `Outcome: ${result.outcome}${result.commissionAmount ? ` — commission ${money(result.commissionAmount)}` : ''}.` })
      await refresh()
    } catch (err) {
      setSimFeedback({ type: 'error', message: err instanceof Error ? err.message : 'Simulation failed.' })
    } finally {
      setSimBusy(false)
    }
  }

  return (
    <article className="card mds-surface mds-prose form-card page-stack">
      <MdsKicker>Revenue Dashboard</MdsKicker>
      <h3>How each influencer drives sales</h3>
      <MdsSectionRule />

      <div className="row-actions" style={{ justifyContent: 'space-between' }}>
        <p style={{ margin: 0 }}>Attributed revenue, orders, commission, and ROI per influencer and channel.</p>
        <button type="button" className="ghost-btn" onClick={refresh} disabled={loading}>
          {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      {error ? <p className="row-save-feedback error">{error}</p> : null}

      {/* KPI tiles */}
      <div className="kpi-grid">
        <div className="kpi-tile"><span className="kpi-label">Revenue</span><strong className="kpi-value">{money(kpis.revenue)}</strong></div>
        <div className="kpi-tile"><span className="kpi-label">Orders</span><strong className="kpi-value">{kpis.orders ?? 0}</strong></div>
        <div className="kpi-tile"><span className="kpi-label">Avg order value</span><strong className="kpi-value">{money(kpis.avgOrderValue)}</strong></div>
        <div className="kpi-tile"><span className="kpi-label">Commission owed</span><strong className="kpi-value">{money(kpis.commission)}</strong></div>
        <div className="kpi-tile"><span className="kpi-label">Total cost</span><strong className="kpi-value">{money(kpis.totalInfluencerCost)}</strong></div>
        <div className="kpi-tile"><span className="kpi-label">ROI</span><strong className="kpi-value">{kpis.roi ?? '—'}×</strong></div>
      </div>

      <MdsSectionRule />
      <h4>Influencer leaderboard</h4>
      {data.leaderboard.length === 0 ? (
        <p className="custom-attributes-empty">No attributed sales yet. Simulate an order below to see data.</p>
      ) : (
        <div className="dash-table-wrap">
          <table className="dash-table">
            <thead>
              <tr>
                <th>Influencer</th><th>Orders</th><th>Revenue</th><th>AOV</th>
                <th>Commission</th><th>Cost</th><th>ROI</th><th>Revenue share</th>
              </tr>
            </thead>
            <tbody>
              {data.leaderboard
                .slice()
                .sort((a, b) => (Number(b.revenue) || 0) - (Number(a.revenue) || 0))
                .map((row) => (
                  <tr key={row.creatorId}>
                    <td>{row.creatorName}</td>
                    <td>{row.orders}</td>
                    <td>{money(row.revenue)}</td>
                    <td>{money(row.avgOrderValue)}</td>
                    <td>{money(row.commission)}</td>
                    <td>{money(row.cost)}</td>
                    <td>{row.roi}{row.roi === '∞' ? '' : '×'}</td>
                    <td>
                      <span className="dash-bar" style={{ width: `${maxRevenue ? Math.round((Number(row.revenue) / maxRevenue) * 100) : 0}%` }} />
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}

      <MdsSectionRule />
      <h4>Channel breakdown</h4>
      {data.channels.length === 0 ? (
        <p className="custom-attributes-empty">No channel data yet.</p>
      ) : (
        <ul className="simple-list">
          {data.channels.map((ch) => (
            <li key={ch.channel}>
              <strong>#{ch.channel}</strong>
              <span>{ch.orders} orders</span>
              <span>{money(ch.revenue)} revenue</span>
              <span>{money(ch.commission)} commission</span>
            </li>
          ))}
        </ul>
      )}

      <MdsSectionRule />
      <h4>Simulate an order (test / demo)</h4>
      <MdsNote>Feeds a synthetic order through the Mock marketplace attribution pipeline. Use a real coupon code from the Coupons page.</MdsNote>
      {simFeedback.message ? (
        <p className={`row-save-feedback ${simFeedback.type === 'error' ? 'error' : 'success'}`}>{simFeedback.message}</p>
      ) : null}
      <form onSubmit={submitSim} className="inline-form page-form-grid">
        <select value={sim.code} onChange={(e) => setSim((p) => ({ ...p, code: e.target.value }))} required>
          <option value="">Select coupon code…</option>
          {coupons.map((c) => <option key={c.id} value={c.code}>{c.code}</option>)}
        </select>
        <input type="text" value={sim.orderId} placeholder="Order id e.g. ORD-1001" onChange={(e) => setSim((p) => ({ ...p, orderId: e.target.value }))} required />
        <input type="number" value={sim.saleAmount} placeholder="Sale amount" onChange={(e) => setSim((p) => ({ ...p, saleAmount: e.target.value }))} />
        <input type="number" value={sim.discountAmount} placeholder="Discount amount" onChange={(e) => setSim((p) => ({ ...p, discountAmount: e.target.value }))} />
        <select value={sim.status} onChange={(e) => setSim((p) => ({ ...p, status: e.target.value }))}>
          <option value="purchase">Purchase</option>
          <option value="refunded">Refund</option>
        </select>
        <button type="submit" className="primary-btn" disabled={simBusy}>{simBusy ? 'Sending…' : 'Simulate order'}</button>
      </form>
    </article>
  )
}

export default DashboardPage
