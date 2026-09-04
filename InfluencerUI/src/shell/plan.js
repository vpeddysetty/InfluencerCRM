/**
 * Plan limits and consumption, as data rather than markup (roadmap M2.3).
 *
 * <p>Plain `.js` for the same reason `provenance.js` is: the repo's test runner is bare
 * `node --test` with no JSX loader, so the logic worth asserting has to sit in a module Node can
 * import. And this logic is worth asserting — it decides when a user is told they are running out
 * of room, and the failure modes are quiet ones. A wrong threshold either nags an account that has
 * plenty of space or says nothing to one about to be blocked mid-task.
 *
 * <p><b>The server is the authority.</b> Nothing here enforces anything; enforcement is
 * `EntitlementService` in the BFF, which returns 402. This exists so a limit is visible BEFORE it
 * is hit — being refused mid-task with no prior warning reads as a bug rather than a boundary.
 */

/** Matches `PlanPolicy.UNLIMITED` in the BFF. */
export const UNLIMITED = -1

/**
 * Fraction of a limit at which we start warning.
 *
 * <p>0.8 rather than something tighter: the point is to give warning while there is still room to
 * act. A notice at 95% of a 25-creator plan arrives with one slot left, which is not warning so
 * much as narration.
 */
const WARN_AT = 0.8

/** Plan display names. Unknown keys render verbatim — see `describePlan`. */
const PLAN_LABELS = Object.freeze({
  free: 'Free',
  pro: 'Pro',
  agency: 'Agency',
})

/**
 * The tier that fixes a limit on the current one.
 *
 * <p>Mirrors the server's message so the UI and the 402 never suggest different upgrades.
 */
const NEXT_TIER = Object.freeze({
  free: 'Pro',
  pro: 'Agency',
})

export function describePlan(plan) {
  const key = String(plan || '').trim().toLowerCase()
  return {
    key,
    label: PLAN_LABELS[key] || (key ? key.charAt(0).toUpperCase() + key.slice(1) : 'Free'),
    nextTier: NEXT_TIER[key] || null,
  }
}

export function isUnlimited(limit) {
  return limit === UNLIMITED || limit === null || limit === undefined
}

/**
 * How full one resource is.
 *
 * <p>`ratio` is null when unlimited — deliberately not 0, so a caller cannot accidentally sort or
 * compare an unlimited resource as though it were empty.
 *
 * <p>`atLimit` uses `>=` to match the server: an account exactly at its limit is full. Using `>`
 * is the same off-by-one the server-side tests guard, and having the two disagree would show a
 * user "24 of 25, room to spare" on the request that gets refused.
 */
export function describeUsage(entry) {
  const used = Number(entry?.used ?? 0)
  const limit = entry?.limit
  const unlimited = isUnlimited(limit)
  const ratio = unlimited || limit <= 0 ? null : used / limit

  return {
    resource: entry?.resource || '',
    label: entry?.label || entry?.resource || '',
    used,
    limit,
    unlimited,
    ratio,
    atLimit: !unlimited && used >= limit,
    // Warning only below the limit: once you are AT it, "running low" is the wrong tense and the
    // stronger at-limit message takes over.
    nearLimit: !unlimited && ratio !== null && ratio >= WARN_AT && used < limit,
    // How many more may be created. Null when unlimited — deliberately not a large number, which a
    // caller would render as a cap. Never negative: being over a limit means no room, not negative
    // room, and an account CAN be over one (the free member limit dropped from 3 to 1 beneath
    // accounts that already had more).
    //
    // Exists for bulk actions, which have to size a whole batch before sending it. `atLimit` alone
    // answers "may I add one more", which is the wrong question when the answer needs to be "27
    // needed, 40 available".
    remaining: unlimited ? null : Math.max(0, limit - used),
  }
}

/**
 * Badge tone for a resource, matching the vocabulary in `provenance.js`.
 *
 * <p>Unlimited is neutral rather than positive: "unlimited" is a fact about the plan, not good
 * news about this account, and a page of green ticks devalues the one tone that should mean
 * something.
 */
export function usageTone(usage) {
  if (usage.atLimit) {
    return 'danger'
  }
  if (usage.nearLimit) {
    return 'warning'
  }
  return 'neutral'
}

