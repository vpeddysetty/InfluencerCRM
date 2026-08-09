import { useEffect, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from '../components/Mds'
import { Badge, ConfirmDialog, PlanUsage } from '../components/ui'
import { PUBLIC_TIERS, describeSubscription, formatAmount, formatDate } from '../shell/plan'

/**
 * The subscription and its billing history (roadmap M2.1/M2.2).
 *
 * <p><b>Who sees what.</b> Reaching this page needs `account:billing:read`, which OWNER and ADMIN
 * hold. Acting needs `account:billing`, which only OWNER holds — the server says so via
 * `canManage`, and this page renders the buttons from that flag rather than re-deriving the rule.
 * An admin sees the plan, the state and the invoices, and is told plainly why they cannot change
 * it, which is better than buttons that fail.
 *
 * <p><b>Cancel is deliberately prominent.</b> The competitor this positions against is most
 * criticised for cancellation being "impossible to stop"; hiding the button would be adopting the
 * exact behaviour the product is meant to beat. It confirms first, and says what is kept.
 */
function BillingPage({
  canViewBilling = false,
  onLoadSubscription,
  onLoadInvoices,
  onLoadPlan,
  onSubscribe,
  onPause,
  onResume,
  onCancel,
}) {
  const [subscription, setSubscription] = useState(null)
  const [invoices, setInvoices] = useState([])
  const [plan, setPlan] = useState(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState('')
  const [feedback, setFeedback] = useState({ type: '', message: '' })
  const [confirmingCancel, setConfirmingCancel] = useState(false)

  const refresh = async () => {
    setLoading(true)
    try {
      // Invoices and usage are allowed to fail on their own: the subscription is the point of the
      // page, and losing it because a secondary panel errored would be the worse outcome.
      const [subscriptionPayload, invoiceRows, planPayload] = await Promise.all([
        onLoadSubscription(),
        onLoadInvoices ? onLoadInvoices().catch(() => []) : Promise.resolve([]),
        onLoadPlan ? onLoadPlan().catch(() => null) : Promise.resolve(null),
      ])
      setSubscription(describeSubscription(subscriptionPayload))
      setInvoices(Array.isArray(invoiceRows) ? invoiceRows : [])
      setPlan(planPayload)
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to load billing.' })
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (canViewBilling) {
      refresh()
    } else {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canViewBilling])

  // Returning from hosted checkout. Says something either way — landing back on an unchanged page
  // after paying reads as a failure, and after cancelling reads as an accident.
  useEffect(() => {
    const outcome = new URLSearchParams(window.location.search).get('checkout')
    if (outcome === 'success') {
      // Deliberately hedged. The subscription activates on the provider's webhook, which may not
      // have arrived yet — claiming "you are on Pro" before it lands would be a promise the page
      // cannot keep, and refreshing would then show the old plan.
      setFeedback({
        type: 'success',
        message: 'Payment received. Your plan updates as soon as the provider confirms it — usually within a few seconds.',
      })
    } else if (outcome === 'cancelled') {
      setFeedback({ type: '', message: 'Checkout cancelled. Nothing was charged.' })
    }
  }, [])

  const act = async (key, action, successMessage) => {
    setBusy(key)
    setFeedback({ type: '', message: '' })
    try {
      await action()
      setFeedback({ type: 'success', message: successMessage })
      await refresh()
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'That did not work.' })
    } finally {
      setBusy('')
      setConfirmingCancel(false)
    }
  }

  if (!canViewBilling) {
    return (
      <section className="page-section">
        <MdsKicker>Billing</MdsKicker>
        <h2>Plan and billing</h2>
        <MdsNote>Only account owners and admins can see billing.</MdsNote>
      </section>
    )
  }

  if (loading) {
    return (
      <section className="page-section">
        <MdsKicker>Billing</MdsKicker>
        <h2>Plan and billing</h2>
        <MdsNote>Loading…</MdsNote>
      </section>
    )
  }

  const state = subscription || describeSubscription(null)
  const paidTiers = PUBLIC_TIERS.filter((tier) => tier.key !== 'free')

  return (
    <section className="page-section">
      <MdsKicker>Billing</MdsKicker>
      <h2>Plan and billing</h2>

      {feedback.message ? (
        <MdsNote className={feedback.type === 'error' ? 'auth-error-note' : ''}>{feedback.message}</MdsNote>
      ) : null}

      {/* An unpaid subscription must never look like a paid one. The server reports whether a real
          payment provider handled it; when it did not, this says so rather than staying quiet. */}
      {state.subscribed && !state.chargesMoney ? (
        <MdsNote className="billing-unpaid-note">
          <strong>No payment was taken for this subscription.</strong>
          It was recorded manually ({state.providerName || 'manual'}) because no payment provider is
          configured on this deployment. The plan is active and its limits apply, but nothing has
          been charged.
        </MdsNote>
      ) : null}

      <div className="billing-status">
        <span className="billing-status-plan">
          <strong>{String(state.plan).charAt(0).toUpperCase() + String(state.plan).slice(1)}</strong>
        </span>
        <Badge tone={state.tone}>{state.statusLabel}</Badge>
      </div>
      <p className="helper">{state.summary}</p>

      {/* Said once, plainly, wherever a limit or an ending is mentioned. The fear at every one of
          these moments is that data disappears, and it never does. */}
      {!state.canManage ? (
        <MdsNote>
          You can see the plan and its invoices. Only the account owner can change, pause, or cancel
          the subscription.
        </MdsNote>
      ) : null}

      {state.canManage ? (
        <div className="billing-actions">
          {state.canPause ? (
            <button
              type="button"
              className="ghost-btn"
              disabled={Boolean(busy)}
              onClick={() => act('pause', onPause, 'Billing paused. Resume whenever you are ready.')}
            >
              {busy === 'pause' ? 'Pausing…' : 'Pause billing'}
            </button>
          ) : null}

          {state.canResume ? (
            <button
              type="button"
              className="primary-btn"
              disabled={Boolean(busy)}
              onClick={() => act('resume', onResume, 'Subscription resumed.')}
            >
              {busy === 'resume' ? 'Resuming…' : 'Resume billing'}
            </button>
          ) : null}

          {state.canCancel && !state.cancelAtPeriodEnd ? (
            <button
              type="button"
              className="ghost-btn"
              disabled={Boolean(busy)}
              onClick={() => setConfirmingCancel(true)}
            >
              Cancel subscription
            </button>
          ) : null}
        </div>
      ) : null}

      <MdsSectionRule />

      <h3>What your plan includes</h3>
      <PlanUsage plan={plan?.plan} usage={plan?.usage} />

      {!state.subscribed || state.status === 'cancelled' ? (
        <>
          <MdsSectionRule />
          <h3>Upgrade</h3>
          <div className="billing-tier-grid">
            {paidTiers.map((tier) => (
              <article key={tier.key} className="billing-tier-card">
                <p className="billing-tier-name">{tier.label}</p>
                <p className="helper">{tier.tagline}</p>
                <ul className="billing-tier-list">
                  {tier.highlights.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
                {state.canManage ? (
                  <button
                    type="button"
                    className="primary-btn"
                    disabled={Boolean(busy)}
                    onClick={() =>
                      act(`subscribe-${tier.key}`, () => onSubscribe(tier.key),
                        `Subscribed to ${tier.label}.`)}
                  >
                    {busy === `subscribe-${tier.key}` ? 'Starting…' : `Choose ${tier.label}`}
                  </button>
                ) : null}
              </article>
            ))}
          </div>
        </>
      ) : null}

      <MdsSectionRule />

      <h3>Invoices ({invoices.length})</h3>
      {invoices.length === 0 ? (
        <MdsNote>No invoices yet.</MdsNote>
      ) : (
        <ul className="billing-invoice-list">
          {invoices.map((invoice) => (
            <li key={invoice.id} className="billing-invoice-row">
              <span className="billing-invoice-date">
                {formatDate(invoice.issuedAt || invoice.createdAt)}
              </span>
              <span className="billing-invoice-amount">
                {formatAmount(invoice.amountCents, invoice.currency)}
              </span>
              <Badge tone={invoice.status === 'paid' ? 'success' : 'neutral'}>{invoice.status}</Badge>
            </li>
          ))}
        </ul>
      )}

      {confirmingCancel ? (
        <ConfirmDialog
          title="Cancel this subscription?"
          // Names exactly what is kept and until when. A cancel dialog that only warns is the
          // dark pattern this product positions against.
          consequence={
            state.endsAt
              ? `You keep full access until ${formatDate(state.endsAt)} — the time you have already paid for. After that the account moves to the free plan. Nothing is deleted, and you can subscribe again at any time.`
              : 'The account moves to the free plan. Nothing is deleted, and you can subscribe again at any time.'
          }
          confirmLabel="Cancel subscription"
          busy={busy === 'cancel'}
          onConfirm={() => act('cancel', () => onCancel({ immediate: false }),
            'Subscription cancelled. You keep access until the end of the paid period.')}
          onCancel={() => setConfirmingCancel(false)}
        />
      ) : null}
    </section>
  )
}

export default BillingPage
