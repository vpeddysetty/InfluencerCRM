import { expect, test } from '@playwright/test'
import { PERSONAS, beat, signUp, uniqueEmail } from './personas.js'

/**
 * A brand-new workspace is told what to do first (roadmap PR-02).
 *
 * <p>The checklist is the answer to the only question a new user has, and the thing most likely to
 * break it is invisible: it renders on `DEFAULT_ROUTE`, and production serves that route from the
 * WORKFLOW REMOTE rather than the shell. A copy that landed in one tree and not the other would
 * pass every unit test, build clean, and show a new user nothing — which is exactly how the section
 * editor shipped blank for two days.
 *
 * <p>So this asserts against the DEPLOYED page rather than the component: sign up, land where the
 * router sends you, and check the guidance is actually on screen.
 */
test('a new workspace lands on guidance, not an empty board', async ({ page }) => {
  const persona = PERSONAS.soloBrandOwner
  await signUp(page, persona, uniqueEmail('activation'))
  await beat(page, 2_000)

  // Wherever DEFAULT_ROUTE points, that is where a new user is. Asserting the checklist is here
  // rather than navigating to it is the point: a checklist on a page nobody opens is not guidance.
  const checklist = page.locator('.activation')
  await expect(checklist).toBeVisible({ timeout: 30_000 })

  await expect(checklist).toContainText('Get your first sale attributed')
  // Nothing done yet, and the count is the reassurance that setup is finite.
  await expect(checklist).toContainText('0 of 5 done')

  // The first step is the creator, because a roster is the only step with standalone value. If
  // this ever reads "Connect your store", the order regressed and a new user is being sent to an
  // integration that cannot pay off yet.
  await expect(checklist).toContainText('Add your first creator')
  await expect(checklist.getByRole('link', { name: 'Add a creator' })).toBeVisible()
})

test('the checklist points at the creator page, and that page loads', async ({ page }) => {
  // A call to action that 404s is worse than none: it is the first thing a new user clicks.
  const persona = PERSONAS.soloBrandOwner
  await signUp(page, persona, uniqueEmail('activation.cta'))
  await beat(page, 2_000)

  await page.locator('.activation').getByRole('link', { name: 'Add a creator' }).click()
  await beat(page, 3_000)

  await expect(page).toHaveURL(/\/creators/)
  await expect(page.locator('body')).not.toContainText(/Unable to load|Something went wrong/i)
})
