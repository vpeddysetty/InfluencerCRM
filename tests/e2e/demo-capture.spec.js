// The suite is ESM ("type": "module"); a require() here fails at collection and takes the whole
// run down with it, not just this spec.
import { expect, test } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), 'artifacts')

/**
 * Records the six beats of the product demo, in order (docs/Demo-Script-Collaborative-Drop.md).
 *
 * <p><b>This is a recording, not a test.</b> It asserts almost nothing on purpose: a failed
 * assertion mid-way leaves a truncated clip, and the deliverable here is footage rather than a
 * verdict. The suite next door is what proves the product works; this proves nothing and shows it.
 *
 * <p><b>Paced for narration, not for speed.</b> Every other spec in this directory races; this one
 * holds. The durations below are the seconds each beat gets in the script, and they exist so the
 * voiceover drops onto the footage without re-editing. A capture that runs faster than the words
 * is a capture somebody has to slow down by hand afterwards.
 *
 * <p><b>Runs against a SEEDED workspace</b> — `node tests/e2e/seed-demo-workspace.mjs` first, then
 * pass the credentials it prints. An empty workspace films an empty product, which is the one
 * thing the demo must not show.
 *
 * <p>Usage:
 * <pre>
 *   node tests/e2e/seed-demo-workspace.mjs
 *   DEMO_EMAIL=... DEMO_PASSWORD=... npx playwright test demo-capture.spec.js
 *   node tests/e2e/build-video.mjs
 * </pre>
 */

const EMAIL = process.env.DEMO_EMAIL
const PASSWORD = process.env.DEMO_PASSWORD
const BASE = process.env.E2E_BASE_URL || 'https://tejdux.com'

/**
 * Seconds per beat, from the script.
 *
 * <p>Held as data rather than inline sleeps so the narration manuscript and the capture cannot
 * drift: if a beat's voiceover runs long, this is the one place to change.
 */
// Measured from the rendered narration, not estimated -- demo-narrate.mjs prints the real
// durations and flags any beat that outruns its hold.
//
// These are MINIMUMS, not positions. The first version treated them as a timeline and assumed beat
// N started at the sum of the holds before it; page loads added 38.6 seconds of navigation the
// budget never accounted for, so every beat after the first drifted further out of sync with the
// words. The capture now RECORDS where each beat actually began and writes those marks out for the
// render to cut on -- measuring beats guessing, and the guess compounds.
const BEAT = {
  signupImport: 24,
  board: 15,
  couponPayout: 29,
  brandAuthors: 26,
  theNumbers: 11,
}

/**
 * Where each beat began, in seconds from the start of the recording.
 *
 * <p>Written to artifacts/beat-marks.json for build-demo.mjs. Without it the render has to assume
 * the footage runs to the same schedule as the narration, and it never does.
 */
const marks = []
let recordingStart = 0

function mark(id) {
  marks.push({ id, at: (Date.now() - recordingStart) / 1000 })
}

/** Hold the current frame long enough for the narration over it to finish. */
async function hold(page, seconds) {
  await page.waitForTimeout(seconds * 1000)
}

/**
 * Move the mouse to an element before clicking it.
 *
 * <p>Playwright clicks by teleporting the cursor, which on video looks like the UI operating
 * itself. Steering there first is the difference between footage of a product being used and
 * footage of a script running.
 */
async function pointAndClick(page, locator) {
  const box = await locator.boundingBox()
  if (box) {
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 18 })
    await page.waitForTimeout(300)
  }
  await locator.click()
}

test.describe('Demo', () => {
  test.skip(!EMAIL || !PASSWORD,
    'Set DEMO_EMAIL and DEMO_PASSWORD from seed-demo-workspace.mjs output')

  test('The collaborative drop', async ({ page }) => {
    // Generous: this is six beats plus page loads, and a timeout mid-capture wastes the whole take.
    test.setTimeout(5 * 60 * 1000)

    // Sign-in is a TAB on the landing page, not a /login route -- the first version assumed a
    // route and getByLabel, and timed out on a form that was never rendered.
    //
    // Anchored names throughout: "Log in" is the tab, "Enter workspace" is the submit, and a loose
    // /log in/i matches the tab when you meant the button. brand-owner-journey records the same
    // trap on the signup side, where clicking the tab silently never posts the form.
    await page.goto(BASE)
    await page.getByRole('button', { name: /^Log in$/ }).click()
    await page.fill('input[name="email"]', EMAIL)
    await page.fill('input[name="password"]', PASSWORD)
    await page.getByRole('button', { name: /^Enter workspace$/i }).click()
    await page.waitForURL(/\/(workflow|dashboard|campaigns)/, { timeout: 90_000 })
    await page.waitForLoadState('networkidle')

    // The clock starts once the app is loaded and the login is off screen -- the recording begins
    // at page.goto, so the sign-in is footage the narration never covers.
    recordingStart = Date.now()

    // ---- signup-import ----------------------------------------------------
    // The import screen, held. The actual spreadsheet drag is done on the day with a real file: a
    // seeded upload would film a mapping of invented columns, which is the one part of this scene
    // that has to look like somebody's real spreadsheet.
    await gotoSection(page, /import/i)
    mark('signup-import')
    await hold(page, BEAT.signupImport)

    // ---- board ------------------------------------------------------------
    await gotoSection(page, /workflow|board/i)
    mark('board')
    await hold(page, BEAT.board)

    // ---- coupon-payout ----------------------------------------------------
    // Two shots under one beat: the coupons themselves, then what they add up to. Marked at the
    // first, so the narration starts with the codes on screen.
    await gotoSection(page, /coupon/i)
    mark('coupon-payout')
    await hold(page, BEAT.couponPayout / 2)
    await gotoSection(page, /finance|payout/i)
    await hold(page, BEAT.couponPayout / 2)

    // ---- brand-authors ----------------------------------------------------
    await gotoSection(page, /content|pages/i)
    mark('brand-authors')
    await hold(page, BEAT.brandAuthors)

    // ---- the-numbers ------------------------------------------------------
    await gotoSection(page, /dashboard|analytic/i)
    mark('the-numbers')
    await hold(page, BEAT.theNumbers)

    // NO CREATOR BEAT. The portal opens on its sign-in screen unless a creator has been invited
    // and has redeemed the invitation, and filming a login form under narration about a creator
    // editing their page would be worse than leaving the beat out. It returns when the seed
    // creates a creator holding an edit grant -- see docs/Demo-Script-Two-Cut.md beat 2.3.

    writeFileSync(join(OUT_DIR, 'beat-marks.json'), JSON.stringify(marks, null, 2))

    // Nothing is asserted. See the header: a failed assertion here truncates the footage, and
    // proving the product works is the job of every other spec in this directory.
    expect(true).toBe(true)
  })
})

/**
 * Navigate by nav link, tolerantly.
 *
 * <p>Labels drift as the product changes and a capture that dies on a renamed link wastes a take,
 * so a miss holds on the current screen rather than failing. The footage is then short by one shot
 * instead of absent entirely, which is recoverable in an edit.
 */
async function gotoSection(page, pattern) {
  const link = page.getByRole('link', { name: pattern }).first()
  try {
    await pointAndClick(page, link)
    await page.waitForLoadState('networkidle')
  } catch {
    // Deliberately swallowed -- see above.
  }
}
