/**
 * Authors and publishes a landing page for ONE existing campaign, using the product's own AI.
 *
 * <p>Split out of `seed-demo-workspace.mjs`, which builds a whole workspace from nothing. This one
 * assumes the account, campaign and coupons already exist and does only the authoring half — which
 * is the part worth re-running, because it is the part that costs a billed model call and the part
 * whose output you actually judge.
 *
 * <p><b>Only the brief is written here.</b> Every word on the finished page comes from
 * `/api/campaign-pages/generate` and the per-section rewrite endpoint. That is the whole point: a
 * script that typed the copy itself would demonstrate the editor and tell you nothing about the
 * generator.
 *
 * <p><b>Driven through the browser, not the API.</b> Posting sections directly would be faster and
 * would skip the tenancy resolution, the plan check and the slug assignment that a real save goes
 * through — so it could leave a page the product itself would never produce.
 *
 * Usage:
 *   node author-landing-page.mjs --campaign "Spring Linen Preview" \
 *     --email demo.linen.202608260205@tejdux.test --password 'DemoPass123!'
 *
 * Optional:
 *   --goal / --audience / --offer / --creator / --cta / --tone   the brief (all have defaults)
 *   --template "Story-led"    start from a built-in template instead of the AI
 *   --draft                   leave it as a draft rather than publishing
 *   --rewrite Warmer          which per-section rewrite to apply, or "none"
 *   --headed                  watch it happen
 */
import { chromium } from 'playwright'

// ---- arguments -------------------------------------------------------------
const argv = process.argv.slice(2)
const arg = (name, fallback = '') => {
  const i = argv.indexOf(`--${name}`)
  return i !== -1 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : fallback
}
const flag = (name) => argv.includes(`--${name}`)

const BASE = process.env.E2E_BASE_URL || 'https://tejdux.com'
const EMAIL = arg('email', process.env.E2E_EMAIL || '')
const PASSWORD = arg('password', process.env.E2E_PASSWORD || '')
const CAMPAIGN = arg('campaign', '')

const BRIEF = {
  goal: arg('goal', 'Preview the spring linen collection to our existing winter customers'),
  audience: arg('audience', 'Existing customers 25-45 who bought winter layers'),
  offer: arg('offer', '15% off the first order'),
  creator: arg('creator', '@mayawears'),
  cta: arg('cta', 'Shop the preview'),
  tone: arg('tone', 'Clean, premium, understated'),
}
const TEMPLATE = arg('template', '')
const REWRITE = arg('rewrite', 'Warmer')
const PUBLISH = !flag('draft')

if (!EMAIL || !PASSWORD || !CAMPAIGN) {
  console.error('usage: node author-landing-page.mjs --campaign "<name>" --email <email> --password <password>')
  console.error('       (or set E2E_EMAIL / E2E_PASSWORD)')
  process.exit(2)
}

const log = (m) => console.log(m)
const pause = (page, ms = 500) => page.waitForTimeout(ms)

/** Click a nav-rail link by its exact label. Substring matching also hits "Skip to content". */
async function goto(page, label) {
  await page.keyboard.press('Escape').catch(() => {})
  const link = page.getByRole('link', { name: label, exact: true }).first()
  await link.click({ timeout: 15000 }).catch(() => link.click({ force: true }))
  await page.waitForLoadState('networkidle')
}

const browser = await chromium.launch({ headless: !flag('headed') })
const page = await browser.newPage({ viewport: { width: 1500, height: 1000 }, baseURL: BASE })
let generatorUsed = 'not used (template)'
let publicLinks = []
let ok = false

