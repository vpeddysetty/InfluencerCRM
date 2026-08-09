import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  Badge,
  DataTable,
  EmptyState,
  MetricsProvenance,
  PageHeader,
} from '../components/ui'

/**
 * Everything known about one creator, on one URL.
 *
 * <p>The defining gap this closes: every route in this app was a list, and a creator opened in an
 * edit *drawer* — a form, not a record. Three consequences followed. Nobody could send a colleague
 * "look at this creator", which is daily friction in an agency where talent gets handed off. There
 * was no relationship history. And a creator's campaigns, coupons, payouts and attributed revenue
 * lived on four different pages, so the person answering "is this partnership working?"
 * reassembled it mentally, every time.
 *
 * <p>For an influencer CRM the relationship <em>is</em> the asset — Grin and CreatorIQ both centre
 * a creator profile. This is that page.
 *
 * <p><b>Reads from state the shell already holds.</b> Campaigns, coupons and workflow cards are
 * loaded once for the workspace; filtering them by creator needs no new endpoint. Only attributed
 * revenue and commissions are fetched here, because those are per-creator aggregates the list
 * never needed.
 */

function money(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) {
    return '—'
  }
  return `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

/** Two initials, matching the avatar in the directory so the same person looks the same. */
function initialsOf(name) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean)
  if (!parts.length) return '—'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase()
}

const VETTING_TONES = {
  approved: 'success',
  rejected: 'danger',
  under_review: 'warning',
  lead: 'neutral',
}

function CreatorRecordPage({
  creators = [],
  campaigns = [],
  campaignCreators = [],
  coupons = [],
  workflowCards = [],
  onLoadRevenue,
  onLoadCommissions,
}) {
  const { creatorId } = useParams()
  const navigate = useNavigate()

  const [revenue, setRevenue] = useState(null)
  const [commissions, setCommissions] = useState([])
  const [loadError, setLoadError] = useState('')

  const creator = useMemo(
    () => creators.find((c) => c.id === creatorId) || null,
    [creators, creatorId],
  )

  useEffect(() => {
    let active = true
    const load = async () => {
      try {
        const [revenuePayload, commissionPayload] = await Promise.all([
          onLoadRevenue ? onLoadRevenue() : Promise.resolve(null),
          onLoadCommissions ? onLoadCommissions() : Promise.resolve([]),
        ])
        if (!active) return
        setRevenue(revenuePayload)
        setCommissions(Array.isArray(commissionPayload) ? commissionPayload : [])
      } catch (error) {
        if (!active) return
        // Non-fatal. The identity and relationship panels below come from state the shell already
        // holds, so a failed revenue call must not blank the whole page.
        setLoadError(error instanceof Error ? error.message : 'Could not load revenue for this creator.')
      }
    }
    load()
    return () => { active = false }
  }, [onLoadRevenue, onLoadCommissions])

  // This creator's row in the attribution leaderboard, if they have driven any sales.
  const attribution = useMemo(() => {
    const rows = Array.isArray(revenue?.leaderboard) ? revenue.leaderboard : []
    return rows.find((row) => row.creatorId === creatorId) || null
  }, [revenue, creatorId])

  const theirCampaigns = useMemo(() => {
    const linkedIds = new Set(
      campaignCreators.filter((link) => link.creatorId === creatorId).map((link) => link.campaignId),
    )
    return campaigns.filter((campaign) => linkedIds.has(campaign.id))
  }, [campaigns, campaignCreators, creatorId])

  const theirCoupons = useMemo(
    () => coupons.filter((coupon) => coupon.creatorId === creatorId),
    [coupons, creatorId],
  )

  const theirCards = useMemo(
    () => workflowCards.filter((card) => card.creatorId === creatorId),
    [workflowCards, creatorId],
  )

  const theirCommissions = useMemo(
    () => commissions.filter((row) => row.creatorId === creatorId),
    [commissions, creatorId],
  )

  // A creator id that matches nothing. Happens after a delete, or from a stale link — which is
  // exactly the sharing case this page exists to enable, so it needs a real answer rather than a
  // blank screen.
  if (!creator) {
    return (
      <>
        <PageHeader title="Creator" description="This record could not be found." />
        <EmptyState
          icon="◍"
          title="No such creator"
          description="They may have been removed, or the link may be out of date."
          action={
            <button type="button" className="primary-btn" onClick={() => navigate('/creators')}>
              Back to creators
            </button>
          }
        />
      </>
    )
  }

  const vettingStatus = String(creator.vettingStatus || '').toLowerCase()

  return (
    <>
      <PageHeader
        title={creator.name || creator.handle || 'Creator'}
        description={[creator.handle, creator.email].filter(Boolean).join(' · ')}
        action={
          <Link className="ghost-btn" to="/creators">Back to creators</Link>
        }
      />

      {loadError ? <p className="row-save-feedback error">{loadError}</p> : null}

      <section className="record-identity">
        <span className="avatar avatar-lg" aria-hidden="true">{initialsOf(creator.name)}</span>
        <div className="record-identity-meta">
          <div className="record-identity-badges">
            {creator.platform ? <Badge tone="info">{creator.platform}</Badge> : null}
            {vettingStatus ? (
              <Badge tone={VETTING_TONES[vettingStatus] || 'neutral'}>
                {vettingStatus.replace(/_/g, ' ')}
              </Badge>
            ) : null}
          </div>
          {/* The rate this brand pays. Per-brand by design — the same creator can hold a different
              rate under another brand — and the one capability with no documented competitor
              equivalent, so it belongs in the header rather than a form field. */}
          {creator.preferredRate !== null && creator.preferredRate !== undefined && creator.preferredRate !== ''
            ? <p className="record-rate">{money(creator.preferredRate)} <span>agreed rate</span></p>
            : <p className="record-rate record-rate-unset">No rate agreed</p>}
        </div>
      </section>

      {/* Audience, with its provenance. Kept next to the identity because "how big are they and
          can I trust that number" is one question, not two. */}
      {creator.followerCount !== null && creator.followerCount !== undefined ? (
        <section className="audience-panel">
          <h2 className="audience-panel-title">Audience</h2>
          <dl className="audience-stats">
            <div>
              <dt>Followers</dt>
              <dd>{Number(creator.followerCount).toLocaleString()}</dd>
            </div>
            {creator.engagementRate !== null && creator.engagementRate !== undefined ? (
              <div>
                <dt>Engagement</dt>
                <dd>{Number(creator.engagementRate).toFixed(2)}%</dd>
              </div>
            ) : null}
            {creator.averageViews !== null && creator.averageViews !== undefined ? (
              <div>
                <dt>Avg. views</dt>
                <dd>{Number(creator.averageViews).toLocaleString()}</dd>
              </div>
            ) : null}
          </dl>
          <MetricsProvenance source={creator.metricsSource} fetchedAt={creator.metricsFetchedAt} />
        </section>
      ) : null}

      {/* The question the whole product exists to answer, for this one person. Shown only when
          they have actually driven sales — six zeroed tiles would say nothing except "empty". */}
      {attribution ? (
        <section className="page-section">
          <h2 className="section-title">Attributed revenue</h2>
          <div className="kpi-grid">
            <div className="kpi-tile"><span className="kpi-label">Revenue</span><strong className="kpi-value">{money(attribution.revenue)}</strong></div>
            <div className="kpi-tile"><span className="kpi-label">Orders</span><strong className="kpi-value">{attribution.orders ?? 0}</strong></div>
            <div className="kpi-tile"><span className="kpi-label">Commission</span><strong className="kpi-value">{money(attribution.commission)}</strong></div>
            <div className="kpi-tile"><span className="kpi-label">Total cost</span><strong className="kpi-value">{money(attribution.cost)}</strong></div>
            <div className="kpi-tile">
              <span className="kpi-label">ROI</span>
              <strong className="kpi-value">{attribution.roi}{attribution.roi === '∞' ? '' : '×'}</strong>
            </div>
          </div>
        </section>
      ) : null}

      <section className="page-section">
        <h2 className="section-title">Campaigns</h2>
        <DataTable
          caption={`Campaigns for ${creator.name || 'this creator'}`}
          columns={[
            { key: 'name', header: 'Campaign' },
            { key: 'status', header: 'Status', render: (row) => <Badge tone="neutral">{row.status || '—'}</Badge> },
            { key: 'startDate', header: 'Starts', render: (row) => row.startDate || '—' },
            { key: 'endDate', header: 'Ends', render: (row) => row.endDate || '—' },
          ]}
          rows={theirCampaigns}
          rowKey={(row) => row.id}
          emptyState={<EmptyState title="Not on any campaign yet" description="Assign them from the campaigns page." />}
        />
      </section>

      <section className="page-section">
        <h2 className="section-title">Discount codes</h2>
        <DataTable
          caption={`Discount codes for ${creator.name || 'this creator'}`}
          columns={[
            { key: 'code', header: 'Code' },
            { key: 'discountValue', header: 'Discount', align: 'right', render: (row) => (row.discountValue ? `${row.discountValue}${row.discountType === 'percent' ? '%' : ''}` : '—') },
            { key: 'commissionValue', header: 'Commission', align: 'right', render: (row) => (row.commissionValue ? `${row.commissionValue}${row.commissionType === 'percent' ? '%' : ''}` : '—') },
            { key: 'isActive', header: 'Active', render: (row) => <Badge tone={row.isActive ? 'success' : 'neutral'}>{row.isActive ? 'Active' : 'Inactive'}</Badge> },
          ]}
          rows={theirCoupons}
          rowKey={(row) => row.id}
          emptyState={<EmptyState title="No discount codes" description="A code per creator is what ties a sale back to the person who drove it." />}
        />
      </section>

      <section className="page-section">
        <h2 className="section-title">Commissions</h2>
        <DataTable
          caption={`Commissions for ${creator.name || 'this creator'}`}
          columns={[
            { key: 'orderId', header: 'Order' },
            { key: 'commissionAmount', header: 'Commission', align: 'right', render: (row) => money(row.commissionAmount) },
            { key: 'grossSale', header: 'Gross sale', align: 'right', render: (row) => money(row.grossSale) },
            { key: 'status', header: 'Status', render: (row) => <Badge tone={row.status === 'paid' ? 'success' : 'neutral'}>{row.status || '—'}</Badge> },
          ]}
          rows={theirCommissions}
          rowKey={(row) => row.id}
          emptyState={<EmptyState title="No commissions accrued" description="Commissions appear once an order is attributed to one of their codes." />}
        />
      </section>

      {/* The closest thing to relationship history this system currently records. Deliberately
          derived from existing rows rather than a new activity log: whether anyone wants to
          *write* to a timeline is worth learning before building one. */}
      <section className="page-section">
        <h2 className="section-title">Workflow</h2>
        <DataTable
          caption={`Workflow cards for ${creator.name || 'this creator'}`}
          columns={[
            { key: 'name', header: 'Card' },
            { key: 'status', header: 'Stage', render: (row) => <Badge tone="neutral">{row.status || '—'}</Badge> },
            { key: 'agreedFee', header: 'Agreed fee', align: 'right', render: (row) => (row.agreedFee ? money(row.agreedFee) : '—') },
            { key: 'createdAt', header: 'Added', render: (row) => (row.createdAt ? String(row.createdAt).slice(0, 10) : '—') },
          ]}
          rows={theirCards}
          rowKey={(row) => row.id}
          emptyState={<EmptyState title="Not on the board" description="Add a relationship card to track them through your stages." />}
        />
      </section>
    </>
  )
}

export default CreatorRecordPage
