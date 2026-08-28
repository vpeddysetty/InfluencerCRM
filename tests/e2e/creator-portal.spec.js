import { test, expect } from '@playwright/test'

/**
 * The creator's half of the co-authoring loop (roadmap PR-43, PR-44).
 *
 * **This suite talks to the PORTAL, not the shell.** Every other spec here drives
 * `InfluencerUI` on 5173 with an operator session; the creator portal is a standalone Vite
 * project on 5180 that authenticates with an opaque `X-Creator-Token` and talks to the BFF
 * directly. `baseURL` from the shared config is therefore wrong for this file and is overridden
 * below — a creator has no account in the shell at all.
 *
 * **It needs seeded state that no UI can create**, which is why it skips rather than fails when
 * that state is absent. A creator cannot invite themselves: the chain is a confirmed
 * `creator_identity_link` plus a `landing_page_collaborators` grant, both written by a brand.
 * Set `E2E_CREATOR_EMAIL` / `E2E_CREATOR_PASSWORD` for an identity holding an edit grant on a
 * page whose `turn` is `creator`.
 *
 * The assertions are the four things that were actually broken or absent when this was built,
 * rather than a walk through the happy path for its own sake:
 *
 *   1. The brand is NAMED. `pagesForCreator` returned only a `brandId`, so every creator-facing
 *      screen said "the brand" — worst of all the button that sends the page back, which is the
 *      one place a creator working with several brands has to know which one.
 *   2. The shared `SectionEditor` actually renders. It is imported from `@influencer/ui` and
 *      styles itself with the brand shell's class names, none of which this portal defined; the
 *      failure mode is a working editor with no styling, and OP-19 was the same component
 *      rendering nothing at all in production.
 *   3. A creator cannot publish. No status control, and a save must leave status and stage alone.
 *   4. Send-back moves the TURN and not the stage, and the page leaves "Waiting on you".
 */

const PORTAL = process.env.E2E_PORTAL_URL || 'http://localhost:5180'
const EMAIL = process.env.E2E_CREATOR_EMAIL
const PASSWORD = process.env.E2E_CREATOR_PASSWORD

test.describe('creator portal', () => {
  test.skip(!EMAIL || !PASSWORD,
    'Set E2E_CREATOR_EMAIL and E2E_CREATOR_PASSWORD for a creator holding an edit grant.')

  // Not `use({ baseURL })` at describe level only — these are absolute so a stray relative
  // navigation cannot silently fall back to the shell's origin and "pass" against the wrong app.
  test('a creator opens a page, edits it, and sends it back', async ({ page }) => {
    // The preview iframe is sandboxed WITHOUT allow-scripts, so every <script> in the rendered
    // page is refused and Chrome logs one error per refusal. That is the sandbox doing its job on
    // server-rendered HTML the creator is editing, not a fault -- so it is filtered out by name
    // rather than by loosening the assertion, which would also swallow real errors.
    const SANDBOXED_SCRIPT = /Blocked script execution in 'about:srcdoc'/
    const consoleErrors = []
    const note = (text) => { if (!SANDBOXED_SCRIPT.test(text)) consoleErrors.push(text) }
    page.on('console', (m) => { if (m.type() === 'error') note(m.text()) })
    page.on('pageerror', (e) => note(`pageerror: ${e.message}`))

    await page.goto(PORTAL, { waitUntil: 'networkidle' })

    await page.fill('input[type=email]', EMAIL)
    await page.fill('input[type=password]', PASSWORD)
    await page.locator('button[type=submit]').first().click()
    await expect(page.getByRole('heading', { name: 'Your pages' })).toBeVisible()

    const card = page.locator('.cp-card').first()
    await expect(card).toBeVisible()
    await card.click()

    // (1) The brand is named, not "the brand".
    const brand = (await page.locator('.cp-eyebrow').first().textContent())?.trim()
    expect(brand, 'the editor must name the brand').toBeTruthy()
    expect(brand?.toLowerCase()).not.toBe('the brand')

    // (2) The shared editor mounted and is usable, not merely present.
    await expect(page.getByRole('button', { name: /save page/i })).toBeVisible()
    await expect(page.locator('iframe')).toHaveCount(1)

    // The preview is the REAL renderer, server-side. An empty frame is the OP-19 failure and the
    // stale-closure one both — it is the assertion that catches the editor silently doing nothing.
    const frame = page.frames().find((f) => f !== page.mainFrame())
    expect(frame, 'the preview iframe must be attached').toBeTruthy()
    await expect.poll(
      async () => (await frame.locator('body').innerHTML().catch(() => '')).length,
      { message: 'the server-rendered preview must not be blank', timeout: 20_000 },
    ).toBeGreaterThan(0)

    // (3) No publish control anywhere on the creator's screen.
    await expect(page.locator('select').filter({ hasText: 'Published' })).toHaveCount(0)

    // Edit and save, then confirm the SERVER accepted it rather than trusting the toast.
    const stamp = `Edited by the creator ${Date.now()}`
    const headline = page.getByLabel('Headline').first()
    await headline.fill(stamp)
    await page.getByRole('button', { name: /save page/i }).click()
    await expect(page.locator('.cp-success')).toContainText(/saved/i)
    await expect.poll(
      async () => (await frame.locator('body').innerHTML().catch(() => '')).includes(stamp),
      { message: 'the re-rendered preview must show what was saved', timeout: 20_000 },
    ).toBe(true)

    // (4) Send it back. The confirmation names the brand for the same reason the header does.
    await page.locator('#cp-handback-note').fill('Rewrote the intro in my own words.')
    await page.getByRole('button', { name: /send back to/i }).click()
    await expect(page.getByRole('heading', { name: new RegExp(`sent back to ${brand}`, 'i') }))
      .toBeVisible()

    // Back on the list the page has moved out of "Waiting on you" — the turn changed, which is
    // the only user-visible proof that hand-back did what it claims.
    await page.getByRole('button', { name: /back to your pages/i }).click()
    await expect(page.getByRole('heading', { name: 'Waiting on you' })).toHaveCount(0)

    expect(consoleErrors, 'the editor must run clean').toEqual([])
  })
})