/** "5 of 25" / "5 · unlimited". Unlimited never renders a denominator that looks like a cap. */
export function formatUsage(usage) {
  return usage.unlimited ? `${usage.used} · unlimited` : `${usage.used} of ${usage.limit}`
}

/**
 * The one-line summary for a resource, or null when there is nothing worth saying.
 *
 * <p>Returns null well below the threshold on purpose. A plan panel that comments on every row is
 * noise, and noise is what makes the row that matters invisible.
 */
export function usageMessage(usage, plan) {
  const { nextTier } = describePlan(plan)
  const upgrade = nextTier ? ` Upgrade to ${nextTier} for more.` : ''
  const noun = countNoun(usage.limit, usage.label)

  if (usage.atLimit) {
    // Says explicitly that nothing is lost. The fear on hitting a cap is that something gets
    // deleted; the server guarantees it does not, and the UI should say so in the same breath.
    //
    // "for more" rather than "to add more", because the sentence already ends with "cannot add
    // more" — saying it twice in one breath reads as a template rather than a sentence.
    //
    // Over-limit is reachable without any downgrade: a limit introduced above accounts that
    // already exceed it, which is exactly what shipping M2.3 did to two live accounts. Those
    // people must not be told they have "used them all" when they are past the cap.
    const spent = usage.used > usage.limit
      ? `you have ${usage.used}`
      : `you have used ${usage.limit === 1 ? 'it' : 'them all'}`
    return `Your plan includes ${usage.limit} ${noun} and ${spent}. Existing ${usage.label} are unaffected — you just cannot add more.${upgrade}`
  }
  if (usage.nearLimit) {
    return `${usage.remaining} of ${usage.limit} ${usage.label} left.${upgrade}`
  }
  return null
}

/**
 * Singularises a label when the count is 1.
 *
 * <p>"all 1 brands" is the kind of sentence that makes a product feel unfinished, and the free
 * tier's brand limit is exactly 1 — so this is the common case, not an edge case. The server sends
 * only plural labels, so the trailing "s" is stripped here rather than adding a second field to
 * every payload.
 */
function countNoun(limit, label) {
  if (limit !== 1 || !label) {
    return label
  }
  return label.endsWith('s') ? label.slice(0, -1) : label
}

/** Resources at or near their limit, most urgent first — what a banner should mention. */
export function pressuredResources(usage = []) {
  return usage
    .map(describeUsage)
    .filter((row) => row.atLimit || row.nearLimit)
    .sort((a, b) => Number(b.atLimit) - Number(a.atLimit) || (b.ratio ?? 0) - (a.ratio ?? 0))
}

/**
 * What a subscription's state means for the person looking at it (M2.1/M2.2).
 *
 * <p>Derived here rather than in the component so it can be tested, and so the words shown for a
 * given state cannot drift between the two places that show them.
 *
 * <p><b>`canManage` comes from the server</b>, not from a role check done here. Viewing needs
 * `account:billing:read` (owner and admin) while changing needs `account:billing` (owner only) —
 * re-deriving that in the UI would create a second copy of an authorization rule that could
 * disagree with the real one.
 */
export function describeSubscription(payload) {
  const subscription = payload?.subscription || null
  const canManage = Boolean(payload?.canManage)

  if (!payload?.subscribed || !subscription) {
    return {
      subscribed: false,
      canManage,
      status: null,
      statusLabel: 'Free',
      plan: 'free',
      tone: 'neutral',
      // A free account is not a broken one. The message says what it is, not what is missing.
      summary: 'You are on the free plan. Upgrade when you need more room.',
      canPause: false,
      canResume: false,
      canCancel: false,
      endsAt: null,
      chargesMoney: false,
    }
  }

  const status = String(subscription.status || '').toLowerCase()
  const endingSoon = Boolean(subscription.cancelAtPeriodEnd)

  return {
    subscribed: true,
    canManage,
    status,
    statusLabel: subscription.statusLabel || status,
    plan: subscription.plan || 'free',
    effectivePlan: subscription.effectivePlan || subscription.plan,
    tone: subscriptionTone(status, endingSoon),
    summary: subscriptionSummary(subscription, status, endingSoon),
    // Server-computed: the UI must not decide for itself what the lifecycle allows.
    canPause: Boolean(subscription.canPause),
    canResume: Boolean(subscription.canResume),
    canCancel: Boolean(subscription.canCancel),
    cancelAtPeriodEnd: endingSoon,
    endsAt: subscription.currentPeriodEnd || null,
    // False whenever no real payment provider handled this. Nothing may present an unpaid
    // subscription as a paid one — the same rule the server-side provider enforces.
    chargesMoney: Boolean(subscription.chargesMoney),
    providerName: subscription.providerName || null,
  }
}

