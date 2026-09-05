/**
 * Renames the signed-in workspace and reports whether the session token carries a brand.
 *
 *   node tests/e2e/rename-workspace.mjs "TejDux"
 *
 * WHY NOT THE SIDEBAR DIALOG. That control posts to /api/brands — CREATE — which the plan caps at
 * one brand for FREE and PRO, so it 403s on an account that already has its workspace. Renaming is
 * a different endpoint: POST /api/brands/onboarding, which renames context.brandId() taken from the
 * caller's own token and never from the body.
 *
 * The call is issued from inside the page so it travels the same session as the failing "Look up",
 * which makes the outcome a diagnosis and not just a rename:
 *
 *   200 -> the token DOES carry a brand, so resolve-handle 403s for its own reason
 *          (Instagram credentials / business_discovery gating)
 *   403 -> the token carries no brand claim; a fresh sign-in is the fix
 *
 * Reuses the persistent profile, so no login happens here.
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const PROFILE = resolve(here, 'artifacts', 'screencast-profile')
const SHOTS = resolve(here, 'artifacts', 'screencast')
const NAME = process.argv[2] || 'TejDux'
const BASE = 'https://www.tejdux.com'

mkdirSync(SHOTS, { recursive: true })

const context = await chromium.launchPersistentContext(PROFILE, {
  headless: false,
  viewport: null,
  args: ['--disable-blink-features=AutomationControlled', '--start-maximized'],
})
const page = context.pages()[0] || (await context.newPage())
page.setDefaultTimeout(30_000)

try {
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(4500)

  // Cookie mode: the DPS proxy holds the credential, so this goes through /dps/api with the
  // session cookie and the double-submit CSRF header the app itself uses.
  const result = await page.evaluate(async (workspaceName) => {
    // XSRF-TOKEN is the name Spring's CookieCsrfTokenRepository writes and the name core.js
    // reads. Guessing a different one produces a 403 that looks exactly like an authorization
    // failure, which is a misdiagnosis waiting to happen.
    const csrf = document.cookie
      .split('; ')
      .find((c) => c.startsWith('XSRF-TOKEN='))
      ?.split('=')[1]

    const attempt = async (url, extraHeaders) => {
      try {
        const res = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...extraHeaders },
          credentials: 'include',
          body: JSON.stringify({ workspaceName, accountType: 'brand' }),
        })
        let body = ''
        try {
          body = (await res.text()).slice(0, 400)
        } catch {
          body = '<unreadable>'
        }
        return { url, status: res.status, body }
      } catch (error) {
        return { url, status: 0, body: String(error && error.message) }
      }
    }

    const headers = csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {}
    const viaProxy = await attempt(
      'https://api.tejdux.com/dps/api/brands/onboarding',
      headers,
    )
    return { csrfPresent: Boolean(csrf), viaProxy }
  }, NAME)

  console.log('')
  console.log('  csrf cookie present: ' + result.csrfPresent)
  console.log(`  ${result.viaProxy.status}  ${result.viaProxy.url}`)
  console.log('      ' + result.viaProxy.body)
  console.log('')

  await page.reload({ waitUntil: 'domcontentloaded' }).catch(() => {})
  await page.waitForTimeout(4000)
  await page.screenshot({ path: resolve(SHOTS, '10-rename-result.png') })

  const text = (await page.locator('body').innerText().catch(() => '')) || ''
  console.log('  name visible after reload: ' + text.includes(NAME))
} catch (error) {
  console.log('  ERROR: ' + (error && error.message))
} finally {
  await page.waitForTimeout(1000)
  await context.close()
}
