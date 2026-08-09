import { useMemo, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from '../components/Mds'
import { ConfirmDialog, useToast } from '../components/ui'

const CAP_LABELS = {
  CREATE_COUPON: 'Coupon sync',
  WEBHOOK_ORDERS: 'Order webhooks',
  POLL_ORDERS: 'Order polling',
  AFFILIATE_LINK: 'Affiliate links',
}

function MarketplacePage({ providers = [], connections = [], onConnect, onDisconnect }) {
  const [providerKey, setProviderKey] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [shop, setShop] = useState('')
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState({ type: '', message: '' })
  const [pendingDisconnect, setPendingDisconnect] = useState(null)
  const [disconnecting, setDisconnecting] = useState(false)

  const toast = useToast()

  const confirmDisconnect = async () => {
    if (!pendingDisconnect) {
      return
    }
    const label = pendingDisconnect.displayName || pendingDisconnect.providerKey
    try {
      setDisconnecting(true)
      await onDisconnect(pendingDisconnect.id)
      toast.success(`${label} disconnected.`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : `Unable to disconnect ${label}.`)
    } finally {
      setDisconnecting(false)
      setPendingDisconnect(null)
    }
  }

  const selectedProvider = useMemo(
    () => providers.find((p) => p.key === providerKey) || null,
    [providers, providerKey],
  )

  const submit = async (event) => {
    event.preventDefault()
    if (!providerKey) {
      setFeedback({ type: 'error', message: 'Pick a marketplace to connect.' })
      return
    }
    setBusy(true)
    setFeedback({ type: '', message: '' })
    try {
      const credentials = {}
      if (apiKey.trim()) credentials.apiKey = apiKey.trim()
      if (shop.trim()) credentials.shop = shop.trim()
      const created = await onConnect({ providerKey, credentials })
      setFeedback({ type: 'success', message: `Connected ${created.displayName || providerKey}.` })
      setApiKey('')
      setShop('')
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to connect.' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <article className="card mds-surface mds-prose form-card page-stack">
      <MdsKicker>Marketplace</MdsKicker>
      <h3>Connect your stores</h3>
      <MdsSectionRule />
      <p>
        Connect an external marketplace to sync coupons and receive order attribution. Each
        marketplace exposes different capabilities — only supported actions are enabled per store.
      </p>

      <h4>Available marketplaces</h4>
      {providers.length === 0 ? (
        <p className="custom-attributes-empty">No providers available.</p>
      ) : (
        <ul className="simple-list">
          {providers.map((provider) => (
            <li key={provider.key}>
              <strong>{provider.displayName}</strong>
              <span className="mds-inline-code">{provider.key}</span>
              <div className="custom-attributes-readonly">
                {(provider.capabilities || []).map((cap) => (
                  <span key={cap} className="custom-attribute-pill">{CAP_LABELS[cap] || cap}</span>
                ))}
              </div>
            </li>
          ))}
        </ul>
      )}

      <MdsSectionRule />
      <h4>Connect a store</h4>
      {feedback.message ? (
        <p className={`row-save-feedback ${feedback.type === 'error' ? 'error' : 'success'}`}>{feedback.message}</p>
      ) : null}
      <form onSubmit={submit} className="inline-form page-form-grid">
        <select value={providerKey} onChange={(event) => setProviderKey(event.target.value)} required>
          <option value="">Select marketplace…</option>
          {providers.map((provider) => (
            <option key={provider.key} value={provider.key}>{provider.displayName}</option>
          ))}
        </select>
        <input
          type="text"
          value={shop}
          placeholder="Shop / store handle"
          onChange={(event) => setShop(event.target.value)}
        />
        <input
          type="password"
          value={apiKey}
          placeholder="API key / access token"
          onChange={(event) => setApiKey(event.target.value)}
          autoComplete="off"
        />
        {selectedProvider ? (
          <MdsNote>
            {selectedProvider.displayName} supports:{' '}
            {(selectedProvider.capabilities || []).map((c) => CAP_LABELS[c] || c).join(', ') || 'no listed capabilities'}.
          </MdsNote>
        ) : null}
        <button type="submit" className="primary-btn" disabled={busy}>
          {busy ? 'Connecting…' : 'Connect'}
        </button>
      </form>

      <MdsSectionRule />
      <h4>Connected stores ({connections.length})</h4>
      {connections.length === 0 ? (
        <p className="custom-attributes-empty">No stores connected yet.</p>
      ) : (
        <ul className="simple-list">
          {connections.map((conn) => (
            <li key={conn.id}>
              <strong>{conn.displayName || conn.providerKey}</strong>
              <span className="mds-inline-code">{conn.providerKey}</span>
              <span>{conn.status || 'connected'}</span>
              {conn.externalAccountRef ? <span>{conn.externalAccountRef}</span> : null}
              <div className="row-actions">
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={() => setPendingDisconnect(conn)}
                >
                  Disconnect
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {/* Disconnecting is the action that quietly stops revenue attribution, so the dialog
          says that rather than only naming the store. */}
      {pendingDisconnect ? (
        <ConfirmDialog
          title={`Disconnect ${pendingDisconnect.displayName || pendingDisconnect.providerKey}?`}
          consequence="New orders from this store stop being attributed to your creators. Coupons already pushed there keep working, but their sales will no longer appear on the revenue dashboard."
          confirmLabel="Disconnect"
          busy={disconnecting}
          onConfirm={confirmDisconnect}
          onCancel={() => setPendingDisconnect(null)}
        />
      ) : null}
    </article>
  )
}

export default MarketplacePage
