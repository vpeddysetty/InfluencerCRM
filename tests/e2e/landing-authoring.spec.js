import { expect, test } from '@playwright/test'
import { PERSONAS, beat, signUp, uniqueEmail } from './personas.js'

/**
 * The whole job, recorded: a new brand goes from an empty workspace to a landing page authored in
 * the curated section editor (roadmap PR-39).
 *
 * <p><b>Why one long test rather than four short ones.</b> The other specs here are deliberately
 * one-job-each, so a failure names the job that broke. This one is the exception on purpose: the
 * deliverable is a video someone watches to understand what the product does, and four separate
 * recordings of four separate signups do not tell that story.
 *
 * <p><b>Selectors are tolerant, assertions are not.</b> The creator and campaign forms have
 * changed shape before, and pinning every field would make this break on cosmetic edits — so the
 * setup steps fill whatever they find. The editor assertions are exact, because the editor is what
 * this journey exists to demonstrate: if the canvas does not show the words that were typed, this
 * fails.
 *
 * <p><b>It writes to whatever database it points at.</b> Against production that is a real account,
 * creator and campaign, named so they are obviously from a test run. That is the trade for
 * recording the real product rather than a mock.
 */
/** Click a nav-rail link by its exact label and wait for the route to settle. */
async function gotoSection(page, label) {
  // Dismiss anything covering the rail first. The creator form opens as an overlay, and a nav
  // link underneath it is present but never "stable", so the click retries until it times out —
  // which reads as a missing link rather than an obscured one.
  await page.keyboard.press('Escape').catch(() => {})
  const link = page.getByRole('link', { name: label, exact: true }).first()
  await link.scrollIntoViewIfNeeded().catch(() => {})
  await link.click({ timeout: 15_000 }).catch(async () => {
    // Last resort: the rail link is the only reliable route, so click it through the DOM rather
    // than failing the journey on an animation that never settles.
    await link.click({ force: true })
  })
  await page.waitForLoadState('networkidle')
}