try {
  // ---- sign in -------------------------------------------------------------
  await page.goto('/', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: 'Log in', exact: true }).first().click()
  await pause(page, 1200)
  await page.fill('input[name="email"]', EMAIL)
  await page.fill('input[name="password"]', PASSWORD)
  await page.locator('button[type=submit]:not([disabled])').first().click()
  await page.waitForLoadState('networkidle')
  await page.waitForSelector('a:has-text("Content")', { timeout: 40000 })
  log(`signed in as ${EMAIL}`)

  // ---- pick the campaign ---------------------------------------------------
  await goto(page, 'Content')
  await pause(page, 2000)

  const picker = page.locator('select').first()
  const options = (await picker.locator('option').allTextContents()).filter(Boolean)
  const match = options.find((o) => o.trim() === CAMPAIGN) || options.find((o) => o.includes(CAMPAIGN))
  if (!match) {
    throw new Error(`campaign "${CAMPAIGN}" not found. Available: ${options.slice(1).join(', ')}`)
  }
  await picker.selectOption({ label: match })
  await pause(page, 3000)
  log(`campaign: ${match}`)

  if (TEMPLATE) {
    // ---- start from a built-in template, no model call ---------------------
    const templatePicker = page.locator('select')
      .filter({ has: page.locator('option[value=""]:text-is("— choose —")') }).first()
    await templatePicker.scrollIntoViewIfNeeded().catch(() => {})
    await templatePicker.selectOption({ label: TEMPLATE })
    log(`template: ${TEMPLATE}`)
    await page.waitForSelector('iframe[title="Page preview"]', { timeout: 60000 })
    await pause(page, 2000)
  } else {
    // ---- let the AI write it -----------------------------------------------
    const briefPanel = page.getByText('Start from a campaign goal').first()
    if (await briefPanel.count()) {
      await briefPanel.click()
      await pause(page, 900)
    }
    await page.locator('#cpg-goal').fill(BRIEF.goal)
    await page.locator('#cpg-audience').fill(BRIEF.audience)
    await page.locator('#cpg-offer').fill(BRIEF.offer)
    await page.locator('#cpg-creator').fill(BRIEF.creator)
    await page.locator('#cpg-cta').fill(BRIEF.cta)
    await page.locator('#cpg-tone').fill(BRIEF.tone)
    await pause(page, 900)

    log('asking the AI for drafts (billed call)…')
    await page.getByRole('button', { name: /Generate page drafts/i }).click()
    // Three drafts routinely take 30-60s; the server's own ceiling is 120s.
    await page.waitForSelector('button:has-text("Use this draft")', { timeout: 150000 })
    await pause(page, 1500)

    // Read which generator actually answered rather than assuming. The service substitutes the
    // template generator when the model is unavailable, and reporting that as an AI draft would
    // make the whole exercise misleading.
    const bodyText = await page.locator('body').innerText()
    generatorUsed = /fallback|template draft/i.test(bodyText) ? 'template (AI fell back)' : 'anthropic'
    log(`generator: ${generatorUsed}`)

    await page.getByRole('button', { name: /Use this draft/i }).first().click()
    await pause(page, 2500)
    await page.waitForSelector('iframe[title="Page preview"]', { timeout: 60000 })
    await pause(page, 2000)

    // The other half of the feature: rewrite one section in place.
    if (REWRITE && REWRITE.toLowerCase() !== 'none') {
      const heroRow = page.getByRole('button', { name: /^Hero/ }).first()
      if (await heroRow.count()) {
        await heroRow.click()
        await pause(page, 800)
        const button = page.getByRole('button', { name: REWRITE, exact: true })
        if (await button.count()) {
          log(`asking the AI to rewrite the hero — "${REWRITE}" (billed call)…`)
          await button.click()
          await pause(page, 12000)
        } else {
          log(`(no "${REWRITE}" rewrite button; skipped)`)
        }
      }
    }
  }

  // ---- publish and save ----------------------------------------------------
  if (PUBLISH) {
    // The page's own status select is the two-option Draft/Published one. Found by its options
    // rather than by position, because the brief form above has a status select too and a
    // positional guess would set the wrong one.
    const selects = page.locator('select')
    const n = await selects.count()
    for (let i = 0; i < n; i++) {
      const opts = await selects.nth(i).locator('option').allTextContents()
      if (opts.length === 2 && opts.includes('Draft') && opts.includes('Published')) {
        await selects.nth(i).selectOption({ label: 'Published' })
        break
      }
    }
    await pause(page, 800)
  }

  await page.getByRole('button', { name: /Save page/i }).click()
  await page.waitForLoadState('networkidle')
  await pause(page, 3500)

  const feedback = await page.locator('body').innerText()
  if (!/saved|slug/i.test(feedback)) {
    throw new Error('the page did not report a successful save')
  }

  publicLinks = [...new Set(await page.locator('a[href*="/s/"]')
    .evaluateAll((as) => as.map((a) => a.href)))]
  await page.screenshot({ path: 'authored-page.png', fullPage: true })
  ok = true
} catch (e) {
  log('ERROR: ' + String(e.message).slice(0, 400))
  await page.screenshot({ path: 'authored-page-error.png' }).catch(() => {})
} finally {
  await browser.close()
}

log('\n================ AUTHORED PAGE ================')
log(`  Campaign:  ${CAMPAIGN}`)
log(`  Status:    ${ok ? (PUBLISH ? 'published' : 'saved as draft') : 'FAILED'}`)
log(`  Copy by:   ${generatorUsed}`)
if (publicLinks.length) {
  log('  Public pages (one per creator with a coupon):')
  for (const l of publicLinks) log(`    ${l}`)
} else if (ok) {
  log('  No public links yet — the campaign has no coupons, so there is')
  log('  no per-creator page to link to. Add a coupon and re-save.')
}
log('  Screenshot: tests/e2e/authored-page.png')
log('==============================================')

process.exit(ok ? 0 : 1)
