// Drive the deployed creators page in a real browser.
//
// OP-42 established the ladder: `vite build` proves the module parses, a render check proves the
// component runs, and only a browser proves the PAGE works — with its real bundle, its real API and
// a real session. PR-67's filters are the thing being checked, because a control that silently
// stops rendering is exactly what the earlier layers miss.
import { chromium } from 'playwright'
import { readFileSync } from 'node:fs'

const creds = readFileSync(process.env.UI_CHECK_CREDS, 'utf8').trim().split('\n')
const email = creds[0]
const password = creds[1]

const browser = await chromium.launch()
const page = await browser.newPage()

const consoleErrors = []
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => consoleErrors.push('PAGEERROR: ' + e.message))

const out = []
try {
  await page.goto('https://app.tejdux.com/', { waitUntil: 'networkidle', timeout: 60000 })
  // The panel opens in SIGN-UP mode. Filling the email and pressing the primary button there
  // attempts to create an account, which fails on a duplicate and leaves you on the landing page --
  // silently, with no console error, which is exactly how the first run of this looked "fine".
  await page.getByRole('button', { name: /^log in$/i }).first().click()
  await page.waitForTimeout(1500)
  // By input NAME, not by label: getByLabel resolved in sign-up mode and not in log-in mode, and
  // the failure is a 30s timeout rather than anything that names the cause.
  await page.locator('input[name="email"]').first().fill(email)
  await page.locator('input[name="password"]').first().fill(password)
  // "Enter workspace", not "Log in" -- the tab is labelled Log in, the submit button is not.
  // Matching the tab's text clicked the tab again and left the form untouched, which looks
  // identical to a failed sign-in from the outside.
  await page.getByRole('button', { name: /enter workspace/i }).first().click()
  await page.waitForTimeout(8000)
  out.push('after sign-in url: ' + page.url())
  const t = await page.locator('body').innerText()
  out.push('page says: ' + t.slice(0, 220).split(String.fromCharCode(10)).join(' | '))

  // CLICK the nav link; do not goto(). The SPA holds its token in memory (App.jsx), so a full page
  // load discards the session and lands back on the login screen -- which is what the first run of
  // this did, reporting every control MISSING for a page it never reached.
  await page.getByRole('link', { name: /^creators$/i }).first().click()
  await page.waitForTimeout(3500)
  out.push('creators url: ' + page.url())

  const body = await page.locator('body').innerText()
  out.push('shows "Bea Big": ' + body.includes('Bea Big'))
  out.push('shows "Fitz Fit": ' + body.includes('Fitz Fit'))

  const controls = [
    ['platform', /filter by platform/i],
    ['niche', /filter by niche/i],
    ['followers', /filter by minimum followers/i],
    ['vetting', /filter by vetting status/i],
  ]
  for (const [name, label] of controls) {
    const n = await page.getByLabel(label).count()
    out.push('control ' + name + ': ' + (n > 0 ? 'present' : 'MISSING'))
  }

  // Actually filter, and confirm the list narrows — a control that renders but does nothing is
  // still broken, and is what a render check cannot tell you.
  const niche = page.getByLabel(/filter by niche/i)
  if ((await niche.count()) > 0) {
    await niche.selectOption('beauty')
    await page.waitForTimeout(1500)
    const after = await page.locator('body').innerText()
    out.push('niche=beauty keeps Bea: ' + after.includes('Bea Big'))
    out.push('niche=beauty drops Fitz: ' + !after.includes('Fitz Fit'))
  }
} catch (err) {
  out.push('FAILED: ' + String(err.message).split('\n')[0])
} finally {
  await page
    .screenshot({ path: 'C:/AI/InfluencerCRM/tests/e2e/creators-prod-check.png', fullPage: true })
    .catch(() => {})
  await browser.close()
}

console.log(out.map((l) => '  ' + l).join('\n'))
console.log('  console errors: ' + (consoleErrors.length ? consoleErrors.slice(0, 3).join(' | ') : 'none'))
