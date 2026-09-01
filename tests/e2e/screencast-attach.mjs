/**
 * Opens a browser for a HUMAN to sign in, and waits until the session actually exists.
 *
 *   node tests/e2e/screencast-attach.mjs      # sign in; the script closes itself once signed in
 *   node tests/e2e/screencast-capture.mjs     # reuses the profile, no login
 *
 * WHY IT WATCHES THE COOKIE RATHER THAN THE WINDOW. Closing the window used to be the completion
 * signal, which made "signed in" indistinguishable from "closed too early" — twice the profile came
 * back with no INFLUENCRM_SESSION at all, because the window was shut during the provider redirect.
 * A closed window is not evidence of a sign-in. The session cookie is, so that is what this waits
 * for; the window is then closed for you, and reaching that point is itself the proof.
 *
 * The Facebook login is not automated and never should be: the consent dialog only appears in a
 * genuine interactive login, and driving one with stored credentials risks a checkpoint on a real
 * account days before App Review.
 *
 * The profile holds live session cookies. tests/e2e/artifacts/ is gitignored; keep it that way.
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

const here = dirname(fileURLToPath(import.meta.url))
const PROFILE = resolve(here, 'artifacts', 'screencast-profile')
const START = process.argv[2] || 'https://www.tejdux.com/'
const SESSION_COOKIE = 'INFLUENCRM_SESSION'
const MAX_WAIT_MS = 20 * 60 * 1000

mkdirSync(PROFILE, { recursive: true })

const context = await chromium.launchPersistentContext(PROFILE, {
  headless: false,
  viewport: null,
  args: ['--disable-blink-features=AutomationControlled', '--start-maximized'],
})

const page = context.pages()[0] || (await context.newPage())
await page.goto(START, { waitUntil: 'domcontentloaded' })

console.log('')
console.log('  Browser open at ' + START)
console.log('')
console.log('  1. On the login card (right side), scroll to "OR CONTINUE WITH"')
console.log('  2. Click the "Facebook" button and sign in as peddysetty@gmail.com')
console.log('  3. Approve the permission dialog and wait to land INSIDE the app')
console.log('')
console.log('  Leave the window open. It closes itself the moment the session appears.')
console.log('')

const started = Date.now()
let signedIn = false
let closedEarly = false

while (Date.now() - started < MAX_WAIT_MS) {
  let cookies = []
  try {
    cookies = await context.cookies()
  } catch {
    // The context is gone — the window was closed by hand.
    closedEarly = true
    break
  }

  if (cookies.some((c) => c.name === SESSION_COOKIE && /tejdux/.test(c.domain))) {
    signedIn = true
    break
  }
  await sleep(2000)
}

if (signedIn) {
  console.log('  SIGNED IN — session cookie present. Profile saved.')
  await context.close()
  process.exit(0)
}

if (closedEarly) {
  console.log('  WINDOW CLOSED BEFORE SIGN-IN COMPLETED — no session cookie was written.')
  console.log('  Nothing to capture with. Run this again and wait for the app to load.')
  process.exit(1)
}

console.log('  TIMED OUT waiting for the session cookie.')
try {
  await context.close()
} catch {
  // Already gone.
}
process.exit(1)
