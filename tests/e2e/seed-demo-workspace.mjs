/**
 * Builds a fully populated demo workspace against a running deployment.
 *
 * <p>Creates one brand account, three creators, two campaigns, a coupon per creator on the lead
 * campaign, and a landing page authored in the curated section editor and published — then prints
 * the credentials and the public per-creator links.
 *
 * <p><b>Driven through the browser, not the API.</b> Seeding via HTTP would be faster and would
 * also prove nothing: it would bypass the tenancy resolution, the plan checks and the slug
 * assignment that the real flow goes through, and could leave rows the product itself would never
 * create. Every row here is made the way a user makes it.
 *
 * <p><b>It also has to look worked-in.</b> This seeds the workspace the demo video is filmed in
 * (docs/Demo-Script-Collaborative-Drop.md), and a demo whose revenue view is empty proves nothing:
 * attribution is real, but with no customers there is nothing to attribute. So orders are simulated
 * per creator, through the auth-scoped `/api/attribution/simulate` endpoint that exists for exactly
 * this — not by writing rows the product would never create.
 *
 * <p>Deliberately UNEVEN order counts. Three creators with identical revenue looks generated, and
 * it also hides the one thing the view is for: telling you which partnership was worth more.
 *
 * <p>Usage: E2E_BASE_URL=https://tejdux.com node seed-demo-workspace.mjs
 */
import { chromium } from 'playwright'

const BASE = process.env.E2E_BASE_URL || 'https://tejdux.com'
const STAMP = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
const EMAIL = `demo.linen.${STAMP}@tejdux.test`
const PASSWORD = 'DemoPass123!'
const WORKSPACE = 'Linen & Trail'

const CREATORS = [
  // `orders` and `avgOrder` shape the revenue view. Uneven on purpose: identical numbers across
  // three creators read as fake, and they conceal the comparison the view exists to make.
  { name: 'Maya Okonjo', handle: '@mayawears', email: 'maya@example.com', code: 'MAYA15',
    orders: 14, avgOrder: 128 },
  { name: 'Devon Reyes', handle: '@devonoutside', email: 'devon@example.com', code: 'DEVON15',
    orders: 6, avgOrder: 96 },
  { name: 'Priya Raman', handle: '@priyaknits', email: 'priya@example.com', code: 'PRIYA15',
    orders: 9, avgOrder: 154 },
]

const LEAD_CAMPAIGN = 'Winter Layers 2026'
const SECOND_CAMPAIGN = 'Spring Linen Preview'

const log = (m) => console.log(m)
const pause = (page, ms = 400) => page.waitForTimeout(ms)

async function goto(page, label) {
  await page.keyboard.press('Escape').catch(() => {})
  const link = page.getByRole('link', { name: label, exact: true }).first()
  await link.click({ timeout: 15000 }).catch(() => link.click({ force: true }))
  await page.waitForLoadState('networkidle')
}

const b = await chromium.launch({ headless: true })
const page = await b.newPage({ viewport: { width: 1500, height: 1000 }, baseURL: BASE })
const created = { creators: [], campaigns: [], coupons: [], links: [], orders: [] }
let generatorUsed = 'unknown'

