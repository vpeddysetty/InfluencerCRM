import { useMemo, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from './components/Mds'

const CHANNEL_OPTIONS = ['', 'instagram', 'tiktok', 'youtube', 'email', 'blog', 'other']
const DISCOUNT_TYPES = [
  { value: 'percent', label: '% off' },
  { value: 'fixed', label: 'Fixed amount off' },
  { value: 'free_shipping', label: 'Free shipping' },
  { value: 'bogo', label: 'Buy one get one' },
]
// OP-21. "% of sale" was ambiguous between gross and net, and the ledger disagreed with the row it
// wrote beside it -- commission on gross, netAmount stored as sale minus discount. The label now
// says which, in the same words as AttributionService.computeCommission and the campaign
// agreement. A creator and a brand reading two different sentences about the same number is the
// dispute this exists to prevent.
const COMMISSION_TYPES = [
  { value: 'percent', label: '% of net revenue' },
  { value: 'fixed', label: 'Fixed per sale' },
]

/** The one sentence, so the form and the agreement cannot drift apart. */
export const COMMISSION_BASE_NOTE =
  'Percentage commission is calculated on net revenue after discount, excluding tax and shipping.'

const EMPTY_SINGLE = {
  campaignId: '',
  creatorId: '',
  code: '',
  codePattern: '{CREATOR}{DISCOUNT}',
  useTemplate: true,
  discountType: 'percent',
  discountValue: '',
  commissionType: 'percent',
  commissionValue: '',
  channel: '',
  landingUrl: '',
}

function nameById(list, id) {
  const found = (list || []).find((item) => item.id === id)
  return found?.name || found?.handle || ''
}

function CouponsPage({
  coupons = [],
  campaigns = [],
  creators = [],
  campaignCreators = [],
  brandName = '',
  connections = [],
  onGenerateCoupon,
  onGenerateCouponsBulk,
  onDeleteCoupon,
  onPushCoupon,
  onPersonalizeCoupon,
  onDecidePersonalization,
}) {
  const [mode, setMode] = useState('single') // 'single' | 'bulk'
  const [single, setSingle] = useState(EMPTY_SINGLE)
  const [bulk, setBulk] = useState({
    campaignId: '',
    codePattern: '{CREATOR}{DISCOUNT}',
    discountType: 'percent',
    discountValue: '',
    commissionType: 'percent',
    commissionValue: '',
    channel: '',
  })
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState({ type: '', message: '' })
  const [copiedId, setCopiedId] = useState('')
  const [filterCampaign, setFilterCampaign] = useState('')
  const [pushSelection, setPushSelection] = useState({}) // couponId -> connectionId
  const [pushingId, setPushingId] = useState('')
  const [blurbDraft, setBlurbDraft] = useState({}) // couponId -> blurb text
  const [personalizingId, setPersonalizingId] = useState('')

  // Creators associated with a campaign, derived from workflow cards; falls back to all creators.
  const creatorsForCampaign = (campaignId) => {
    if (!campaignId) return []
    const ids = new Set(
      (campaignCreators || [])
        .filter((card) => card.campaignId === campaignId && card.creatorId)
        .map((card) => card.creatorId),
    )
    const scoped = creators.filter((creator) => ids.has(creator.id))
    return scoped.length ? scoped : creators
  }

  const bulkTargets = useMemo(
    () => creatorsForCampaign(bulk.campaignId),
    [bulk.campaignId, campaignCreators, creators],
  )

  const visibleCoupons = useMemo(
    () => (filterCampaign ? coupons.filter((c) => c.campaignId === filterCampaign) : coupons),
    [coupons, filterCampaign],
  )

  const buildSharedFields = (form) => {
    const out = {}
    if (form.discountType) out.discountType = form.discountType
    if (String(form.discountValue).trim()) out.discountValue = Number(form.discountValue)
    if (form.commissionType) out.commissionType = form.commissionType
    if (String(form.commissionValue).trim()) out.commissionValue = Number(form.commissionValue)
    if (form.channel) out.channel = form.channel
    return out
  }

  const submitSingle = async (event) => {
    event.preventDefault()
    if (!single.campaignId || !single.creatorId) {
      setFeedback({ type: 'error', message: 'Pick a campaign and a creator.' })
      return
    }
    setBusy(true)
    setFeedback({ type: '', message: '' })
    try {
      const payload = {
        campaignId: single.campaignId,
        creatorId: single.creatorId,
        creatorName: nameById(creators, single.creatorId),
        brandName,
        landingUrl: single.landingUrl.trim() || undefined,
        ...buildSharedFields(single),
      }
      if (single.useTemplate) {
        payload.codePattern = single.codePattern.trim() || '{CREATOR}{DISCOUNT}'
      } else if (single.code.trim()) {
        payload.code = single.code.trim()
      }
      const created = await onGenerateCoupon(payload)
      setFeedback({ type: 'success', message: `Created coupon ${created.code}.` })
      setSingle((prev) => ({ ...EMPTY_SINGLE, campaignId: prev.campaignId }))
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to generate coupon.' })
    } finally {
      setBusy(false)
    }
  }

  const submitBulk = async (event) => {
    event.preventDefault()
    if (!bulk.campaignId) {
      setFeedback({ type: 'error', message: 'Pick a campaign to bulk-generate for.' })
      return
    }
    if (!bulkTargets.length) {
      setFeedback({ type: 'error', message: 'No creators found for this campaign.' })
      return
    }
    setBusy(true)
    setFeedback({ type: '', message: '' })
    try {
      const targetCards = (campaignCreators || []).filter((card) => card.campaignId === bulk.campaignId)
      const created = await onGenerateCouponsBulk({
        campaignId: bulk.campaignId,
        codePattern: bulk.codePattern.trim() || '{CREATOR}{DISCOUNT}',
        brandName,
        ...buildSharedFields(bulk),
        creators: bulkTargets.map((creator) => {
          const card = targetCards.find((c) => c.creatorId === creator.id)
          return {
            creatorId: creator.id,
            creatorName: creator.name || creator.handle || '',
            campaignCreatorId: card?.id || undefined,
          }
        }),
      })
      setFeedback({ type: 'success', message: `Generated ${created.length} coupon${created.length === 1 ? '' : 's'}.` })
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to bulk-generate coupons.' })
    } finally {
      setBusy(false)
    }
  }

  const trackingLink = (coupon) => {
    if (coupon.landingUrl) {
      const sep = coupon.landingUrl.includes('?') ? '&' : '?'
      const ref = coupon.refSlug || coupon.code
      const ch = coupon.channel ? `&ch=${encodeURIComponent(coupon.channel)}` : ''
      return `${coupon.landingUrl}${sep}ref=${encodeURIComponent(ref)}${ch}`
    }
    return ''
  }

  const copy = async (id, text) => {
    if (!text) return
    try {
      await navigator.clipboard.writeText(text)
      setCopiedId(id)
      setTimeout(() => setCopiedId((prev) => (prev === id ? '' : prev)), 1500)
    } catch {
      setFeedback({ type: 'error', message: 'Clipboard unavailable in this context.' })
    }
  }

  const syncCapableConnections = useMemo(
    () => connections.filter((conn) => (conn.status || 'connected') === 'connected'),
    [connections],
  )

  const push = async (coupon) => {
    const connectionId = pushSelection[coupon.id] || syncCapableConnections[0]?.id
    if (!connectionId) {
      setFeedback({ type: 'error', message: 'Connect a marketplace first (Marketplace tab).' })
      return
    }
    setPushingId(coupon.id)
    setFeedback({ type: '', message: '' })
    try {
      const updated = await onPushCoupon(coupon.id, connectionId)
      setFeedback({ type: 'success', message: `Pushed ${updated.code} (${updated.syncStatus}).` })
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to push coupon.' })
    } finally {
      setPushingId('')
    }
  }

  const submitPersonalization = async (coupon) => {
    const blurb = (blurbDraft[coupon.id] ?? coupon.personalBlurb ?? '').trim()
    if (!blurb) {
      setFeedback({ type: 'error', message: 'Add a personalization blurb first.' })
      return
    }
    setPersonalizingId(coupon.id)
    setFeedback({ type: '', message: '' })
    try {
      await onPersonalizeCoupon(coupon.id, { personalBlurb: blurb })
      setFeedback({ type: 'success', message: 'Personalization submitted for approval.' })
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to submit.' })
    } finally {
      setPersonalizingId('')
    }
  }

  const decide = async (coupon, decision) => {
    setPersonalizingId(coupon.id)
    setFeedback({ type: '', message: '' })
    try {
      await onDecidePersonalization(coupon.id, decision)
      setFeedback({ type: 'success', message: `Personalization ${decision}d.` })
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to decide.' })
    } finally {
      setPersonalizingId('')
    }
  }

  const describeDiscount = (coupon) => {
    if (!coupon.discountType) return ''
    if (coupon.discountType === 'percent') return `${coupon.discountValue ?? ''}% off`
    if (coupon.discountType === 'fixed') return `$${coupon.discountValue ?? ''} off`
    if (coupon.discountType === 'free_shipping') return 'Free shipping'
    if (coupon.discountType === 'bogo') return 'BOGO'
    return coupon.discountType
  }

  return (
    <article className="card mds-surface mds-prose form-card page-stack">
      <MdsKicker>Coupons</MdsKicker>
      <h3>Generate influencer coupons</h3>
      <MdsSectionRule />
      <p>
        Create discount codes that anchor each campaign↔influencer pair. Generate one at a time, or
        bulk-generate a code for every creator on a campaign using a template like{' '}
        <code>{'{CREATOR}{DISCOUNT}'}</code>.
      </p>

      <div className="row-actions" style={{ marginBottom: '0.5rem' }}>
        <button
          type="button"
          className={mode === 'single' ? 'primary-btn' : 'ghost-btn'}
          onClick={() => setMode('single')}
        >
          Single
        </button>
        <button
          type="button"
          className={mode === 'bulk' ? 'primary-btn' : 'ghost-btn'}
          onClick={() => setMode('bulk')}
        >
          Bulk (per campaign)
        </button>
      </div>

      {feedback.message ? (
        <p className={`row-save-feedback ${feedback.type === 'error' ? 'error' : 'success'}`}>{feedback.message}</p>
      ) : null}

      {mode === 'single' ? (
        <form onSubmit={submitSingle} className="inline-form page-form-grid">
          <select
            value={single.campaignId}
            onChange={(event) => setSingle((prev) => ({ ...prev, campaignId: event.target.value, creatorId: '' }))}
            required
          >
            <option value="">Select campaign…</option>
            {campaigns.map((campaign) => (
              <option key={campaign.id} value={campaign.id}>{campaign.name}</option>
            ))}
          </select>
          <select
            value={single.creatorId}
            onChange={(event) => setSingle((prev) => ({ ...prev, creatorId: event.target.value }))}
            required
          >
            <option value="">Select creator…</option>
            {(creatorsForCampaign(single.campaignId).length ? creatorsForCampaign(single.campaignId) : creators).map((creator) => (
              <option key={creator.id} value={creator.id}>{creator.name || creator.handle}</option>
            ))}
          </select>

          <label className="mds-inline-check">
            <input
              type="checkbox"
              checked={single.useTemplate}
              onChange={(event) => setSingle((prev) => ({ ...prev, useTemplate: event.target.checked }))}
            />
            Use template pattern
          </label>
          {single.useTemplate ? (
            <input
              type="text"
              value={single.codePattern}
              placeholder="Code pattern e.g. {CREATOR}{DISCOUNT}"
              onChange={(event) => setSingle((prev) => ({ ...prev, codePattern: event.target.value }))}
            />
          ) : (
            <input
              type="text"
              value={single.code}
              placeholder="Custom code e.g. JADE20 (blank = random)"
              onChange={(event) => setSingle((prev) => ({ ...prev, code: event.target.value }))}
            />
          )}

          <select
            value={single.discountType}
            onChange={(event) => setSingle((prev) => ({ ...prev, discountType: event.target.value }))}
          >
            {DISCOUNT_TYPES.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
          </select>
          <input
            type="number"
            value={single.discountValue}
            placeholder="Discount value"
            onChange={(event) => setSingle((prev) => ({ ...prev, discountValue: event.target.value }))}
          />
          <select
            value={single.commissionType}
            onChange={(event) => setSingle((prev) => ({ ...prev, commissionType: event.target.value }))}
          >
            {COMMISSION_TYPES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
          {/* OP-21. Shown where the rate is SET, not buried in help: the moment someone chooses a
              percentage is the moment the basis matters, and it is the sentence the agreement and
              the ledger both use. */}
          <p className="helper commission-base-note">{COMMISSION_BASE_NOTE}</p>
          <input
            type="number"
            value={single.commissionValue}
            placeholder="Commission value"
            onChange={(event) => setSingle((prev) => ({ ...prev, commissionValue: event.target.value }))}
          />
          <select
            value={single.channel}
            onChange={(event) => setSingle((prev) => ({ ...prev, channel: event.target.value }))}
          >
            {CHANNEL_OPTIONS.map((ch) => <option key={ch} value={ch}>{ch ? ch : 'No channel'}</option>)}
          </select>
          <input
            type="url"
            value={single.landingUrl}
            placeholder="Landing URL (optional, for tracking link)"
            onChange={(event) => setSingle((prev) => ({ ...prev, landingUrl: event.target.value }))}
          />
          <button type="submit" className="primary-btn" disabled={busy}>
            {busy ? 'Generating…' : 'Generate coupon'}
          </button>
        </form>
      ) : (
        <form onSubmit={submitBulk} className="inline-form page-form-grid">
          <select
            value={bulk.campaignId}
            onChange={(event) => setBulk((prev) => ({ ...prev, campaignId: event.target.value }))}
            required
          >
            <option value="">Select campaign…</option>
            {campaigns.map((campaign) => (
              <option key={campaign.id} value={campaign.id}>{campaign.name}</option>
            ))}
          </select>
          <input
            type="text"
            value={bulk.codePattern}
            placeholder="Code pattern e.g. {CREATOR}{DISCOUNT}"
            onChange={(event) => setBulk((prev) => ({ ...prev, codePattern: event.target.value }))}
          />
          <select
            value={bulk.discountType}
            onChange={(event) => setBulk((prev) => ({ ...prev, discountType: event.target.value }))}
          >
            {DISCOUNT_TYPES.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
          </select>
          <input
            type="number"
            value={bulk.discountValue}
            placeholder="Discount value"
            onChange={(event) => setBulk((prev) => ({ ...prev, discountValue: event.target.value }))}
          />
          <select
            value={bulk.commissionType}
            onChange={(event) => setBulk((prev) => ({ ...prev, commissionType: event.target.value }))}
          >
            {COMMISSION_TYPES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
          {/* OP-21. Shown where the rate is SET, not buried in help: the moment someone chooses a
              percentage is the moment the basis matters, and it is the sentence the agreement and
              the ledger both use. */}
          <p className="helper commission-base-note">{COMMISSION_BASE_NOTE}</p>
          <input
            type="number"
            value={bulk.commissionValue}
            placeholder="Commission value"
            onChange={(event) => setBulk((prev) => ({ ...prev, commissionValue: event.target.value }))}
          />
          <select
            value={bulk.channel}
            onChange={(event) => setBulk((prev) => ({ ...prev, channel: event.target.value }))}
          >
            {CHANNEL_OPTIONS.map((ch) => <option key={ch} value={ch}>{ch ? ch : 'No channel'}</option>)}
          </select>
          <MdsNote>
            {bulk.campaignId
              ? `Will generate ${bulkTargets.length} coupon${bulkTargets.length === 1 ? '' : 's'} — one per creator on this campaign.`
              : 'Pick a campaign to see how many coupons will be generated.'}
          </MdsNote>
          <button type="submit" className="primary-btn" disabled={busy || !bulkTargets.length}>
            {busy ? 'Generating…' : `Generate ${bulkTargets.length || ''} coupons`}
          </button>
        </form>
      )}

      <MdsSectionRule />
      <div className="row-actions" style={{ justifyContent: 'space-between' }}>
        <h4 style={{ margin: 0 }}>Coupons ({visibleCoupons.length})</h4>
        <select value={filterCampaign} onChange={(event) => setFilterCampaign(event.target.value)}>
          <option value="">All campaigns</option>
          {campaigns.map((campaign) => (
            <option key={campaign.id} value={campaign.id}>{campaign.name}</option>
          ))}
        </select>
      </div>

      {visibleCoupons.length === 0 ? (
        <p className="custom-attributes-empty">No coupons yet. Generate one above.</p>
      ) : (
        <ul className="simple-list">
          {visibleCoupons.map((coupon) => {
            const link = trackingLink(coupon)
            return (
              <li key={coupon.id}>
                <strong className="mds-inline-code">{coupon.code}</strong>
                <span>{nameById(campaigns, coupon.campaignId) || 'Campaign'}</span>
                <span>{nameById(creators, coupon.creatorId) || 'Creator'}</span>
                {describeDiscount(coupon) ? <span>{describeDiscount(coupon)}</span> : null}
                {coupon.channel ? <span>#{coupon.channel}</span> : null}
                <span>{coupon.syncStatus === 'synced' ? 'Synced' : 'Local'}</span>
                <div className="row-actions">
                  <button type="button" className="ghost-btn" onClick={() => copy(`code-${coupon.id}`, coupon.code)}>
                    {copiedId === `code-${coupon.id}` ? 'Copied!' : 'Copy code'}
                  </button>
                  {link ? (
                    <button type="button" className="ghost-btn" onClick={() => copy(`link-${coupon.id}`, link)}>
                      {copiedId === `link-${coupon.id}` ? 'Copied!' : 'Copy link'}
                    </button>
                  ) : null}
                  {syncCapableConnections.length ? (
                    <>
                      {syncCapableConnections.length > 1 ? (
                        <select
                          value={pushSelection[coupon.id] || ''}
                          onChange={(event) =>
                            setPushSelection((prev) => ({ ...prev, [coupon.id]: event.target.value }))
                          }
                        >
                          <option value="">Default store</option>
                          {syncCapableConnections.map((conn) => (
                            <option key={conn.id} value={conn.id}>{conn.displayName || conn.providerKey}</option>
                          ))}
                        </select>
                      ) : null}
                      <button
                        type="button"
                        className="ghost-btn"
                        onClick={() => push(coupon)}
                        disabled={pushingId === coupon.id}
                      >
                        {pushingId === coupon.id
                          ? 'Pushing…'
                          : coupon.syncStatus === 'synced'
                            ? 'Re-push'
                            : 'Push to store'}
                      </button>
                    </>
                  ) : null}
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => {
                      if (window.confirm(`Delete coupon ${coupon.code}?`)) onDeleteCoupon(coupon.id)
                    }}
                  >
                    Delete
                  </button>
                </div>
                <div className="coupon-personalize">
                  <span className={`perso-status perso-${coupon.personalizationStatus || 'none'}`}>
                    Personalization: {coupon.personalizationStatus || 'none'}
                  </span>
                  <input
                    type="text"
                    placeholder="Creator blurb for the landing page"
                    value={blurbDraft[coupon.id] ?? coupon.personalBlurb ?? ''}
                    onChange={(e) => setBlurbDraft((prev) => ({ ...prev, [coupon.id]: e.target.value }))}
                  />
                  <div className="row-actions">
                    <button type="button" className="ghost-btn" onClick={() => submitPersonalization(coupon)} disabled={personalizingId === coupon.id}>
                      {personalizingId === coupon.id ? 'Working…' : 'Submit for approval'}
                    </button>
                    {coupon.personalizationStatus === 'pending' ? (
                      <>
                        <button type="button" className="primary-btn" onClick={() => decide(coupon, 'approve')} disabled={personalizingId === coupon.id}>Approve</button>
                        <button type="button" className="ghost-btn" onClick={() => decide(coupon, 'reject')} disabled={personalizingId === coupon.id}>Reject</button>
                      </>
                    ) : null}
                  </div>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </article>
  )
}

export default CouponsPage
