/**
 * What a new workspace still has to do before the product can show it anything (roadmap PR-02).
 *
 * <p><b>The problem.</b> Signing up produces a correct, complete and entirely empty workspace. Every
 * page has a considered empty state — `EmptyState` is used well throughout — but each one answers
 * "why is this list empty?" in isolation. None of them answers the question a new user actually has,
 * which is *what do I do first, and in what order*. Against a free incumbent that gap is the whole
 * product: someone who does not reach a first attributed sale has no reason to come back, and
 * nothing today walks them there.
 *
 * <p><b>Plain `.js`, deliberately.</b> This is the logic that decides what a brand is told to do
 * next, and getting the ORDER wrong is worse than not guiding at all — "connect your store" before
 * "add a creator" sends someone to an integration that cannot pay off yet. The repo's test runner is
 * bare `node --test` with no JSX loader, so logic worth asserting has to sit in a module Node can
 * import. Same reasoning as `dateRange.js`, and the same reason that file's timezone bug was
 * catchable.
 *
 * <p><b>Derived from data the shell already holds, never stored.</b> There is no `onboarding_state`
 * column and deliberately so: a persisted checklist can disagree with reality — ticked while the
 * creator it refers to was deleted — and then it is a liar on the most trusted screen a new user
 * sees. Recomputing from creators/campaigns/coupons/pages costs nothing and cannot drift.
 */

/**
 * The order is the opinion, and it is the one thing here worth arguing about.
 *
 * <p>Creator first, because a creator is the only step with standalone value: a roster is useful
 * before anything else exists. Campaign second, because a coupon needs one to belong to. Coupon
 * third, because it is the attribution primitive — `PR-39`'s publish-readiness advisory warns that a
 * page without one attributes no sales. Page fourth, because it is the thing a creator shares and
 * it wants the coupon to already exist. The store last: it is the only step that depends on someone
 * else's system, and putting it first is how a setup flow strands people on day one.
 */
export const ACTIVATION_STEPS = Object.freeze([
  {
    id: 'creator',
    label: 'Add your first creator',
    why: 'Everything else hangs off a creator — codes, pages and the revenue they drive.',
    route: '/creators',
    cta: 'Add a creator',
  },
  {
    id: 'campaign',
    label: 'Create a campaign',
    why: 'A campaign is what a discount code and a landing page belong to.',
    route: '/campaigns',
    cta: 'Create a campaign',
  },
  {
    id: 'coupon',
    label: 'Give a creator a discount code',
    why: 'The code is what turns an order into a sale credited to that creator.',
    route: '/coupons',
    cta: 'Create a code',
  },
  {
    id: 'page',
    label: 'Publish a landing page',
    why: 'The page is what the creator shares. It is personalised per code automatically.',
    route: '/content',
    cta: 'Build a page',
  },
  {
    id: 'store',
    label: 'Connect your store',
    why: 'Orders flow in against the codes, and the dashboard starts showing what each partnership earned.',
    route: '/marketplace',
    cta: 'Connect a store',
  },
])

const count = (value) => (Array.isArray(value) ? value.length : 0)

/**
 * Which steps are done, and which one to point at next.
 *
 * <p>Every input is optional and defaults to empty: the shell loads these lists asynchronously, and
 * a checklist that flickered "nothing done" on every refresh would be worse than one that appears a
 * moment late. An absent list reads as "not yet", never as "no".
 *
 * @returns {{steps: Array, done: number, total: number, complete: boolean, next: object|null}}
 */
export function activationState({ creators, campaigns, coupons, pages, stores } = {}) {
  const completion = {
    creator: count(creators) > 0,
    campaign: count(campaigns) > 0,
    coupon: count(coupons) > 0,
    // A page counts only once PUBLISHED. A draft is not a thing a creator can share, and ticking it
    // would tell someone they had finished a step whose whole point is the public URL.
    //
    // Two shapes are accepted because the two screens that render this hold different data. The
    // dashboard has landing templates with a `status`; the workflow board -- which is where a new
    // signup lands -- has only coupons, and a coupon gains a `publicSlug` when its page is
    // published (`ContentPage` filters on exactly that). Reading either is honest; adding a fetch
    // to the landing screen to normalise them would cost a request on every visit to tick a box.
    page: (Array.isArray(pages) ? pages : []).some(
      (p) => String(p?.status || '').toLowerCase() === 'published',
    ) || (Array.isArray(coupons) ? coupons : []).some((c) => Boolean(c?.publicSlug)),
    store: count(stores) > 0,
  }

  const steps = ACTIVATION_STEPS.map((step) => ({ ...step, done: completion[step.id] === true }))
  const done = steps.filter((s) => s.done).length

  return {
    steps,
    done,
    total: steps.length,
    complete: done === steps.length,
    // The first INCOMPLETE step, not the first after the last completed one: someone who created a
    // campaign before adding a creator should still be sent back for the creator rather than being
    // marched past it. The list is an order of dependency, not a wizard they can be ahead of.
    next: steps.find((s) => !s.done) || null,
  }
}

/**
 * Whether to show the checklist at all.
 *
 * <p>Hidden once complete, and hidden for a workspace that is clearly past this stage even if a step
 * was skipped — a brand with real attributed revenue does not need to be told to connect a store it
 * evidently connected. Guidance that outstays its usefulness is read as clutter, and clutter on the
 * dashboard is what makes people stop reading the dashboard.
 */
export function shouldShowActivation(state, { hasRevenue = false } = {}) {
  if (!state || state.complete) return false
  return !hasRevenue
}
