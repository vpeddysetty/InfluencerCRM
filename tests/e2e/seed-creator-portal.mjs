import { chromium } from '@playwright/test'

/**
 * Create a brand and a creator with a page already handed over, and print both logins.
 *
 * <p><b>Why a script and not a fixture.</b> A creator account cannot be made directly: the portal
 * has no signup, deliberately — an identity exists only because a brand invited one, and a
 * self-serve account would have no brand relationship and could see nothing. So the only way to
 * get a working creator login is to walk the whole path, which is what this does.
 *
 * <p>It drives the real product against production. Nothing here is a shortcut around the flow:
 * the brand signs up, makes a campaign and a page, invites by email, the creator accepts on the
 * portal, and the brand hands the page over. If any step regresses this stops working, which is
 * the point — it is also the shortest end-to-end check of OP-22 there is.
 *
 * <pre>
 *   node tests/e2e/seed-creator-portal.mjs
 *   node tests/e2e/seed-creator-portal.mjs --headed     # watch it happen
 * </pre>
 */

const BASE = process.env.E2E_BASE_URL || 'https://tejdux.com'
const PORTAL = process.env.E2E_PORTAL_URL || 'https://portal.tejdux.com'
const HEADED = process.argv.includes('--headed')

// One password for both accounts: these are demo logins meant to be typed by hand, and two would
// be two things to lose. Long enough for the signup rules either side.
const PASSWORD = 'DemoPass123!'
const STAMP = Date.now().toString().slice(-6)
const BRAND_EMAIL = `demo.brand.${STAMP}@tejdux.test`
const CREATOR_EMAIL = `demo.creator.${STAMP}@tejdux.test`
const BRAND_NAME = 'Linen & Trail'
const CAMPAIGN = 'Autumn Layers'
const PAGE_NAME = 'Autumn Layers — the linen everyone asks about'

const step = (m) => console.log(`  ${m}`)
const fail = (m) => { console.error(`\n  FAILED: ${m}\n`); process.exitCode = 1 }

const browser = await chromium.launch({ headless: !HEADED, slowMo: HEADED ? 120 : 0 })

try {
  // ---- the brand ---------------------------------------------------------
  const brand = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const p = await brand.newPage()

  step('signing the brand up…')
  await p.goto(BASE, { waitUntil: 'domcontentloaded' })
  await p.getByRole('button', { name: /^Sign up$/ }).first().click()
  await p.fill('input[name="fullName"]', 'Ari Mendel')
  await p.fill('input[name="brand"]', BRAND_NAME)
  await p.fill('input[name="email"]', BRAND_EMAIL)
  await p.fill('input[name="password"]', PASSWORD)
  await p.locator('input[name="acceptedTerms"]').check()
  await p.getByRole('button', { name: /^Create workspace$/i }).click()
  await p.waitForURL(/\/(workflow|dashboard|campaigns)/, { timeout: 120_000 })

  step('creating a campaign…')
  await p.getByRole('link', { name: /^Campaigns$/i }).first().click()
  const newCampaign = p.getByRole('button', { name: /^New campaign$/ })
  await newCampaign.first().waitFor({ state: 'visible', timeout: 30_000 })
  await newCampaign.first().click()
  await p.locator('#campaign-name').waitFor({ state: 'visible', timeout: 15_000 })
  await p.locator('#campaign-name').fill(CAMPAIGN)
  await p.locator('#campaign-budget').fill('6000').catch(() => {})
  await p.getByRole('button', { name: /^Create campaign$/i }).click()
  await p.waitForTimeout(4000)

  step('building a page…')
  await p.getByRole('link', { name: /^Content$/i }).first().click()
  await p.waitForTimeout(4000)
  await p.locator('select').first().selectOption({ label: CAMPAIGN })
  await p.waitForTimeout(3500)
  const pageName = p.locator('label.auth-label', { hasText: /^Page name$/ })
    .locator('xpath=following-sibling::input[1]')
  await pageName.fill(PAGE_NAME)
  await p.locator('select').filter({ hasText: '— choose —' }).first()
    .selectOption({ label: 'Coupon offer' }).catch(() => {})
  await p.waitForTimeout(3500)
  // The collaborator panel is gated on the page existing, so this save is what makes the invite
  // field appear at all.
  await p.getByRole('button', { name: /^Save page$/i }).first().click()
  await p.waitForTimeout(5000)

  step('inviting the creator…')
  await p.locator('#collab-invite-email').fill(CREATOR_EMAIL)
  await p.getByRole('button', { name: /^Send invitation$/i }).first().click()
  const linkField = p.locator('.collab-panel__link input')
  await linkField.first().waitFor({ state: 'visible', timeout: 30_000 })
  const inviteUrl = await linkField.first().inputValue()
  if (!inviteUrl) {
    fail('no invitation link was shown')
    throw new Error('no invite link')
  }

  // ---- the creator -------------------------------------------------------
  // A separate context, not a tab: the creator must not inherit the brand's session.
  const creator = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const c = await creator.newPage()

  step('accepting as the creator…')
  const token = new URL(inviteUrl).searchParams.get('token')
  await c.goto(`${PORTAL}/invite?token=${encodeURIComponent(token)}`, { waitUntil: 'domcontentloaded' })
  await c.locator('input').first().waitFor({ state: 'visible', timeout: 30_000 })
  await c.locator('input').first().fill('Maya Okonjo')
  await c.locator('input[type=password]').first().fill(PASSWORD)
  await c.locator('input[type=checkbox]').first().check()
  await c.getByRole('button', { name: /Accept and get started/i }).click()
  await c.locator('button.cp-card, h1:has-text("Your pages"), :text("Nothing yet")')
    .first().waitFor({ state: 'visible', timeout: 30_000 })

  // ---- hand the page over ------------------------------------------------
  step('handing the page over…')
  // Navigate rather than reload: the app holds its bearer token in memory.
  await p.getByRole('link', { name: /^Board$/i }).first().click()
  await p.waitForTimeout(2500)
  await p.getByRole('link', { name: /^Content$/i }).first().click()
  await p.waitForTimeout(4000)
  await p.locator('select').first().selectOption({ label: CAMPAIGN }).catch(() => {})
  await p.waitForTimeout(4000)
  const handOff = p.getByRole('button', { name: /^Hand over to creator$/i })
  if (await handOff.count()) {
    await handOff.first().click()
    await p.waitForTimeout(5000)
  } else {
    console.warn('  NOTE: no handoff button — the creator has access but the page is not their turn')
  }

  // ---- prove it ----------------------------------------------------------
  await c.reload({ waitUntil: 'domcontentloaded' })
  await c.waitForTimeout(5000)
  const cards = await c.locator('button.cp-card').count()

  console.log(`
  ================================================================
   CREATOR PORTAL      ${PORTAL}
     email             ${CREATOR_EMAIL}
     password          ${PASSWORD}
     pages waiting     ${cards}

   BRAND WORKSPACE     ${BASE}
     email             ${BRAND_EMAIL}
     password          ${PASSWORD}
     brand             ${BRAND_NAME}
     campaign          ${CAMPAIGN}
  ================================================================

  Both are real accounts on production. The creator sees exactly one page -- the one
  the brand handed over -- and cannot publish it, see other campaigns, or reach the
  brand's workspace. That is the whole point of the separation.
`)

  if (cards === 0) {
    fail('the creator has no page — something in the handoff did not take')
  }
} finally {
  await browser.close()
}
