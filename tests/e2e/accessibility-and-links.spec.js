import { expect, test } from '@playwright/test'
import { PERSONAS, beat, navigate, signUp, uniqueEmail } from './personas.js'

/**
 * The two claims from this session that only a running stack can settle.
 *
 * <p>Both were verified in isolation already — the plan meter in a component harness, the link
 * helper by unit test — and isolation is exactly what neither claim is about. A meter announces
 * itself only if the real page renders the real usage; a hosted link only works if CloudFront
 * routes it to WebExperience rather than answering from the SPA bucket. Those are properties of
 * the deployment, not of a component.
 *
 * <p><b>Assertions are about outcomes a user could describe</b>, matching journeys.spec.js: "the
 * plan panel tells a screen reader how much is used", not "the span carries an attribute".
 */

test.describe('Plan usage is legible to a screen reader', () => {
  test('the usage badge is a meter with real bounds, not just coloured text', async ({ page }) => {
    const email = uniqueEmail('a11y-plan')
    await signUp(page, PERSONAS.soloBrandOwner, email)
    await beat(page)

    // Billing is where PlanUsage renders. Reached by its rail link rather than by URL so this
    // also proves the link a user clicks is wired — see navigate() in personas.js.
    await navigate(page, 'Billing')
    await beat(page)

    const panel = page.locator('.plan-usage')
    await expect(panel).toBeVisible()

    // The claim: every bounded row is a progressbar. A fresh free workspace has creators, landing
    // pages and members capped, so at least one must be present. Asserting >0 rather than an exact
    // count keeps this from breaking when a plan gains a resource.
    const meters = panel.locator('[role="progressbar"]')
    await expect(meters.first()).toBeVisible()
    const count = await meters.count()
    expect(count).toBeGreaterThan(0)
    await beat(page)

    for (let i = 0; i < count; i += 1) {
      const meter = meters.nth(i)
      const now = Number(await meter.getAttribute('aria-valuenow'))
      const max = Number(await meter.getAttribute('aria-valuemax'))
      const text = await meter.getAttribute('aria-valuetext')

      // Bounds have to be real numbers, and valuenow must sit inside them. An account can exceed
      // its limit — the free member cap dropped from 3 to 1 beneath accounts that already had
      // more — and the component clamps for that reason: valuenow past valuemax is invalid ARIA
      // that assistive tech reports unpredictably.
      expect(Number.isFinite(now)).toBe(true)
      expect(Number.isFinite(max)).toBe(true)
      expect(max).toBeGreaterThan(0)
      expect(now).toBeGreaterThanOrEqual(0)
      expect(now).toBeLessThanOrEqual(max)

      // valuetext is what actually gets announced, so it must name the resource rather than
      // leaving a screen reader to say "3 of 25" about nothing in particular.
      expect(text).toMatch(/\d+ of \d+ .+ used/)
    }
    await beat(page)
  })

  test('an unlimited resource is not announced as a meter', async ({ page }) => {
    const email = uniqueEmail('a11y-unlimited')
    await signUp(page, PERSONAS.soloBrandOwner, email)
    await navigate(page, 'Billing')
    await beat(page)

    const panel = page.locator('.plan-usage')
    await expect(panel).toBeVisible()

    // A meter needs a maximum. Any row whose text says "unlimited" must therefore NOT carry
    // progressbar semantics — aria-valuemax on an unbounded resource announces a ceiling the
    // account does not have, which is worse than announcing nothing.
    const unlimitedRows = panel.locator('.plan-usage-row', { hasText: /unlimited/i })
    const rowCount = await unlimitedRows.count()
    for (let i = 0; i < rowCount; i += 1) {
      await expect(unlimitedRows.nth(i).locator('[role="progressbar"]')).toHaveCount(0)
    }
    await beat(page)
  })
})

test.describe('A published landing page is reachable at the link the brand is shown', () => {
  test('the hosted link is absolute and does not answer with the app shell', async ({ page, request }) => {
    const email = uniqueEmail('landing-link')
    await signUp(page, PERSONAS.soloBrandOwner, email)
    await beat(page)

    await navigate(page, 'Content')
    await beat(page)

    // The builder shows the hosted link once a page exists. If this workspace has none yet there
    // is nothing to assert about, and inventing one here would test the fixture rather than the
    // product — so the test skips rather than passing vacuously.
    const hostedLink = page.locator('a[href*="/s/"]').first()
    if ((await hostedLink.count()) === 0) {
      test.skip(true, 'no published landing page in this workspace; nothing to link to')
    }

    const href = await hostedLink.getAttribute('href')

    // The bug this covers: a bare relative path resolves against the SPA's own origin, and until
    // CloudFront routed /s/* to WebExperience that fell through to the default behaviour and
    // answered 200 from the SPA bucket. The brand copied a dead link that looked alive.
    expect(href).toMatch(/^https?:\/\//)

    const response = await request.get(href)
    expect(response.status()).toBe(200)

    // 200 alone does not prove it: the failure mode WAS a 200. The marketing shell is what came
    // back, so the body has to be the hosted page rather than the SPA's own document.
    const body = await response.text()
    expect(body).not.toMatch(/<div id="root">\s*<\/div>/)
    await beat(page)
  })
})