function subscriptionTone(status, endingSoon) {
  if (status === 'past_due') {
    return 'danger'
  }
  // Warning rather than danger: a scheduled cancellation is a decision the user made, not a
  // failure. It still needs to be visible, because it is easy to forget and reversible until it
  // takes effect.
  if (status === 'paused' || endingSoon) {
    return 'warning'
  }
  if (status === 'cancelled') {
    return 'neutral'
  }
  return 'success'
}

function subscriptionSummary(subscription, status, endingSoon) {
  const ends = formatDate(subscription.currentPeriodEnd)

  if (status === 'past_due') {
    // Says the account still works. The fear on a failed payment is that everything stops now.
    return `A payment did not go through. Your plan is still active while it is retried — update the payment method to avoid interruption.`
  }
  if (status === 'paused') {
    return 'Billing is paused. Your data is untouched and free-plan limits apply until you resume.'
  }
  if (status === 'cancelled') {
    return 'This subscription has ended. Nothing was deleted — subscribe again whenever you like.'
  }
  if (endingSoon) {
    // The point of cancel-at-period-end: the user sees exactly what they keep and until when.
    return ends
      ? `Cancelled. You keep full access until ${ends}, then move to the free plan. Nothing is deleted.`
      : 'Cancelled. You keep full access until the end of the paid period, then move to the free plan.'
  }
  if (status === 'trialing') {
    return 'Your trial is running. Nothing has been charged yet.'
  }
  return ends ? `Active. Renews ${ends}.` : 'Active.'
}

/** Short, unambiguous date. Locale-formatted so it is not read as the wrong month. */
export function formatDate(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

/** Minor units to a readable amount. Integer cents in, never a float — see the invoice entity. */
export function formatAmount(amountCents, currency = 'USD') {
  const cents = Number(amountCents ?? 0)
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(cents / 100)
  } catch {
    // An unknown currency code must not blank the amount out.
    return `${(cents / 100).toFixed(2)} ${currency}`
  }
}

/**
 * The public tier table, for signed-out marketing copy.
 *
 * <p><b>These numbers must match `PlanPolicy` in the BFF.</b> They are duplicated here because the
 * landing page is signed out and has no account to ask — there is no token, so `/api/brands/plan`
 * is unreachable. Advertising a limit the server does not enforce is the failure this comment
 * exists to prevent: if `PlanPolicy` changes, change these, and `plan.test.mjs` asserts the shape
 * so a half-edit is caught.
 *
 * <p><b>No prices.</b> None has been decided, and inventing one on a public page would be a
 * commitment made by a UI file. `free` is stated plainly because it is true and enforced today;
 * the paid tiers say what they lift, not what they cost.
 */
/**
 * Whether paid plans may be advertised to signed-out visitors.
 *
 * <p><b>Off until Stripe is live.</b> Showing Pro and Agency on a public page is an offer, and an
 * offer nobody can accept is worse than no offer: a visitor who wants to pay finds no way to, and
 * a visitor who signs up expecting those limits gets the free ones. While billing runs on
 * `ManualBillingProvider` nothing can actually take money, so the paid tiers stay hidden.
 *
 * <p>Build-time rather than a server read: the landing page is signed out and has no token, so it
 * cannot ask the BFF which billing provider is configured. Set `VITE_BILLING_LIVE=true` in the
 * same deploy that sets `WEBE_BILLING_PROVIDER=stripe`.
 *
 * <p>This hides the marketing copy only. It is not a security control — enforcement is
 * `PlanPolicy` and `ACCOUNT_BILLING` on the server, which do not consult it.
 */
export const BILLING_LIVE = import.meta?.env?.VITE_BILLING_LIVE === 'true'

