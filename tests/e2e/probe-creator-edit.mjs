import { chromium } from '@playwright/test'

/**
 * Does a creator's edit survive the hand-back and reach the brand?
 *
 *   node tests/e2e/probe-creator-edit.mjs <creatorEmail> <brandEmail>
 *
 * Drives the real product: the brand hands the page over, the creator types a MARKER into the
 * hero headline and saves, hands it back, and the brand looks for that marker.
 */
const BASE = 'https://tejdux.com'
const PORTAL = 'https://portal.tejdux.com'
const CREATOR_EMAIL = process.argv[2]
const BRAND_EMAIL = process.argv[3]
const PASSWORD = 'DemoPass123!'
const MARKER = `Creator wrote this ${Date.now().toString().slice(-6)}`

const b = await chromium.launch()
const ok = (m, v) => console.log((v ? 'PASS ' : 'FAIL ') + m)
const log = (tag) => async (r) => {
  if (r.request().method() === 'GET') return
  if (!/creator-portal|landing-templates|handoff/.test(r.url())) return
  let t = ''
  try { t = (await r.text()).slice(0, 160) } catch {}
  console.log(`  [${tag}]`, r.status(), r.url().replace(/^https:\/\/[^/]+/, ''), t)
}

// ---- brand: make sure the page is with the creator ----
const p = await (await b.newContext()).newPage()
p.on('response', log('brand'))
await p.goto(BASE, { waitUntil: 'domcontentloaded' })
await p.getByRole('button', { name: /^Log in$/ }).first().click()
await p.fill('input[name="email"]', BRAND_EMAIL)
await p.fill('input[name="password"]', PASSWORD)
await p.getByRole('button', { name: /^Enter workspace$/i }).click()
await p.waitForURL(/\/(workflow|dashboard|campaigns)/, { timeout: 120_000 })
await p.getByRole('link', { name: /^Content$/i }).first().click()
await p.waitForTimeout(4000)
await p.locator('select').first().selectOption({ label: 'Autumn Layers' }).catch(() => {})
await p.waitForTimeout(4000)

const handOff = p.getByRole('button', { name: /^Hand over to creator$/i })
if (await handOff.count()) {
  console.log('  handing over so the creator can edit…')
  await handOff.first().click()
  await p.waitForTimeout(5000)
}

// ---- creator: edit, save, hand back ----
const c = await (await b.newContext()).newPage()
c.on('response', log('creator'))
await c.goto(PORTAL, { waitUntil: 'domcontentloaded' })
await c.locator('input').first().fill(CREATOR_EMAIL)
await c.locator('input[type=password]').first().fill(PASSWORD)
await c.getByRole('button', { name: /sign in|log in/i }).first().click()
await c.waitForTimeout(6000)

const card = c.locator('button.cp-card')
ok('creator has the page', (await card.count()) > 0)
if (await card.count()) {
  await card.first().click()
  await c.waitForTimeout(6000)

  // Select the Hero section, then type into its headline.
  await c.getByRole('button', { name: /^Hero/ }).first().click().catch(() => {})
  await c.waitForTimeout(1500)
  const headline = c.getByPlaceholder('What are you selling?')
  ok('hero headline field is editable', (await headline.count()) > 0)
  if (await headline.count()) {
    await headline.first().fill(MARKER)
    await c.waitForTimeout(1200)
  }

  const save = c.getByRole('button', { name: /^Save page$/i })
  ok('save button present', (await save.count()) > 0)
  if (await save.count()) {
    await save.first().click()
    await c.waitForTimeout(6000)
  }

  const back = c.getByRole('button', { name: /Send back to/i })
  if (await back.count()) {
    await back.first().click()
    await c.waitForTimeout(6000)
  }
}

// ---- brand: is the marker there? ----
const p2 = await (await b.newContext()).newPage()
p2.on('response', async (r) => {
  if (/\/api\/landing-templates(\?|$)/.test(r.url()) && r.status() === 200) {
    const t = await r.text().catch(() => '')
    console.log('  [brand sees] marker in payload:', t.includes(MARKER))
    const m = t.match(/"turn":"?([a-z]+)"?/)
    console.log('  [brand sees] turn=', m ? m[1] : '(absent)')
  }
})
await p2.goto(BASE, { waitUntil: 'domcontentloaded' })
await p2.getByRole('button', { name: /^Log in$/ }).first().click()
await p2.fill('input[name="email"]', BRAND_EMAIL)
await p2.fill('input[name="password"]', PASSWORD)
await p2.getByRole('button', { name: /^Enter workspace$/i }).click()
await p2.waitForURL(/\/(workflow|dashboard|campaigns)/, { timeout: 120_000 })
await p2.getByRole('link', { name: /^Content$/i }).first().click()
await p2.waitForTimeout(4000)
await p2.locator('select').first().selectOption({ label: 'Autumn Layers' }).catch(() => {})
await p2.waitForTimeout(6000)

// The editor renders into INPUT VALUES, which textContent does not include -- checking the body
// text reported a failure while the field held the creator's words. Read the field.
await p2.getByRole('button', { name: /^Hero/ }).first().click().catch(() => {})
await p2.waitForTimeout(2000)
const heroValue = await p2.getByPlaceholder('What are you selling?').first().inputValue().catch(() => '')
ok('the brand can SEE the creator edit on screen', heroValue.includes(MARKER))
console.log('  brand hero field:', JSON.stringify(heroValue))
console.log('  marker was:', JSON.stringify(MARKER))

await b.close()
