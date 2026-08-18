/**
 * Opens a browser that KEEPS its profile on disk, so a sign-in done by hand survives the run.
 *
 * Why this exists rather than the storage-state dance: the first attempt launched a throwaway
 * profile and then needed the process to be told "the human is signed in now" before it could
 * export cookies. That signal had nowhere to come from — the process runs in the background with
 * no usable stdin — and the sign-in was stranded inside a window nothing could read.
 *
 * A persistent context removes the handoff entirely. The profile lives at a known path, so the
 * capture run reopens the SAME logged-in browser later. Sign in once; every later run inherits it.
 *
 *   node tests/e2e/screencast-attach.mjs      # sign in by hand, then close the window
 *   node tests/e2e/screencast-capture.mjs     # reuses the profile, no login
 *
 * The profile holds live session cookies. tests/e2e/artifacts/ is gitignored; keep it that way.
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
export const PROFILE = resolve(here, 'artifacts', 'screencast-profile')
const START = process.argv[2] || 'https://www.tejdux.com/'

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
console.log('  Profile: ' + PROFILE)
console.log('')
console.log('  1. Sign in with Facebook as peddysetty@gmail.com')
console.log('  2. Land inside the app (left nav visible)')
console.log('  3. CLOSE THE WINDOW yourself when done — the profile persists on disk')
console.log('')

// Resolves when the human closes the window. No stdin, no signal file, nothing to co-ordinate.
await new Promise((done) => context.on('close', done))

console.log('  Window closed. Profile saved; the capture run will reuse this session.')