try {
  // ---- sign up -------------------------------------------------------------
  await page.goto('/', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: 'Sign up', exact: true }).first().click()
  await pause(page, 800)
  await page.fill('input[name="fullName"]', 'Sam Alder')
  await page.locator('.auth-accounttype-option').filter({ has: page.locator('input[value="brand"]') }).click()
  await page.waitForSelector('.auth-accounttype-option.selected input[value="brand"]', { timeout: 10000 })
  await page.fill('input[name="brand"]', WORKSPACE)
  await page.fill('input[name="email"]', EMAIL)
  await page.fill('input[name="password"]', PASSWORD)
  const terms = page.locator('input[name="acceptedTerms"]')
  if (await terms.count()) await terms.check().catch(() => {})
  await page.locator('button[type=submit]:not([disabled])').first().click()
  await page.waitForLoadState('networkidle')
  await page.waitForSelector('a:has-text("Creators")', { timeout: 40000 })
  log(`signed up ${EMAIL}`)

  // ---- creators ------------------------------------------------------------
  await goto(page, 'Creators')
  for (const c of CREATORS) {
    await page.getByRole('button', { name: /add creator|new creator/i }).first().click()
    await page.waitForSelector('form.drawer-form', { timeout: 20000 })
    await page.getByPlaceholder('Ari Rivera').first().fill(c.name)
    const h = page.getByPlaceholder('@aririvera').first()
    if (await h.count()) await h.fill(c.handle)
    const e = page.getByPlaceholder('ari@example.com').first()
    if (await e.count()) await e.fill(c.email)
    await page.locator('form.drawer-form button[type=submit]').first().click()
    await page.waitForLoadState('networkidle')
    await pause(page, 900)
    created.creators.push(c.name)
    log(`  creator: ${c.name} ${c.handle}`)
  }

  // ---- campaigns -----------------------------------------------------------
  await goto(page, 'Campaigns')
  for (const name of [LEAD_CAMPAIGN, SECOND_CAMPAIGN]) {
    await page.getByRole('button', { name: /new campaign/i }).first().click()
    await page.waitForSelector('#campaign-name', { timeout: 20000 })
    await page.locator('#campaign-name').fill(name)
    const budget = page.locator('#campaign-budget')
    if (await budget.count()) await budget.fill('5000')
    await page.locator('form.drawer-form button[type=submit]').first().click()
    await page.waitForLoadState('networkidle')
    await pause(page, 1000)
    created.campaigns.push(name)
    log(`  campaign: ${name}`)
  }

  // ---- coupons: one per creator on the lead campaign ------------------------
  await goto(page, 'Coupons')
  await pause(page, 1200)
  for (const c of CREATORS) {
    // The campaign and creator pickers are the first two selects on the single-coupon form.
    const selects = page.locator('form.inline-form select')
    await selects.nth(0).selectOption({ label: LEAD_CAMPAIGN }).catch(() => {})
    await pause(page, 500)
    await selects.nth(1).selectOption({ label: c.name }).catch(() => {})
    // "Use template pattern" off => the custom-code input is the one shown.
    const useTemplate = page.locator('form.inline-form input[type=checkbox]').first()
    if (await useTemplate.isChecked().catch(() => false)) await useTemplate.uncheck()
    await page.getByPlaceholder(/Custom code/i).first().fill(c.code)
    await page.getByPlaceholder('Discount value').first().fill('15')
    const commission = page.getByPlaceholder('Commission value').first()
    if (await commission.count()) await commission.fill('10')
    const landing = page.getByPlaceholder(/Landing URL/i).first()
    if (await landing.count()) await landing.fill('https://linenandtrail.example.com/winter')
    await page.getByRole('button', { name: /generate coupon/i }).first().click()
    await page.waitForLoadState('networkidle')
    await pause(page, 1200)
    created.coupons.push(`${c.code} (${c.name})`)
    log(`  coupon: ${c.code} -> ${c.name}`)
  }

  // ---- author + publish the landing page ------------------------------------
  await goto(page, 'Content')
  await pause(page, 1500)
  await page.locator('select').first().selectOption({ label: LEAD_CAMPAIGN })
  await pause(page, 3000)

  // ---- let the PRODUCT'S AI write the page ----------------------------------
  // The whole point of this run: the copy below is written by
  // /api/campaign-pages/generate (page_generation_provider = anthropic in prod), not by this
  // script. Only the BRIEF is authored here — which is what a brand actually types.
  const briefPanel = page.getByText('Start from a campaign goal').first()
  if (await briefPanel.count()) {
    await briefPanel.click()
    await pause(page, 900)
  }

  await page.locator('#cpg-goal').fill('Launch our winter linen layering collection to existing customers')
  await page.locator('#cpg-audience').fill('Outdoor-minded 25-40, city commuters who hike at weekends')
  await page.locator('#cpg-offer').fill('15% off the first order')
  await page.locator('#cpg-creator').fill(CREATORS[0].handle)
  await page.locator('#cpg-cta').fill('Shop the collection')
  await page.locator('#cpg-tone').fill('Clean, premium, understated')
  await pause(page, 1000)

  log('  asking the AI for drafts (billed call)…')
  await page.getByRole('button', { name: /Generate page drafts/i }).click()
  // Generation routinely takes 30-60s for three drafts; the server's own timeout is 120s.
  await page.waitForSelector('button:has-text("Use this draft")', { timeout: 150000 })
  await pause(page, 1500)

  // Record which generator actually answered. A fallback draft presented as an AI draft would
  // make this whole demo a lie, so it is read from the page rather than assumed.
  const bodyText = await page.locator('body').innerText()
  generatorUsed = /fallback|template draft/i.test(bodyText) ? 'template (AI fell back)' : 'anthropic'
  log(`  generator: ${generatorUsed}`)

  await page.getByRole('button', { name: /Use this draft/i }).first().click()
  await pause(page, 2500)

  // The draft now opens AS SECTIONS — the mapping added in this change. Before it, a generated
  // draft arrived as an opaque html document and the section editor had nothing typed to show.
  await page.waitForSelector('iframe[title="Page preview"]', { timeout: 60000 })
  await pause(page, 2000)

  // Use the AI once more, per section, which is the other half of the feature.
  const heroRow = page.getByRole('button', { name: /^Hero/ }).first()
  if (await heroRow.count()) {
    await heroRow.click()
    await pause(page, 800)
    const warmer = page.getByRole('button', { name: 'Warmer', exact: true })
    if (await warmer.count()) {
      log('  asking the AI to rewrite the hero (billed call)…')
      await warmer.click()
      await pause(page, 12000)
    }
  }

  // Publish, then save.
  const statusSelects = page.locator('select')
  const count = await statusSelects.count()
  for (let i = 0; i < count; i++) {
    const opts = await statusSelects.nth(i).locator('option').allTextContents()
    if (opts.includes('Published') && opts.includes('Draft') && opts.length === 2) {
      await statusSelects.nth(i).selectOption({ label: 'Published' })
      break
    }
  }
  await pause(page, 800)
  await page.getByRole('button', { name: /Save page/i }).click()
  await page.waitForLoadState('networkidle')
  await pause(page, 3000)

  // Collect the public links the page now advertises.
  const links = await page.locator('a[href*="/s/"]').evaluateAll((as) => as.map((a) => a.href))
  created.links = [...new Set(links)]

  // ---- orders, so the revenue view has a shape -----------------------------
  //
  // Through /api/attribution/simulate, which exists for this and is auth-scoped: the brand comes
  // from the caller's own verified token, so this cannot write into a workspace the session cannot
  // reach. Run from inside the page context so the session's bearer token is the one used, rather
  // than minting a second one here.
  created.orders = await page.evaluate(async (creators) => {
    const token = window.localStorage.getItem('token')
        || window.sessionStorage.getItem('token') || ''
    const results = []
    for (const creator of creators) {
      let placed = 0
      for (let i = 0; i < creator.orders; i += 1) {
        // Spread across the last three weeks so the windowed view (influencerRevenue takes a from
        // and a to) has something to slice. A single day would make every window identical.
        const daysAgo = Math.floor((i / creator.orders) * 21)
        const placedAt = new Date(Date.now() - daysAgo * 86400000).toISOString()
        // Vary the amount a little, or every order is the average and the numbers look typed in.
        const amount = creator.avgOrder + ((i % 5) - 2) * 7
        const response = await fetch('/api/attribution/simulate', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: JSON.stringify({
            providerKey: 'mock',
            order: {
              // The coupon code is what ties the order to the creator -- it is the attribution
              // primitive, and the reason it survives being read off a screen in a video.
              couponCode: creator.code,
              externalId: `demo-${creator.code}-${i}`,
              totalAmount: amount,
              currency: 'USD',
              placedAt,
            },
          }),
        })
        if (response.ok) placed += 1
      }
      results.push(`${creator.code}: ${placed}/${creator.orders}`)
    }
    return results
  }, CREATORS)

  await page.screenshot({ path: 'demo-workspace.png', fullPage: true })
} catch (e) {
  log('ERROR: ' + String(e.message).slice(0, 300))
  await page.screenshot({ path: 'demo-workspace-error.png' }).catch(() => {})
} finally {
  await b.close()
}

log('\n================ DEMO WORKSPACE ================')
log(`  URL:       ${BASE}`)
log(`  Email:     ${EMAIL}`)
log(`  Password:  ${PASSWORD}`)
log(`  Workspace: ${WORKSPACE}`)
log(`  Creators:  ${created.creators.join(', ') || '(none)'}`)
log(`  Campaigns: ${created.campaigns.join(', ') || '(none)'}`)
log(`  Coupons:   ${created.coupons.join(', ') || '(none)'}`)
log(`  Orders:    ${(created.orders || []).join('  ') || '(none)'}`)
log(`  Page copy: written by ${generatorUsed}`)
log('  Public pages:')
for (const l of created.links) log(`    ${l}`)
log('===============================================')
