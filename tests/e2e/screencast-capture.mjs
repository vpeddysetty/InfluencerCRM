/**
 * Walks the App Review screencast script and screenshots every beat, so the frames can be checked
 * against what the narration claims BEFORE anything is recorded.
 *
 * Reuses the signed-in profile from screencast-attach.mjs. The Facebook login itself is not
 * automated and never should be — see that file.
 *
 *   node tests/e2e/screencast-capture.mjs
 *
 * Shots land in tests/e2e/artifacts/screencast/NN-name.png (gitignored), and a findings.json
 * records what each beat actually showed. Nothing here asserts or exits non-zero: the point is to
 * report the gap between script and reality, not to pass.
 *
 * The one beat that can fail loudly is 4. The script calls a "Simulated" badge a stop, because
 * filming generated follower counts as real audience data is the specific thing App Review exists
 * to catch. So the badge text is read and reported verbatim rather than interpreted.
 */
import { chromium } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const PROFILE = resolve(here, 'artifacts', 'screencast-profile')
const SHOTS = resolve(here, 'artifacts', 'screencast')
const BASE = 'https://www.tejdux.com'

mkdirSync(SHOTS, { recursive: true })

const findings = []
let n = 0

async function shot(page, name, note) {
  n += 1
  const file = resolve(SHOTS, `${String(n).padStart(2, '0')}-${name}.png`)
  await page.screenshot({ path: file, fullPage: false })
  console.log(`  [${n}] ${name} :: ${note}`)
  findings.push({ step: n, name, note, file })
  return file
}

const context = await chromium.launchPersistentContext(PROFILE, {
  headless: false,
  viewport: null,
  args: ['--disable-blink-features=AutomationControlled', '--start-maximized'],
})
const page = context.pages()[0] || (await context.newPage())
page.setDefaultTimeout(30_000)

try {
  // ---- Session check. Everything downstream is meaningless if this is a signed-out browser.
  // The DPS redirects a completed sign-in to "/", so that is where a live session shows itself.
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(4000)

  const bodyText = (await page.locator('body').innerText().catch(() => '')) || ''
  const signedIn =
    /Creators|Campaigns|Dashboard|Workflow/i.test(bodyText) &&
    !/Continue with Facebook|Log in|Sign up/i.test(bodyText.slice(0, 400))

  await shot(page, 'session-check', signedIn ? 'appears SIGNED IN' : 'appears SIGNED OUT')

  if (!signedIn) {
    findings.push({
      step: 'ABORT',
      note: 'Not signed in — the saved profile has no live session. Re-run screencast-attach.mjs.',
      url: page.url(),
    })
    console.log('\n  NOT SIGNED IN. Stopping before the in-app beats.\n')
  } else {
    // ---- Beat 2 tail: Settings -> Sign-in methods
    await page.goto(`${BASE}/settings`, { waitUntil: 'domcontentloaded' }).catch(() => {})
    await page.waitForTimeout(3000)
    const settingsText = (await page.locator('body').innerText().catch(() => '')) || ''
    await shot(
      page,
      'beat2-settings-signin-methods',
      `Facebook connected: ${/Facebook[\s\S]{0,40}Connected/i.test(settingsText)} | ` +
        `run-together text present: ${/(Google|Facebook)Connected/i.test(settingsText)}`,
    )

    // ---- Beat 3: Creators directory
    await page.goto(`${BASE}/creators`, { waitUntil: 'domcontentloaded' }).catch(() => {})
    await page.waitForTimeout(3000)
    await shot(page, 'beat3-creators-directory', 'creator list as the script opens on')

    // ---- Beat 4: New creator -> handle lookup -> the badge
    const newCreator = page
      .getByRole('button', { name: /new creator|add creator/i })
      .first()
    if (await newCreator.isVisible().catch(() => false)) {
      await newCreator.click().catch(() => {})
      await page.waitForTimeout(2500)
      await shot(page, 'beat4-new-creator-drawer', 'drawer open')

      const handle = page
        .getByLabel(/handle/i)
        .or(page.getByPlaceholder(/handle|username/i))
        .first()
      if (await handle.isVisible().catch(() => false)) {
        await handle.fill('tejduxtest').catch(() => {})
        await shot(page, 'beat4-handle-typed', 'handle "tejduxtest" entered')

        const lookup = page.getByRole('button', { name: /look ?up/i }).first()
        if (await lookup.isVisible().catch(() => false)) {
          await lookup.click().catch(() => {})
          // "Do not cut the wait" — a real Graph call. Give it room.
          await page.waitForTimeout(15_000)
          const panel = (await page.locator('body').innerText().catch(() => '')) || ''
          const simulated = /simulated/i.test(panel)
          const verified = /platform[- ]verified/i.test(panel)
          await shot(
            page,
            'beat4-audience-panel',
            `badge — Simulated: ${simulated} | Platform verified: ${verified}` +
              (simulated ? '  <-- SCRIPT SAYS THIS IS A STOP' : ''),
          )
        } else {
          await shot(page, 'beat4-no-lookup-button', 'no "Look up" control found')
        }
      } else {
        await shot(page, 'beat4-no-handle-field', 'no Handle field found')
      }
    } else {
      await shot(page, 'beat4-no-new-creator-button', 'no "New creator" control found')
    }
  }

  // ---- Beat 5: public pages. These need no session, so they run either way.
  for (const [path, name] of [
    ['/data-deletion/', 'beat5-data-deletion'],
    ['/privacy/', 'beat5-privacy'],
  ]) {
    await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' }).catch(() => {})
    await page.waitForTimeout(2500)
    await shot(page, name, `public page ${path}`)
  }

  // ---- Beat 1: the signed-out landing page the video opens on.
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' }).catch(() => {})
  await page.waitForTimeout(2500)
  await shot(page, 'beat1-landing', 'landing page (note: signed-in session may alter the header)')
} catch (error) {
  findings.push({ step: 'ERROR', note: String(error && error.message) })
  console.log('  ERROR: ' + (error && error.message))
} finally {
  writeFileSync(resolve(SHOTS, 'findings.json'), JSON.stringify(findings, null, 2))
  console.log('\n  Shots + findings.json in ' + SHOTS + '\n')
  await context.close()
}