/**
 * Tiers to show a signed-out visitor.
 *
 * <p><b>None, while pricing is undecided (2026-09).</b> The product is being taken to brands and
 * agencies to build a case study before there is a price, so the honest answer to "what does this
 * cost?" is that it has not been set — and a tier table is a worse answer than no tier table.
 *
 * <p>Previously this showed the free tier alone, which was honest when free was what the signup
 * button got you. It no longer is: the default plan is `agency` during this period, so a visitor
 * reading "25 creators" would be told a limit that will not be applied to them. A stated limit
 * that is wrong is worse than a missing section — the section can be asked about, the number just
 * misleads.
 *
 * <p>`BILLING_LIVE` still restores the full table in one flag, and PUBLIC_TIERS below is kept
 * intact rather than deleted: the copy was written carefully and the numbers track `PlanPolicy`.
 * When pricing is decided, set VITE_BILLING_LIVE=true and the table returns.
 */
export function visiblePublicTiers(billingLive = BILLING_LIVE) {
  return billingLive ? PUBLIC_TIERS : []
}

export const PUBLIC_TIERS = Object.freeze([
  Object.freeze({
    key: 'free',
    label: 'Free',
    tagline: 'One person, running a real program.',
    highlights: Object.freeze([
      'Just you — a single login',
      '1 brand workspace',
      '25 creators',
      '3 landing pages',
      // Stated as a benefit with its ceiling attached, not as a restriction. Twenty is more than
      // authoring a campaign takes, so the number reassures rather than warns -- and a visitor who
      // reads "AI drafts" with no figure assumes it is either unlimited or a trial, and both of
      // those are worse to discover later. Must track PlanPolicy.FREE's allowance in the BFF.
      '20 AI page drafts a month',
    ]),
    // Says what free IS rather than what it withholds. "Single user" is a shape, not a
    // restriction, and the tier reads better as the honest answer to "can I try this alone?"
    // than as a list of things removed.
    note: 'No card required, and no time limit — free is capped by size, not by a clock. '
      + 'Add your team whenever you are ready.',
  }),
  Object.freeze({
    key: 'pro',
    label: 'Pro',
    tagline: 'For when it stops being a one-person job.',
    highlights: Object.freeze([
      'Up to 10 team members',
      'Roles and permissions for each of them',
      '250 creators',
      '25 landing pages',
      '500 AI page drafts a month',
    ]),
    note: 'Pricing is not published yet.',
  }),
  Object.freeze({
    key: 'agency',
    label: 'Agency',
    tagline: 'Many client brands, one login.',
    highlights: Object.freeze([
      'Unlimited brands',
      'Unlimited creators',
      'Unlimited team members',
      'Unlimited AI page drafts',
      'Per-brand data isolation',
    ]),
    note: 'Pricing is not published yet.',
  }),
])

/**
 * The screen-reader description of one usage row.
 *
 * <p>The row renders as a label plus a Badge reading "3 of 25". Sighted users get the ratio and
 * the tone colour together; a screen reader got the bare text and no indication that it was a
 * meter at all. `role="progressbar"` with explicit bounds is what turns it back into one.
 *
 * <p>Returns `null` for an unlimited resource on purpose. A meter needs a maximum, and there is
 * no honest value for one here — `aria-valuemax` on an unbounded plan would invent a ceiling the
 * account does not have. Unlimited rows stay plain text, which is already accurate.
 *
 * <p>`aria-valuenow` is clamped to the maximum. An account CAN sit above its limit (the free
 * member cap dropped from 3 to 1 beneath accounts that already had more), and a valuenow past
 * valuemax is invalid ARIA that assistive tech reports unpredictably. The visible "3 of 1" still
 * tells the true story; `aria-valuetext` carries it into the accessible name.
 */
export function usageMeterAria(usage) {
  if (!usage || usage.unlimited) {
    return null
  }

  const limit = Number(usage.limit)
  if (!Number.isFinite(limit) || limit <= 0) {
    return null
  }

  const used = Number(usage.used) || 0

  return {
    role: 'progressbar',
    'aria-valuemin': 0,
    'aria-valuemax': limit,
    'aria-valuenow': Math.max(0, Math.min(used, limit)),
    'aria-valuetext': `${used} of ${limit} ${usage.label} used`,
  }
}
