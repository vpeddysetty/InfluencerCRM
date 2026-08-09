// Real-browser pass over the shell at :5173 — proves the journeys render, not just that
// the API answers. Chromium drives the actual login form and reads the DOM back.
import { chromium } from 'playwright'

const UI = 'http://localhost:5173'
const SHOTS = process.env.SHOTS || '.'
const results = []
const rec = (id, ok, desc, extra = '') => {
  results.push({ id, ok, desc, extra })
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${id} | ${desc}${extra ? ' | ' + extra : ''}`)
}

// Navigate the way a user does — by clicking the nav — rather than page.goto(). A hard
// navigation remounts the app and drops the in-memory access token, which is a real
// limitation of the shell's legacy /api data path but not what these assertions are about.
async function navigate(page, label) {
  const link = page.locator('a, button').filter({ hasText: new RegExp(`^${label}$`) }).first()
  await link.click()
  await page.waitForTimeout(2500)
  return page.locator('body').innerText()
}

async function login(page, email, password) {
  await page.goto(`${UI}/login`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1200)
  // /login opens on the *signup* tab. Switch to "Log in" first, or the credentials are
  // typed into the signup form and the submit creates nothing.
  await page.locator('button', { hasText: /^Log in$/ }).first().click()
  await page.waitForTimeout(600)

  // The login tab renders its email field as type="text", so match on the name instead.
  const emailBox = page.locator('input[name="email"]').first()
  const passBox = page.locator('input[type="password"]').first()
  await emailBox.waitFor({ state: 'visible', timeout: 15000 })
  await emailBox.fill(email)
  await passBox.fill(password)
  await Promise.all([
    page.waitForResponse(r => r.url().includes('/dps/auth/login'), { timeout: 20000 }).catch(() => null),
    page.locator('button[type="submit"]').first().click(),
  ])
  await page.waitForTimeout(3000)
}

const browser = await chromium.launch()

try {
  // ---------------- Agency owner ----------------
  {
    const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
    const page = await ctx.newPage()
    const errors = []
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

    await login(page, 'demo.admin@northstar.test', 'DemoPass123!')
    const url = page.url()
    rec('UI-A1', !/\/login/.test(url), 'Agency ADMIN reaches the app after login', url)
    await page.screenshot({ path: `${SHOTS}/ui-agency-dashboard.png`, fullPage: true })

    const body = await page.locator('body').innerText()
    rec('UI-A2', /Aurora/i.test(body), 'Active brand "Aurora" is rendered in the shell')

    // The brand switcher must be present for a multi-brand agency.
    const hasLumen = /Lumen/i.test(body) ||
      (await page.locator('select, [role="combobox"], button').filter({ hasText: /Lumen|Aurora/i }).count()) > 0
    rec('UI-A3', hasLumen, 'Brand switcher exposes the second brand')

    // Creators page renders the seeded creator.
    const creators = await navigate(page, 'Creators')
    rec('UI-A4', /shared_star/i.test(creators), 'Creators page lists @shared_star for Aurora')
    await page.screenshot({ path: `${SHOTS}/ui-agency-creators.png`, fullPage: true })

    const camps = await navigate(page, 'Campaigns')
    rec('UI-A5', /Aurora Summer Glow/i.test(camps), 'Campaigns page lists the E2E campaign')
    await page.screenshot({ path: `${SHOTS}/ui-agency-campaigns.png`, fullPage: true })

    const wf = await navigate(page, 'Workflow')
    rec('UI-A6', /Aurora Creator Pipeline|Prospect|Outreach/i.test(wf), 'Workflow board and stages render')
    await page.screenshot({ path: `${SHOTS}/ui-agency-workflow.png`, fullPage: true })

    // A token must never be readable by JavaScript — that is the point of the DPS design.
    const leaked = await page.evaluate(() => {
      const all = { ...localStorage, ...sessionStorage }
      const blob = JSON.stringify(all)
      return {
        cookieHasSession: /INFLUENCRM_SESSION/.test(document.cookie),
        tokenish: /eyJ|accessToken|refreshToken|bearer/i.test(blob),
      }
    })
    rec('UI-A7', !leaked.tokenish, 'No access/refresh token in localStorage or sessionStorage')
    rec('UI-A8', !leaked.cookieHasSession, 'Session cookie is httpOnly (invisible to document.cookie)')

    rec('UI-A9', errors.filter(e => !/favicon|404 \(Not Found\)/i.test(e)).length === 0,
        'No uncaught console errors on the agency journey',
        errors.slice(0, 2).join(' ~ '))
    await ctx.close()
  }

  // ---------------- Solo brand owner ----------------
  {
    const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
    const page = await ctx.newPage()
    await login(page, 'e2e.brand.owner@veridianglow.test', 'DemoPass123!')
    rec('UI-B1', !/\/login/.test(page.url()), 'Solo brand OWNER reaches the app after login', page.url())
    await page.screenshot({ path: `${SHOTS}/ui-brand-dashboard.png`, fullPage: true })

    const body = await page.locator('body').innerText()
    rec('UI-B2', /Veridian/i.test(body), 'Own brand "Veridian Glow Co" is rendered')
    rec('UI-B3', !/Aurora|Lumen|Northstar/i.test(body),
        'TENANCY: no agency brand names leak into the solo brand UI')

    const creators = await navigate(page, 'Creators')
    rec('UI-B4', /veridian_muse/i.test(creators), 'Creators page lists the brand\'s own creator')
    rec('UI-B5', !/shared_star/i.test(creators),
        'TENANCY: the agency\'s @shared_star is absent from this brand\'s list')
    await page.screenshot({ path: `${SHOTS}/ui-brand-creators.png`, fullPage: true })

    const pay = await navigate(page, 'Payouts')
    rec('UI-B6', /50\.40|50,40/.test(pay), 'Payouts page shows the 50.40 settled amount')
    await page.screenshot({ path: `${SHOTS}/ui-brand-payouts.png`, fullPage: true })

    // Known gap, asserted so it is tracked rather than forgotten: the DPS session cookie
    // survives a reload, but the shell's legacy /api data path still authenticates with an
    // in-memory bearer token, so a hard refresh loses the data until the shell is migrated
    // to the cookie proxy. Expected to flip to PASS once that migration lands.
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(3000)
    const afterReload = await page.locator('body').innerText()
    rec('UI-B7', !/Bearer token is required/i.test(afterReload),
        'KNOWN GAP: data still loads after a hard page reload')
    await ctx.close()
  }
} finally {
  await browser.close()
}

const pass = results.filter(r => r.ok).length
console.log(`\n==== UI: PASS ${pass} / ${results.length} ====`)
if (pass !== results.length) {
  console.log(results.filter(r => !r.ok).map(r => `  ${r.id}: ${r.desc} ${r.extra}`).join('\n'))
}
