import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { MdsNote } from '../components/Mds'
import { exportCsv } from '../api/csv'
import { DataTable, EmptyState, FilterBar, PageHeader } from '../components/ui'
import { DEFAULT_RANGE, RANGE_PRESETS, rangeLabel as labelForRange, rangeToParams } from '../shell/dateRange'
import { activationState, shouldShowActivation } from '../shell/activation'
import ActivationChecklist from '@influencer/ui/ActivationChecklist.jsx'

const EMPTY = { kpis: {}, leaderboard: [], channels: [] }

// The order simulator writes synthetic attribution into real reporting, so it is off unless
// explicitly switched on. Leaving it always-visible on the page whose whole job is proving
// real ROI invites exactly the data a brand owner would later have to reconcile.
const SIMULATOR_ENABLED = import.meta.env?.VITE_ENABLE_ORDER_SIMULATOR === 'true'

function money(value) {
  const n = Number(value)
  if (Number.isNaN(n)) return value ?? '—'
  return `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function DashboardPage({ coupons = [], creators = [], campaigns = [], pages = [], stores = [], onLoadRevenue, onSimulateOrder, onGoTo }) {
  const [data, setData] = useState(EMPTY)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [range, setRange] = useState(DEFAULT_RANGE)

  // Simulate-order tool (drives the attribution pipeline for demos / testing).
  const [sim, setSim] = useState({ code: '', orderId: '', saleAmount: '', discountAmount: '', status: 'purchase' })
  const [simBusy, setSimBusy] = useState(false)
  const [simFeedback, setSimFeedback] = useState({ type: '', message: '' })

  // Depends on `range` so switching the window refetches. Server-side filtering rather than
  // client-side slicing because the payload is pre-aggregated rollups — the per-order rows the
  // UI would need to filter itself never reach the browser.
  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const payload = await onLoadRevenue(rangeToParams(range))
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
  }, [onLoadRevenue, range])

  useEffect(() => {
    refresh()
  }, [refresh])

  const rankedLeaderboard = useMemo(
    () => [...data.leaderboard].sort((a, b) => (Number(b.revenue) || 0) - (Number(a.revenue) || 0)),
    [data.leaderboard],
  )

  const maxRevenue = useMemo(
    () => data.leaderboard.reduce((m, r) => Math.max(m, Number(r.revenue) || 0), 0),
    [data.leaderboard],
  )

  const kpis = data.kpis || {}

  // "Has anything been attributed?" — any order, any revenue, or any leaderboard row. Checking
  // all three avoids hiding real data when one field lags behind the others.
  const hasAttribution =
    data.leaderboard.length > 0 || Number(kpis.orders) > 0 || Number(kpis.revenue) > 0

  // PR-02. Derived, never stored: a persisted checklist can disagree with reality — ticked while
  // the creator it refers to was deleted — and then it is wrong on the first screen a new user
  // trusts. Recomputing costs nothing and cannot drift. `hasAttribution` doubles as the "this
  // workspace is past setup" signal, so a brand with real sales is never told to connect a store
  // it evidently connected.
  const activation = useMemo(
    () => activationState({ creators, campaigns, coupons, pages, stores }),
    [creators, campaigns, coupons, pages, stores],
  )
  const showActivation = shouldShowActivation(activation, { hasRevenue: hasAttribution })

  const rangeLabel = labelForRange(range)
  const isRanged = range !== 'all'

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

  const exportLeaderboard = () => {
    exportCsv({
      // The filename carries the window, so two exports taken a month apart are not both
      // "attribution.csv" with silently different meanings.
      prefix: isRanged ? `attribution-${range}` : 'attribution',
      source: 'attribution-leaderboard',
      // Raw numbers, not the formatted strings the table renders. A CSV consumer wants to sum
      // and sort the column; `$1,234.00` and `10.0×` are text and would break both.
      columns: [
        { key: 'creatorName', header: 'Creator' },
        { key: 'orders', header: 'Orders' },
        { key: 'revenue', header: 'Revenue' },
        { key: 'avgOrderValue', header: 'Avg order value' },
        { key: 'commission', header: 'Commission' },
        { key: 'cost', header: 'Cost' },
        { key: 'roi', header: 'ROI' },
      ],
      rows: rankedLeaderboard,
    })
  }

  // Right-aligned numerics so a column of currency reads down the decimal. The revenue-share bar
  // stays left so the bars share a common origin — right-aligning them would make length
  // meaningless.
  const leaderboardColumns = [
    { key: 'creatorName', header: 'Influencer', sortable: false },
    { key: 'orders', header: 'Orders', align: 'right' },
    { key: 'revenue', header: 'Revenue', align: 'right', render: (row) => money(row.revenue) },
    { key: 'avgOrderValue', header: 'AOV', align: 'right', render: (row) => money(row.avgOrderValue) },
    { key: 'commission', header: 'Commission', align: 'right', render: (row) => money(row.commission) },
    { key: 'cost', header: 'Cost', align: 'right', render: (row) => money(row.cost) },
    {
      key: 'roi',
      header: 'ROI',
      align: 'right',
      render: (row) => `${row.roi}${row.roi === '∞' ? '' : '×'}`,
    },
    {
      key: 'share',
      header: 'Revenue share',
      width: '18%',
      // aria-hidden because the revenue figure already states the value — announcing it twice
      // adds noise, not information.
      render: (row) => (
        <span className="dash-bar-track" aria-hidden="true">
          <span
            className="dash-bar"
            style={{ width: `${maxRevenue ? Math.round((Number(row.revenue) / maxRevenue) * 100) : 0}%` }}
          />
        </span>
      ),
    },
  ]

  const channelColumns = [
    { key: 'channel', header: 'Channel', render: (row) => `#${row.channel}` },
    { key: 'orders', header: 'Orders', align: 'right' },
    { key: 'revenue', header: 'Revenue', align: 'right', render: (row) => money(row.revenue) },
    { key: 'commission', header: 'Commission', align: 'right', render: (row) => money(row.commission) },
  ]

  return (
    <>
      <PageHeader
        title="Revenue"
        description="Attributed revenue, orders, commission, and ROI per influencer and channel."
        action={
          <>
            <button
              type="button"
              className="ghost-btn"
              onClick={exportLeaderboard}
              disabled={rankedLeaderboard.length === 0}
            >
              Export CSV
            </button>
            <button type="button" className="ghost-btn" onClick={refresh} disabled={loading}>
              {loading ? 'Loading…' : 'Refresh'}
            </button>
          </>
        }
      />

      <FilterBar>
        <label className="range-picker">
          <span className="visually-hidden">Date range</span>
          <select
            value={range}
            onChange={(event) => setRange(event.target.value)}
            aria-label="Date range"
            disabled={loading}
          >
            {RANGE_PRESETS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        {/* Names the window beside the numbers. Every figure below is scoped to it, and a KPI
            with no stated period is the single easiest dashboard number to misread. */}
        <span className="filter-bar-caption">Showing {rangeLabel.toLowerCase()}</span>
      </FilterBar>

      {error ? <p className="row-save-feedback error">{error}</p> : null}

      {/* Above the KPIs deliberately: for an empty workspace the tiles are all zeroes, and the
          first thing on the page should be the way out of that rather than a wall of nothing. It
          renders null once setup is done, so an established brand never sees it. */}
      {showActivation ? <ActivationChecklist state={activation} onGo={onGoTo} /> : null}

      {hasAttribution ? (
        <div className="kpi-grid">
          <div className="kpi-tile"><span className="kpi-label">Revenue</span><strong className="kpi-value">{money(kpis.revenue)}</strong></div>
          <div className="kpi-tile"><span className="kpi-label">Orders</span><strong className="kpi-value">{kpis.orders ?? 0}</strong></div>
          <div className="kpi-tile"><span className="kpi-label">Avg order value</span><strong className="kpi-value">{money(kpis.avgOrderValue)}</strong></div>
          <div className="kpi-tile"><span className="kpi-label">Commission owed</span><strong className="kpi-value">{money(kpis.commission)}</strong></div>
          <div className="kpi-tile"><span className="kpi-label">Total cost</span><strong className="kpi-value">{money(kpis.totalInfluencerCost)}</strong></div>
          <div className="kpi-tile"><span className="kpi-label">ROI</span><strong className="kpi-value">{kpis.roi ?? '—'}×</strong></div>
        </div>
      ) : null}

      {/* Two different emptinesses. "Nothing has ever been attributed" is a setup problem and
          gets the setup path; "nothing in this window" is a filter problem and gets a way to
          widen it. Showing the connect-your-store panel to someone with a year of data who
          picked last week would be wrong. */}
      {!hasAttribution && !loading ? (
        isRanged ? (
          <EmptyState
            icon="◔"
            title={`No sales attributed in the ${rangeLabel.toLowerCase()}`}
            description="Nothing landed in this window. Widen the range to see whether earlier campaigns drove sales."
            action={
              <button type="button" className="primary-btn" onClick={() => setRange('all')}>
                Show all time
              </button>
            }
          />
        ) : (
          <section className="revenue-setup-panel">
            <h4>No sales attributed yet</h4>
            <p>
              Connect your store and give each creator a discount code — sales then land against the
              creator who drove them, and this page shows which partnerships paid for themselves.
            </p>
            <div className="row-actions">
              <Link className="primary-btn revenue-setup-action" to="/marketplace">
                Connect your store
              </Link>
              <Link className="ghost-btn revenue-setup-action" to="/coupons">
                Create discount codes
              </Link>
            </div>
          </section>
        )
      ) : null}

      {rankedLeaderboard.length > 0 ? (
        <section className="page-section">
          <h2 className="section-title">Influencer leaderboard</h2>
          <DataTable
            caption={`Influencer leaderboard, ${rangeLabel.toLowerCase()}`}
            columns={leaderboardColumns}
            rows={rankedLeaderboard}
            rowKey={(row) => row.creatorId}
          />
        </section>
      ) : null}

      {data.channels.length > 0 ? (
        <section className="page-section">
          <h2 className="section-title">Channel breakdown</h2>
          <DataTable
            caption={`Channel breakdown, ${rangeLabel.toLowerCase()}`}
            columns={channelColumns}
            rows={data.channels}
            rowKey={(row) => row.channel}
          />
        </section>
      ) : null}

      {SIMULATOR_ENABLED ? (
        <section className="page-section">
          <h2 className="section-title">Simulate an order (test / demo)</h2>
          <MdsNote>
            Synthetic orders are written to real attribution data. Enabled by
            VITE_ENABLE_ORDER_SIMULATOR for testing — do not use on a live workspace.
          </MdsNote>
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
        </section>
      ) : null}
    </>
  )
}

export default DashboardPage
