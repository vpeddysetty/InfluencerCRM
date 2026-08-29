// @ts-check
const { test, expect } = require('@playwright/test')

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
const BEAT = {
  // 22, not 18: the rendered narration measured 21.8s. Raised rather than trimming the words --
  // this is the beat that argues against the spreadsheet, which is the actual competitor, and it
  // is the wrong one to rush.
  spreadsheet: 22,
  brief: 15,
  handoff: 12,
  creator: 20,
  waiting: 12,
  coupon: 18,
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

    await page.goto(`${BASE}/login`)
    await page.getByLabel(/email/i).fill(EMAIL)
    await page.getByLabel(/password/i).fill(PASSWORD)
    await page.getByRole('button', { name: /sign in|log in/i }).click()
    await page.waitForLoadState('networkidle')

    // ---- Beat 0: the spreadsheet ------------------------------------------
    // The import screen, held. The actual drag is done on the day with a real file -- a seeded
    // upload would film a mapping of invented columns, which is the one part of this scene that
    // has to look like somebody's real spreadsheet.
    await gotoSection(page, /import/i)
    await hold(page, BEAT.spreadsheet)

    // ---- Beat 1: the brief becomes a page ---------------------------------
    await gotoSection(page, /content|pages/i)
    await hold(page, BEAT.brief)

    // ---- Beat 2: the handoff, and the board that follows -------------------
    // Two shots in one beat: the collaborator panel, then the board. Cut between them in the edit;
    // recording both means the cut is available rather than needing a second take.
    await hold(page, BEAT.handoff / 2)
    await gotoSection(page, /workflow|board/i)
    await hold(page, BEAT.handoff / 2)

    // ---- Beat 3: the creator ----------------------------------------------
    // The portal is its own site on its own origin, so this is a separate context at phone size --
    // the one beat that is not the brand app, and the reason the demo is split-screen at all.
    const phone = await page.context().browser().newContext({
      viewport: { width: 390, height: 844 },
      isMobile: true,
      hasTouch: true,
      recordVideo: { dir: 'test-results/', size: { width: 390, height: 844 } },
    })
    const creatorPage = await phone.newPage()
    await creatorPage.goto(process.env.DEMO_PORTAL_URL || `${BASE.replace('//', '//creators.')}/`)
    await hold(creatorPage, BEAT.creator)
    await phone.close()

    // ---- Beat 4: waiting on you -------------------------------------------
    await gotoSection(page, /content|pages/i)
    await hold(page, BEAT.waiting)

    // ---- Beat 5: the coupon and the attribution ---------------------------
    await gotoSection(page, /coupon/i)
    await hold(page, BEAT.coupon / 2)
    await gotoSection(page, /dashboard|analytic/i)
    await hold(page, BEAT.coupon / 2)

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
