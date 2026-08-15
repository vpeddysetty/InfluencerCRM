/**
 * Does an input keep focus while you type into it?
 *
 * Reproduces the "form loses focus every time I type" report against a real browser, because that
 * is the only place the symptom exists. Types character by character and checks after each one that
 * the focused element is still the same input and that the caret has not jumped.
 *
 *   node tests/e2e/focus-check.mjs <baseUrl>
 *
 * Signs up a throwaway account first, since the campaigns drawer is behind a session.
 */
import { chromium } from '@playwright/test'

const target = process.argv[2] || 'http://localhost:4173'
const failures = []
const note = (m) => console.log(`  ${m}`)

const browser = await chromium.launch()
const context = await browser.newContext()
const page = await context.newPage()
page.on('pageerror', (e) => failures.push(`uncaught exception: ${e.message}`))

console.log(`\nFocus check: ${target}\n`)

try {
  await page.goto(target, { waitUntil: 'networkidle', timeout: 30_000 })

  const email = `focus${Date.now()}@tejdux.com`
  await page.getByRole('button', { name: 'Sign up', exact: true }).click()
  await page.locator('input[name="fullName"]').fill('Focus Test')
  await page.locator('input[name="brand"]').fill('Focus Co')
  await page.locator('input[name="email"]').fill(email)
  await page.locator('input[name="password"]').fill('Test!23456')
  await page.locator('input[name="acceptedTerms"]').check()
  await page.getByRole('button', { name: /Create workspace/i }).click()
  await page.waitForURL((url) => !url.pathname.match(/^\/(login)?$/), { timeout: 25_000 })
  note(`signed in → ${new URL(page.url()).pathname}`)

  // In-app navigation, not page.goto. The session lives in memory after signup (the DPS cookie is
  // set on api.tejdux.com and the SPA holds a bearer token), so a full page load lands back on the
  // signed-out landing page. Clicking the nav link is also what a user actually does.
  await page.getByRole('link', { name: /Campaigns/i }).first().click()
  await page.getByRole('button', { name: /Create your first campaign|New campaign/i }).first().click()

  const input = page.locator('#campaign-name')
  await input.waitFor({ state: 'visible', timeout: 10_000 })
  await input.click()

  // Type one character at a time. A component remounting on each keystroke drops focus to <body>,
  // so the failure shows up on the SECOND character and every one after it.
  const word = 'Summer Launch'
  for (const character of word) {
    await page.keyboard.type(character, { delay: 40 })
    const stillFocused = await input.evaluate((el) => el === document.activeElement)
    if (!stillFocused) {
      const actual = await page.evaluate(() =>
        `${document.activeElement?.tagName}#${document.activeElement?.id || ''}`)
      failures.push(`focus left #campaign-name after typing "${character}" — now on ${actual}`)
      break
    }
  }

  const typed = await input.inputValue()
  if (typed !== word) {
    failures.push(`expected "${word}" in the field, got "${typed}" — characters were dropped`)
  } else {
    note(`typed "${typed}" with focus retained throughout`)
  }
} catch (error) {
  failures.push(`check failed: ${error.message}`)
} finally {
  await browser.close()
}

console.log('')
if (failures.length) {
  console.error('FOCUS CHECK FAILED')
  failures.forEach((f) => console.error(`  - ${f}`))
  process.exit(1)
}
console.log('FOCUS CHECK PASSED\n')
