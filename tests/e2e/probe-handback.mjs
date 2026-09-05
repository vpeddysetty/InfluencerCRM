import { chromium } from '@playwright/test'

/**
 * Does a creator's hand-back reach the brand's screen?
 *
 * Uses the accounts seed-creator-portal.mjs printed; pass them in.
 */
const BASE = 'https://tejdux.com'
const PORTAL = 'https://portal.tejdux.com'
const CREATOR_EMAIL = process.argv[2]
const BRAND_EMAIL = process.argv[3]
const PASSWORD = 'DemoPass123!'

const b = await chromium.launch()
const ok = (m, v) => console.log((v ? 'PASS ' : 'FAIL ') + m)

// ---- creator hands it back ----
const c = await (await b.newContext()).newPage()
c.on('response', async (r) => {
  if (/hand-back|landing-templates/.test(r.url()) && r.request().method() !== 'GET') {
    let t = ''
    try { t = (await r.text()).slice(0, 200) } catch {}
    console.log('  [creator]', r.status(), r.url().replace(/^https:\/\/[^/]+/, ''), t)
  }
})
await c.goto(PORTAL, { waitUntil: 'domcontentloaded' })
await c.locator('input[type=email], input').first().fill(CREATOR_EMAIL)
await c.locator('input[type=password]').first().fill(PASSWORD)
await c.getByRole('button', { name: /sign in|log in/i }).first().click()
await c.waitForTimeout(6000)

const before = (await c.textContent('body')).replace(/\s+/g, ' ')
console.log('  portal before:', before.slice(0, 160))

const card = c.locator('button.cp-card')
if (await card.count()) {
  await card.first().click()
  await c.waitForTimeout(5000)
  const hb = c.getByRole('button', { name: /hand back|send back|return/i })
  console.log('  hand-back buttons:', await hb.count())
  for (let i = 0; i < (await hb.count()); i++) {
    console.log('   ', JSON.stringify((await hb.nth(i).textContent()).trim()))
  }
  if (await hb.count()) {
    await hb.first().click()
    await c.waitForTimeout(3000)
    // A note field and a confirm may follow.
    const confirm = c.getByRole('button', { name: /hand back|send|confirm/i })
    if (await confirm.count()) {
      await confirm.last().click().catch(() => {})
      await c.waitForTimeout(6000)
    }
  }
  const after = (await c.textContent('body')).replace(/\s+/g, ' ')
  console.log('  portal after:', after.slice(0, 200))
}

// ---- what does the brand see? ----
const p = await (await b.newContext()).newPage()
p.on('response', async (r) => {
  if (/\/api\/landing-templates(\?|$)/.test(r.url()) && r.status() === 200) {
    let t = ''
    try { t = (await r.text()) } catch {}
    const m = t.match(/"turn":"?([a-z]+)"?/)
    const s = t.match(/"stage":"([a-z_]+)"/)
    console.log('  [brand sees] turn=', m ? m[1] : '(absent)', ' stage=', s ? s[1] : '(absent)')
  }
})
await p.goto(BASE, { waitUntil: 'domcontentloaded' })
await p.getByRole('button', { name: /^Log in$/ }).first().click()
await p.fill('input[name="email"]', BRAND_EMAIL)
await p.fill('input[name="password"]', PASSWORD)
await p.getByRole('button', { name: /^Enter workspace$/i }).click()
await p.waitForURL(/\/(workflow|dashboard|campaigns)/, { timeout: 120_000 })
await p.getByRole('link', { name: /^Content$/i }).first().click()
await p.waitForTimeout(4000)
await p.locator('select').first().selectOption({ label: 'Autumn Layers' }).catch(() => {})
await p.waitForTimeout(5000)

const body = (await p.textContent('body')).replace(/\s+/g, ' ')
const i = body.indexOf('Working on this page')
console.log('  brand panel:', i >= 0 ? body.slice(i, i + 260) : '(panel absent)')
ok('brand sees it is back with them', /Hand over to creator|Waiting on you|your turn/i.test(body))
ok('brand no longer told it is with the creator', !/Waiting on the creator/i.test(body))

await b.close()
