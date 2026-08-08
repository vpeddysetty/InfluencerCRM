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
    const left = usage.limit - usage.used
    return `${left} of ${usage.limit} ${usage.label} left.${upgrade}`
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
export const PUBLIC_TIERS = Object.freeze([
  Object.freeze({
    key: 'free',
    label: 'Free',
    tagline: 'Run a real program, not a trial.',
    highlights: Object.freeze([
      '1 brand workspace',
      '25 creators',
      '3 team members',
      '3 landing pages',
    ]),
    // The honest version of a free tier: what makes it free is the ceiling, not a countdown.
    note: 'No card required, and no time limit — the free plan is capped by size, not by a clock.',
  }),
  Object.freeze({
    key: 'pro',
    label: 'Pro',
    tagline: 'For a program that outgrew the free ceiling.',
    highlights: Object.freeze([
      '250 creators',
      '10 team members',
      '25 landing pages',
      'Everything in Free',
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
      'Per-brand data isolation',
    ]),
    note: 'Pricing is not published yet.',
  }),
])
