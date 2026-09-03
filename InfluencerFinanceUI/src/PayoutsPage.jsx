import { useEffect, useMemo, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from './components/Mds'

function money(value, currency = 'USD') {
  const n = Number(value)
  if (Number.isNaN(n)) return value ?? '—'
  return `${currency === 'USD' ? '$' : ''}${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function PayoutsPage({
  creators = [],
  onLoadCommissions,
  onLoadPayouts,
  onLoadProviders,
  onApproveCommission,
  onCreatePayout,
}) {
  const [commissions, setCommissions] = useState([])
  const [payouts, setPayouts] = useState([])
  const [providers, setProviders] = useState([])
  const [providerKey, setProviderKey] = useState('manual')
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState('')
  const [feedback, setFeedback] = useState({ type: '', message: '' })

  const creatorName = (id) => {
    const c = creators.find((x) => x.id === id)
    return c?.name || c?.handle || 'Creator'
  }

  const refresh = async () => {
    setLoading(true)
    try {
      const [comm, pays, provs] = await Promise.all([
        onLoadCommissions(),
        onLoadPayouts(),
        onLoadProviders().catch(() => []),
      ])
      setCommissions(Array.isArray(comm) ? comm : [])
      setPayouts(Array.isArray(pays) ? pays : [])
      setProviders(Array.isArray(provs) ? provs : [])
      if (provs?.length && !provs.some((p) => p.key === providerKey)) {
        setProviderKey(provs[0].key)
      }
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to load payouts.' })
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const pending = useMemo(() => commissions.filter((c) => c.status === 'pending'), [commissions])
  const approved = useMemo(() => commissions.filter((c) => c.status === 'approved'), [commissions])

  // Creators with approved (payable) commissions + their totals.
  const payableByCreator = useMemo(() => {
    const map = new Map()
    approved.forEach((c) => {
      const cur = map.get(c.creatorId) || { creatorId: c.creatorId, total: 0, count: 0 }
      cur.total += Number(c.commissionAmount) || 0
      cur.count += 1
      map.set(c.creatorId, cur)
    })
    return Array.from(map.values())
  }, [approved])

  const approve = async (id) => {
    setBusyId(id)
    setFeedback({ type: '', message: '' })
    try {
      await onApproveCommission(id)
      await refresh()
      setFeedback({ type: 'success', message: 'Commission approved.' })
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to approve.' })
    } finally {
      setBusyId('')
    }
  }

  const pay = async (creatorId) => {
    setBusyId(`pay-${creatorId}`)
    setFeedback({ type: '', message: '' })
    try {
      const payout = await onCreatePayout({ creatorId, providerKey })
      await refresh()
      setFeedback({ type: 'success', message: `Paid ${creatorName(creatorId)}: ${money(payout.totalAmount)} (${payout.status}).` })
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to create payout.' })
    } finally {
      setBusyId('')
    }
  }

  return (
    <article className="card mds-surface mds-prose form-card page-stack">
      <MdsKicker>Payouts</MdsKicker>
      <h3>Influencer commissions &amp; payouts</h3>
      <MdsSectionRule />

      <div className="row-actions" style={{ justifyContent: 'space-between' }}>
        <p style={{ margin: 0 }}>Approve accrued commissions, then batch them into payouts per influencer.</p>
        <button type="button" className="ghost-btn" onClick={refresh} disabled={loading}>
          {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      {feedback.message ? (
        <p className={`row-save-feedback ${feedback.type === 'error' ? 'error' : 'success'}`}>{feedback.message}</p>
      ) : null}

      <MdsSectionRule />
      <h4>Pending commissions ({pending.length})</h4>
      {pending.length === 0 ? (
        <p className="custom-attributes-empty">No pending commissions. Simulate orders on the Dashboard to accrue some.</p>
      ) : (
        <ul className="simple-list">
          {pending.map((c) => (
            <li key={c.id}>
              <strong>{creatorName(c.creatorId)}</strong>
              <span>{money(c.commissionAmount, c.currency)}</span>
              <span>on {money(c.grossSale, c.currency)} sale</span>
              <div className="row-actions">
                <button type="button" className="primary-btn" onClick={() => approve(c.id)} disabled={busyId === c.id}>
                  {busyId === c.id ? 'Approving…' : 'Approve'}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <MdsSectionRule />
      <div className="row-actions" style={{ justifyContent: 'space-between' }}>
        <h4 style={{ margin: 0 }}>Approved &amp; payable ({approved.length})</h4>
        <select value={providerKey} onChange={(e) => setProviderKey(e.target.value)}>
          {providers.length === 0 ? <option value="manual">Manual / offline</option> : null}
          {providers.map((p) => <option key={p.key} value={p.key}>{p.displayName}</option>)}
        </select>
      </div>
      {payableByCreator.length === 0 ? (
        <p className="custom-attributes-empty">
          Nothing approved yet. Approve a pending commission above and it moves here, grouped by
          creator, ready to pay in one go.
        </p>
      ) : (
        <ul className="simple-list">
          {payableByCreator.map((row) => (
            <li key={row.creatorId}>
              <strong>{creatorName(row.creatorId)}</strong>
              <span>{money(row.total)} across {row.count} commission{row.count === 1 ? '' : 's'}</span>
              <div className="row-actions">
                <button
                  type="button"
                  className="primary-btn"
                  onClick={() => pay(row.creatorId)}
                  disabled={busyId === `pay-${row.creatorId}`}
                >
                  {busyId === `pay-${row.creatorId}` ? 'Paying…' : 'Create payout'}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <MdsSectionRule />
      <h4>Payout history ({payouts.length})</h4>
      {payouts.length === 0 ? (
        <p className="custom-attributes-empty">
          No payouts yet. Once you pay an approved commission, it is recorded here with its
          reference — this is the record a creator asks for when they cannot find the money.
        </p>
      ) : (
        <ul className="simple-list">
          {payouts.map((p) => (
            <li key={p.id}>
              <strong>{creatorName(p.creatorId)}</strong>
              <span>{money(p.totalAmount, p.currency)}</span>
              <span>{p.method || p.providerKey || 'manual'}</span>
              <span>{p.status}</span>
              {p.providerRef ? <span className="mds-inline-code">{p.providerRef}</span> : null}
            </li>
          ))}
        </ul>
      )}
      <MdsNote>Commissions sit “pending” through a hold window (refund protection) before approval — a real deployment gates approval on that window.</MdsNote>
    </article>
  )
}

export default PayoutsPage