test.describe('Brand owner — from empty workspace to an authored page', () => {
  test('adds a creator and a campaign, then authors the landing page', async ({ page }) => {
    test.setTimeout(300_000)

    // ---- 1. a brand signs up ------------------------------------------------
    const persona = PERSONAS.soloBrandOwner
    await signUp(page, persona, uniqueEmail('landing-demo'))
    await beat(page, 1_400)

    // ---- 2. bring in a creator ----------------------------------------------
    // Exact match, not navigate(): that helper matches on a substring, and "Content" also
    // matches the off-viewport "Skip to content" link — which retries until it times out.
    await gotoSection(page, 'Creators')
    await beat(page)

    const creatorHandle = `maya_${Date.now().toString(36)}`
    const addCreator = page.getByRole('button', { name: /add creator|new creator/i }).first()
    if (await addCreator.count()) {
      await addCreator.click()
      await beat(page)
      // Located by placeholder: these inputs are id'd, not name'd, and the ids are stable.
      const nameField = page.getByPlaceholder('Ari Rivera').first()
      if (await nameField.count()) await nameField.fill('Maya Okonjo')
      const handleField = page.getByPlaceholder('@aririvera').first()
      if (await handleField.count()) await handleField.fill(`@${creatorHandle}`)
      const creatorEmail = page.getByPlaceholder('ari@example.com').first()
      if (await creatorEmail.count()) await creatorEmail.fill(`${creatorHandle}@example.com`)
      await beat(page)
      const save = page.locator('form.drawer-form button[type=submit]').first()
      if (await save.count()) {
        await save.click()
        await page.waitForLoadState('networkidle')
        await beat(page, 1_400)
      }
    }
    await expect(page.locator('body')).not.toContainText(/Unable to load/i)
    await beat(page)

    // ---- 3. set up a campaign -----------------------------------------------
    await gotoSection(page, 'Campaigns')
    await beat(page)

    const campaignName = `Winter Trails ${Date.now().toString(36)}`
    await page.getByRole('button', { name: /new campaign/i }).first().click()
    await page.waitForSelector('#campaign-name', { timeout: 20_000 })
    await beat(page)
    await page.locator('#campaign-name').fill(campaignName)
    // The campaign TYPE pre-selects the matching page template later, so the two stay one
    // decision. Setting it here is the point, not incidental setup.
    const typeSelect = page.locator('#campaign-type, select[name="campaignType"]').first()
    if (await typeSelect.count()) await typeSelect.selectOption({ index: 1 }).catch(() => {})
    await beat(page)

    await page.locator('form.drawer-form button[type=submit]').first().click()
    await page.waitForLoadState('networkidle')
    await beat(page, 1_600)
    // The campaign must actually exist — the page editor has nothing to attach to otherwise.
    await expect(page.locator('body')).toContainText(campaignName, { timeout: 20_000 })
    await expect(page.locator('body')).not.toContainText(/Unable to load/i)
    await beat(page)

    // ---- 4. open the page editor --------------------------------------------
    await gotoSection(page, 'Content')
    await beat(page, 1_200)

    const campaignPicker = page.locator('select').first()
    const options = (await campaignPicker.locator('option').allTextContents()).filter(Boolean)
    const target = options.find((o) => o.includes(campaignName)) || options[1]
    expect(target, 'a campaign must exist to author a page for').toBeTruthy()
    await campaignPicker.selectOption({ label: target })

    // Scroll the editor into frame. The Content route stacks the brief form above the page
    // builder, so on a 720p viewport the canvas starts below the fold — and a video of the brief
    // form is not a video of the editor.
    await page.getByRole('heading', { name: /Landing page builder/i }).first()
      .scrollIntoViewIfNeeded().catch(() => {})
    await beat(page)

    // Start from a template. A campaign whose type has no matching template opens with an empty
    // page — correct behaviour, and the right moment to show the picker doing its job.
    // The picker is the one whose first option is the placeholder — identified by that rather
    // than by position, so adding a control above it does not silently retarget this.
    const templatePicker = page.locator('select').filter({ has: page.locator('option[value=""]:text-is("— choose —")') }).first()
    await templatePicker.scrollIntoViewIfNeeded().catch(() => {})
    await beat(page)
    await templatePicker.selectOption({ label: 'Product launch' })
    await beat(page, 1_600)

    // The canvas is what this journey is about, so it is waited for explicitly rather than slept
    // past.
    await page.waitForSelector('iframe[title="Page preview"]', { timeout: 60_000 })
    await page.locator('iframe[title="Page preview"]').scrollIntoViewIfNeeded().catch(() => {})
    await beat(page, 1_600)

    // The section list came from the template matching the campaign type — the brand never picked
    // a page shape as a second decision.
    await expect(page.getByText('Add a section')).toBeVisible()
    await beat(page, 1_200)

    // ---- 5. write the words -------------------------------------------------
    const headline = page.getByPlaceholder('What are you selling?')
    await headline.fill('The linen everyone keeps asking about')
    await beat(page, 1_200)

    const eyebrow = page.getByPlaceholder('Limited collection')
    if (await eyebrow.count()) {
      await eyebrow.fill('Winter 2026')
      await beat(page, 1_000)
    }

    // THE assertion of this journey: the preview is the real server renderer, so seeing the typed
    // words there proves they reached the page a visitor would get — not a client-side mock of it.
    const canvas = page.frameLocator('iframe[title="Page preview"]')
    await expect(canvas.locator('body')).toContainText('The linen everyone keeps asking about', {
      timeout: 60_000,
    })
    await beat(page, 1_600)

    // ---- 6. the four preview widths -----------------------------------------
    // Phone last and left on screen: most creator traffic lands there, and it is the width the
    // free-form builder made easy to forget.
    for (const width of ['Laptop', 'Tablet', 'Phone']) {
      const button = page.getByRole('button', { name: width, exact: true })
      if (await button.count()) {
        await button.click()
        await beat(page, 1_500)
      }
    }
    const desktop = page.getByRole('button', { name: 'Desktop', exact: true })
    if (await desktop.count()) {
      await desktop.click()
      await beat(page, 1_200)
    }

    // ---- 7. save -------------------------------------------------------------
    const savePage = page.getByRole('button', { name: /Save page/i })
    if (await savePage.count()) {
      await savePage.click()
      await page.waitForLoadState('networkidle')
      await beat(page, 2_000)
      // The product's claim in the user's own words: it saved, and the page has a public address.
      await expect(page.locator('body')).toContainText(/saved|slug/i)
    }
    await beat(page, 2_500)
  })
})
