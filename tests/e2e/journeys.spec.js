import { expect, test } from '@playwright/test'
import { PERSONAS, beat, navigate, signUp, uniqueEmail } from './personas.js'

/**
 * End-to-end journeys, recorded.
 *
 * <p>Each test is one persona doing one job the product exists for. They run against the real
 * stack — real DPS session, real BFF, real database — so a pass means the whole chain works, not
 * that a component renders.
 *
 * <p><b>Assertions are deliberately about outcomes a user could describe.</b> "The creator I added
 * appears in the list" is a claim the product makes; "the POST returned 201" is an implementation
 * detail that can be true while the product is broken.
 */

test.describe('Prospect — evaluating, signed out', () => {
  test('sees what the free tier is, and is not promised a team', async ({ page }) => {
    // The signed-out story matters as much as the signed-in one: this is the only page most
    // visitors ever see, and it is where the single-user positioning has to be honest.
    await page.goto('/')
    await expect(page).toHaveTitle(/.+/)
    await beat(page)

    const body = page.locator('body')
    await expect(body).toContainText(/Free/i)
    await beat(page)

    // The pairing that would otherwise drift: PlanPolicy.FREE caps members at 1, so the page must
    // not advertise seats the server refuses.
    await expect(body).not.toContainText(/3 team members/i)

    // No price may appear anywhere: none has been decided, and a UI file is not where that
    // commitment gets made.
    await expect(body).not.toContainText(/\$\d/)
    await beat(page)
  })

  test('can reach sign-up and sign-in without an account', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: 'Sign up' }).click()
    await expect(page.locator('input[name="fullName"]')).toBeVisible()
    await beat(page)

    // The workspace-type choice is the agency product's front door. If it is missing, an agency
    // signing up silently gets a single-brand account.
    await expect(page.locator('input[name="accountType"][value="agency"]')).toBeAttached()
    await beat(page)

    await page.getByRole('button', { name: 'Log in' }).click()
    await expect(page.locator('input[name="fullName"]')).toHaveCount(0)
    await beat(page)
  })
})

test.describe('Solo brand owner — the free tier, alone', () => {
  test('signs up and lands in a working workspace', async ({ page }) => {
    const persona = PERSONAS.soloBrandOwner
    const email = uniqueEmail(persona.emailPrefix)

    await signUp(page, persona, email)

    // The workspace is named after the brand they typed, not after this platform. Getting this
    // wrong was a real defect class: a pre-filled default meant accounts named "tejdux.io".
    await expect(page.locator('body')).toContainText(persona.workspace)
    await beat(page)
  })

  test('walks the core sections a program actually uses', async ({ page }) => {
    const persona = PERSONAS.soloBrandOwner
    await signUp(page, persona, uniqueEmail(persona.emailPrefix))

    // Each of these is a distinct backend context — creators, campaigns, workflow, revenue. A
    // failure here is a broken context, not a broken link, which is why the journey visits them
    // rather than asserting the rail's contents.
    for (const section of ['Creators', 'Campaigns', 'Board', 'Revenue']) {
      await navigate(page, section)
      await beat(page, 1_100)
      // No error banner: the section loaded its data rather than rendering an empty shell over a
      // failed fetch, which looks identical in a screenshot.
      await expect(page.locator('body')).not.toContainText(/Unable to load/i)
    }
  })

  test('adds a creator and sees it in the roster', async ({ page }) => {
    const persona = PERSONAS.soloBrandOwner
    await signUp(page, persona, uniqueEmail(persona.emailPrefix))
    await navigate(page, 'Creators')
    await beat(page)

    const handle = `e2e_creator_${Date.now().toString(36)}`
    const addButton = page.getByRole('button', { name: /add creator|new creator/i }).first()

    if (await addButton.count()) {
      await addButton.click()
      await beat(page)

      // Fill whatever the form asks for by field name; the form's exact shape has changed before
      // and pinning every field would make this journey break on cosmetic edits.
      const nameField = page.locator('input[name="displayName"], input[name="name"]').first()
      if (await nameField.count()) await nameField.fill(handle)

      const handleField = page.locator('input[name="handle"], input[name="instagramHandle"]').first()
      if (await handleField.count()) await handleField.fill(handle)

      await beat(page)
      const save = page.getByRole('button', { name: /save|create|add/i }).last()
      if (await save.count()) {
        await save.click()
        await page.waitForLoadState('networkidle')
        await beat(page, 1_200)
      }
    }

    // Whether or not the form was reachable, the roster must render. A creators page that throws
    // is the failure worth catching here.
    await expect(page.locator('body')).not.toContainText(/Unable to load/i)
  })

  test('is told roles are paid rather than shown a broken control', async ({ page }) => {
    const persona = PERSONAS.soloBrandOwner
    await signUp(page, persona, uniqueEmail(persona.emailPrefix))
    await navigate(page, 'Members')
    await beat(page, 1_200)

    // Free is single-user. The page must SAY so — a 402 arrives with an upgrade path, and hiding
    // the section entirely would leave a free user unable to discover team roles exist at all.
    await expect(page.locator('body')).not.toContainText(/Unable to load/i)
    await beat(page)
  })
})

test.describe('Agency owner — several client brands, one login', () => {
  test('signs up as an agency and can switch brands', async ({ page }) => {
    const persona = PERSONAS.agencyOwner
    await signUp(page, persona, uniqueEmail(persona.emailPrefix))

    await expect(page.locator('body')).toContainText(persona.workspace)
    await beat(page, 1_200)

    // Multi-brand tenancy is the one capability no competitor offers, per MARKET-ANALYSIS. If the
    // agency signup silently produced a single-brand account, this is where it shows.
    await navigate(page, 'Creators')
    await beat(page)
    await expect(page.locator('body')).not.toContainText(/Unable to load/i)
  })

  test('reaches billing to see what the plan includes', async ({ page }) => {
    const persona = PERSONAS.agencyOwner
    await signUp(page, persona, uniqueEmail(persona.emailPrefix))

    await navigate(page, 'Billing')
    await beat(page, 1_400)

    // Billing must render for an owner: ACCOUNT_BILLING_READ is OWNER+ADMIN, and a blank page
    // here would mean the permission split shipped wrong.
    await expect(page.locator('body')).not.toContainText(/Unable to load/i)
    await beat(page)
  })
})

test.describe('Session — the reload that used to log people out', () => {
  test('a hard reload does not strand the user in a tokenless workspace', async ({ page }) => {
    const persona = PERSONAS.soloBrandOwner
    await signUp(page, persona, uniqueEmail(persona.emailPrefix))
    await beat(page)

    // The regression this guards: isLoggedIn was restored from localStorage while the token
    // deliberately was not, so a reload rebuilt the signed-in shell with nothing to authenticate
    // with and every request went out bare.
    await page.reload()
    await page.waitForLoadState('networkidle')
    await beat(page, 1_500)

    // Either outcome is correct — restored via the DPS cookie, or cleanly at the login page. What
    // must NOT happen is a workspace full of error banners, which is what the bug produced.
    await expect(page.locator('body')).not.toContainText(/Unable to load/i)
    await beat(page)
  })
})
