/**
 * Post-deploy smoke check: does the deployed landing page actually RENDER?
 *
 * Run after every UI deploy:
 *   node tests/e2e/smoke-landing.mjs                      # https://www.tejdux.com
 *   node tests/e2e/smoke-landing.mjs http://localhost:5173
 *
 * Exits non-zero on failure, so it can gate a deploy script.
 *
 * WHY THIS EXISTS. On 2026-08-14 a `const` was used one line above its declaration in App.jsx. That
 * is a temporal dead zone error: it throws ReferenceError on the first render, React unmounts the
 * whole tree, and the site serves a blank page. Every check in the pipeline passed. The unit tests
 * assert against source as text and never mount the component; the Vite build bundles without
 * evaluating; and curl reported 200 for the HTML and all seven assets, because every file genuinely
 * was there and correct. The deployment was perfect and the application was dead.
 *
 * The gap was that nothing ever executed the page. So this does exactly that, and nothing more —
 * it is a smoke check, not a journey. It loads the URL in a real browser, fails on any uncaught
 * exception, and requires that recognisable content appears in the DOM. A blank page fails on both
 * counts, which is the point.
 */
import { chromium } from '@playwright/test'

const target = process.argv[2] || 'https://www.tejdux.com'
const TIMEOUT_MS = 30_000

const failures = []
const note = (message) => console.log(`  ${message}`)

const browser = await chromium.launch()
// A fresh context every run: a cached bundle or a stale session cookie would make this assert
// something other than what was just deployed.
const context = await browser.newContext()
const page = await context.newPage()

// Any uncaught exception is a failure. The TDZ bug surfaced here first and nowhere else.
page.on('pageerror', (error) => failures.push(`uncaught exception: ${error.message}`))
page.on('console', (message) => {
  if (message.type() === 'error') {
    // Console errors are reported but not fatal: a blocked third-party request or a favicon 404
    // says nothing about whether the app mounted.
    note(`console error (non-fatal): ${message.text().slice(0, 140)}`)
  }
})

console.log(`\nSmoke check: ${target}\n`)

try {
  const response = await page.goto(target, { waitUntil: 'networkidle', timeout: TIMEOUT_MS })

  if (!response || !response.ok()) {
    failures.push(`page returned HTTP ${response ? response.status() : 'no response'}`)
  } else {
    note(`HTTP ${response.status()}`)
  }

  // Did React mount anything at all? #root exists in index.html either way, so its PRESENCE proves
  // nothing — only its content does. This is the assertion a blank page fails.
  const rootHtml = await page.locator('#root').innerHTML().catch(() => '')
  if (rootHtml.trim().length < 200) {
    failures.push(`#root is empty or near-empty (${rootHtml.trim().length} chars) — the app did not mount`)
  } else {
    note(`#root rendered ${rootHtml.trim().length} chars`)
  }

  // Content that only exists if the landing page itself rendered, rather than some error boundary.
  const headline = page.locator('h1')
  const headlineText = await headline.first().textContent({ timeout: 5_000 }).catch(() => null)
  if (!headlineText || !headlineText.trim()) {
    failures.push('no <h1> rendered — the landing page did not render its headline')
  } else {
    note(`headline: "${headlineText.trim().slice(0, 60)}"`)
  }

  // The sign-in affordances. Their absence means the page rendered but the auth panel did not,
  // which is the half that matters for every flow this product has.
  for (const label of ['Sign up', 'Log in']) {
    const count = await page.getByRole('button', { name: label, exact: true }).count()
    if (count === 0) {
      failures.push(`no "${label}" control found`)
    } else {
      note(`"${label}" present`)
    }
  }
  // Actually SIGN UP. Everything above proves the page renders; this proves it works.
  //
  // Added after a signup bug that no source-reading test could catch: the consent checkbox moved
  // below <form>, so the FormData the submit handler built stopped seeing it. Every signup was sent
  // with acceptedTerms=false and refused by the server, while the box sat ticked on screen. The
  // unit tests passed, the build passed, the API passed when called directly with the field — the
  // only broken thing was the browser, and nothing was driving a browser.
  //
  // Skipped against production by default: it writes a real account to the real database. Pass
  // --signup to opt in, which is worth doing after any change to the auth path.
  const runSignup = process.argv.includes('--signup') || !target.includes('tejdux.com')
  if (!runSignup) {
    note('signup check skipped (pass --signup to run it against a real environment)')
  } else {
    const email = `smoke${Date.now()}@tejdux.com`
    await page.getByRole('button', { name: 'Sign up', exact: true }).click()
    await page.locator('input[name="fullName"]').fill('Smoke Test')
    await page.locator('input[name="brand"]').fill('Smoke Test Co')
    await page.locator('input[name="email"]').fill(email)
    await page.locator('input[name="password"]').fill('Test!23456')
    await page.locator('input[name="acceptedTerms"]').check()

    await page.getByRole('button', { name: /Create workspace/i }).click()

    // Either the workspace loads, or an error note appears. Waiting for both and reporting
    // whichever arrives beats a fixed timeout that reports "no workspace" for every failure.
    const errorNote = page.locator('.auth-error-note')
    const outcome = await Promise.race([
      page.waitForURL((url) => !url.pathname.match(/^\/(login)?$/), { timeout: 20_000 })
        .then(() => 'signed-in')
        .catch(() => null),
      errorNote.waitFor({ state: 'visible', timeout: 20_000 })
        .then(() => 'error')
        .catch(() => null),
    ])

    if (outcome === 'error') {
      failures.push(`signup failed: ${(await errorNote.textContent())?.trim().slice(0, 160)}`)
    } else if (outcome === 'signed-in') {
      note(`signup succeeded → ${new URL(page.url()).pathname}`)
    } else {
      failures.push('signup neither completed nor reported an error within 20s')
    }
  }
} catch (error) {
  failures.push(`navigation failed: ${error.message}`)
} finally {
  await browser.close()
}

console.log('')
if (failures.length > 0) {
  console.error('SMOKE CHECK FAILED')
  failures.forEach((failure) => console.error(`  - ${failure}`))
  process.exit(1)
}
console.log('SMOKE CHECK PASSED\n')
