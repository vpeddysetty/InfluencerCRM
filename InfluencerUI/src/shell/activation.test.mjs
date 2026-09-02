import test from 'node:test'
import assert from 'node:assert/strict'

import { activationState, shouldShowActivation, ACTIVATION_STEPS } from './activation.js'

/**
 * The checklist that tells a new workspace what to do first (roadmap PR-02).
 *
 * <p>What is worth pinning is not the arithmetic but the JUDGEMENT: that a draft page does not count
 * as a published one, that someone who did step two before step one is still sent back for step one,
 * and that the thing disappears once it stops being useful. Each of those is a decision that reads
 * as a bug when it goes the other way.
 */

test('a brand-new workspace has nothing done and is pointed at the creator step', () => {
  const state = activationState({})

  assert.equal(state.done, 0)
  assert.equal(state.complete, false)
  assert.equal(state.next.id, 'creator')
})

test('the creator comes before the store, because the store depends on someone else', () => {
  // The order is the opinion. A setup flow that opens with "connect your store" strands people on
  // day one behind an integration that cannot pay off until a code exists.
  const ids = ACTIVATION_STEPS.map((s) => s.id)

  assert.ok(ids.indexOf('creator') < ids.indexOf('coupon'))
  assert.ok(ids.indexOf('campaign') < ids.indexOf('coupon'))
  assert.ok(ids.indexOf('coupon') < ids.indexOf('page'))
  assert.equal(ids[ids.length - 1], 'store')
})

test('a DRAFT page does not tick the page step', () => {
  // The point of the step is a URL a creator can share. Ticking it for a draft tells someone they
  // finished something they did not.
  const state = activationState({ pages: [{ status: 'draft' }] })

  assert.equal(state.steps.find((s) => s.id === 'page').done, false)
})

test('a published page does', () => {
  const state = activationState({ pages: [{ status: 'published' }] })

  assert.equal(state.steps.find((s) => s.id === 'page').done, true)
})

test('status matching is case-insensitive, because the API is not the only writer', () => {
  const state = activationState({ pages: [{ status: 'Published' }] })

  assert.equal(state.steps.find((s) => s.id === 'page').done, true)
})

test('a coupon carrying a publicSlug also ticks the page step', () => {
  // The workflow board -- where a new signup lands -- holds coupons, not landing templates. A
  // coupon gains a publicSlug when its page is published, so reading that is the honest signal
  // there; requiring the template shape would leave the step permanently unticked on the one
  // screen the checklist actually appears on.
  const state = activationState({ coupons: [{ id: 'c1', publicSlug: 'c-abc123' }] })

  assert.equal(state.steps.find((s) => s.id === 'page').done, true)
})

test('a coupon with no page does not tick it', () => {
  const state = activationState({ coupons: [{ id: 'c1' }] })

  assert.equal(state.steps.find((s) => s.id === 'page').done, false)
})

test('someone who did step two first is still sent back for step one', () => {
  // `next` is the first INCOMPLETE step, not the one after the last completed. A list of
  // dependencies is not a wizard, and a user can legitimately be "ahead" of it.
  const state = activationState({ campaigns: [{ id: 'c1' }] })

  assert.equal(state.next.id, 'creator')
  assert.equal(state.done, 1)
})

test('absent lists read as "not yet", never as "no"', () => {
  // The shell loads these asynchronously. A checklist that flickered "nothing done" on every
  // refresh would be worse than one that appears a moment late.
  const state = activationState({ creators: undefined, campaigns: null })

  assert.equal(state.done, 0)
  assert.equal(state.steps.length, ACTIVATION_STEPS.length)
})

test('everything done means complete, and nothing left to point at', () => {
  const state = activationState({
    creators: [{}], campaigns: [{}], coupons: [{}],
    pages: [{ status: 'published' }], stores: [{}],
  })

  assert.equal(state.complete, true)
  assert.equal(state.next, null)
})

test('the checklist hides once complete', () => {
  const state = activationState({
    creators: [{}], campaigns: [{}], coupons: [{}],
    pages: [{ status: 'published' }], stores: [{}],
  })

  assert.equal(shouldShowActivation(state), false)
})

test('and hides for a workspace with real revenue even if a step was skipped', () => {
  // A brand with attributed sales evidently connected a store. Telling them to do it is the kind of
  // stale guidance that teaches people to stop reading the dashboard.
  const state = activationState({ creators: [{}], campaigns: [{}], coupons: [{}] })

  assert.equal(shouldShowActivation(state, { hasRevenue: true }), false)
  assert.equal(shouldShowActivation(state, { hasRevenue: false }), true)
})

test('every step names a route and an action, or it cannot be acted on', () => {
  for (const step of ACTIVATION_STEPS) {
    assert.ok(step.route.startsWith('/'), `${step.id} needs a route`)
    assert.ok(step.cta.length > 0, `${step.id} needs a call to action`)
    // The `why` is not decoration: a checklist that says what to do without saying what it buys
    // reads as busywork, which is how a new user decides the product is not for them.
    assert.ok(step.why.length > 20, `${step.id} needs to say what the step buys`)
  }
})
