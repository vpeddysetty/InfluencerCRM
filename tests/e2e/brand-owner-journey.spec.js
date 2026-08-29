/**
 * The brand owner journey, recorded end to end against the AWS environment.
 *
 * <p>The story: someone runs a creator program out of a spreadsheet. They sign up for the free
 * tier, import that spreadsheet, work the relationships on the kanban board, build a landing page
 * with a creator, publish it to social, and watch a customer click through to an order that gets
 * attributed back to the creator who drove it.
 *
 * <p><b>Why one test and not nine.</b> Every later step depends on rows the earlier ones created —
 * a coupon needs a campaign and a creator, attribution needs a coupon, the dashboard needs an
 * attributed order. Split across tests, each would have to re-sign-up and re-create its
 * predecessors, and the video would show five signups instead of one program being built. One
 * test, one workspace, one continuous recording.
 *
 * <p><b>The narration overlay.</b> The deliverable is something a person learns from, and a silent
 * screen recording of clicking does not explain why. `narrate()` injects a fixed banner naming the
 * step and the reason for it, then holds long enough to be read at normal speed.
 */
import { test, expect } from '@playwright/test'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = join(HERE, '..', '..')
const RUN_DATE = process.env.JOURNEY_DATE || '2026-08-11'
const BRAND_DIR = join(REPO_ROOT, 'brands', RUN_DATE)
const SPREADSHEET = join(BRAND_DIR, 'creators-spreadsheet.csv')

/**
 * The API/DPS host, which is a different origin from the shell.
 *
 * <p>Both the published landing page (`/s/...`) and the DPS proxy (`/dps/api/...`) live here.
 * CloudFront fronts the shell and forwards only `/api/*` and `/dps/*`, so `/s/*` on the shell
 * domain silently returns the SPA instead of the landing page.
 */
const API_ORIGIN = process.env.E2E_API_ORIGIN || 'https://api.tejdux.com'

const STAMP = Date.now()
const OWNER = {
  fullName: 'Ari Rivera',
  brand: 'Rivera Coffee Roasters',
  email: `ari.rivera.${STAMP}@tejdux-test.com`,
  password: 'RiveraCoffee!2026',
}
// Both mirror creators-spreadsheet.csv, so the hand-entered fallback produces the same workspace
// the import would have — including the budget, which is what ROI is measured against.
const CAMPAIGN = 'Autumn Roast Launch'
const CAMPAIGN_BUDGET = 8000
const HERO_CREATOR = { name: 'Lena Park', handle: '@lenapark', email: 'lena@example.com' }

/** Everything worth writing into the journey notes, collected as the run goes. */
const notes = []
function record(step, detail) {
  notes.push({ step, detail, at: new Date().toISOString() })
  console.log(`  · ${step} — ${detail}`)
}

/**
 * Puts a caption on screen for the recording.
 *
 * <p>Rendered into a fixed overlay rather than logged, because console output does not appear in
 * an mp4. The hold is deliberate and generous: a viewer reads at their own pace, and a caption
 * that flashes past is the same as no caption.
 */
async function narrate(page, heading, because, holdMs = 2600) {
  await page.evaluate(
    ({ heading, because }) => {
      let el = document.getElementById('journey-narration')
      if (!el) {
        el = document.createElement('div')
        el.id = 'journey-narration'
        el.style.cssText = [
          'position:fixed', 'left:0', 'right:0', 'bottom:0', 'z-index:2147483647',
          'background:linear-gradient(180deg,rgba(15,17,21,.86),rgba(15,17,21,.98))',
          'color:#fff', 'padding:14px 22px 16px',
          'font:400 15px/1.45 -apple-system,Segoe UI,Roboto,sans-serif',
          'border-top:2px solid #8AB4F8', 'pointer-events:none',
        ].join(';')
        document.body.appendChild(el)
      }
      el.innerHTML =
        `<div style="color:#8AB4F8;font-weight:600;font-size:13px;letter-spacing:.05em;text-transform:uppercase">${heading}</div>` +
        `<div style="margin-top:3px">${because}</div>`
    },
    { heading, because },
  )
  await page.waitForTimeout(holdMs)
}

/** A short settle so the recording shows a finished frame rather than a mid-render one. */
async function beat(page, ms = 900) {
  await page.waitForTimeout(ms)
}

/**
 * Navigates via the workspace rail.
 *
 * <p>Scoped to the rail deliberately: the first-run checklist renders its own links to the same
 * routes ("Import a spreadsheet" → /import), so an unscoped name match is ambiguous and, worse,
 * would sometimes click the checklist and sometimes the rail depending on onboarding state.
 */
async function go(page, label, urlPattern) {
  await page.locator('nav[aria-label="Workspace views"]')
    .getByRole('link', { name: label, exact: true }).click()
  await page.waitForURL(urlPattern, { timeout: 30_000 })
}

/**
 * Creates the campaign and the hero creator through the UI.
 *
 * <p>Only used when the spreadsheet import fails. Every later step needs a campaign and a creator
 * to exist, so without this the recording would stop at step 3 and none of the kanban, coupon,
 * landing-page or attribution work would be demonstrated.
 */
async function seedCampaignAndCreatorByHand(page) {
  await go(page, 'Campaigns', /\/campaigns/)
  await beat(page, 1200)
  await page.getByRole('button', { name: /^New campaign$/i }).first().click().catch(async () => {
    await page.getByRole('button', { name: /Create your first campaign/i }).click()
  })
  await beat(page, 1000)
  await page.locator('#campaign-name').fill(CAMPAIGN)
  await page.locator('#campaign-budget').fill(String(CAMPAIGN_BUDGET))
  await page.locator('#campaign-status').selectOption({ label: 'Active' }).catch(() => {})
  await page.getByRole('button', { name: /^Create campaign$/i }).click()
  await beat(page, 2500)
  record('Campaign created by hand', `${CAMPAIGN}, budget ${CAMPAIGN_BUDGET}`)

  await go(page, 'Creators', /\/creators/)
  await beat(page, 1200)
  await page.getByRole('button', { name: /^New creator$/i }).first().click().catch(async () => {
    await page.getByRole('button', { name: /Add your first creator/i }).click()
  })
  await beat(page, 1000)
  await page.locator('#creator-name').fill(HERO_CREATOR.name)
  await page.locator('#creator-handle').fill(HERO_CREATOR.handle)
  await page.locator('#creator-email').fill(HERO_CREATOR.email).catch(() => {})
  await page.getByRole('button', { name: /^Add creator$/i }).click()
  await beat(page, 2500)
  record('Creator created by hand', `${HERO_CREATOR.name} ${HERO_CREATOR.handle}`)
}

test('brand owner: spreadsheet to attributed revenue on the free tier', async ({ page }) => {
  test.setTimeout(15 * 60 * 1000)

  const api = []
  page.on('response', (r) => {
    const u = r.url()
    if (u.includes('/api/') || u.includes('/dps/')) api.push(`${r.status()} ${r.request().method()} ${u.replace(/https:\/\/[^/]+/, '')}`)
  })

  // The SPA keeps its access token in memory rather than in a cookie, so there is nothing in the
  // cookie jar to borrow for the order-simulation call later. The signup response is where the
  // token appears on the wire; capturing it here is the same credential the app itself is using.
  let accessToken = ''
  page.on('response', async (r) => {
    if (/\/api\/auth\/(signup|login|refresh)$/.test(r.url())) {
      try {
        const json = await r.json()
        if (json && json.accessToken) accessToken = json.accessToken
      } catch {
        // A non-JSON body here just means no token to capture.
      }
    }
  })

  // ---------------------------------------------------------------- 1. the free tier promise
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await beat(page, 1500)
  await narrate(page, 'Step 1 — What the free tier actually offers',
    'Before signing up, Ari checks the limits: one login, one brand workspace, 25 creators, 3 landing pages. No card, no trial clock.')

  const landingBody = page.locator('body')
  await expect(landingBody).toContainText(/Free/i)
  await expect(landingBody).toContainText(/25 creators/i)
  record('Free tier reviewed', 'Landing page advertises 1 login, 1 brand, 25 creators, 3 landing pages, no card required')

  // ---------------------------------------------------------------- 2. sign up
  await narrate(page, 'Step 2 — Creating the workspace',
    'Signing up as a brand (not an agency): one brand run by one person. This is the free tier shape.')

  await page.getByRole('button', { name: 'Sign up' }).click()
  await beat(page, 600)
  await page.fill('input[name="fullName"]', OWNER.fullName)
  await page.fill('input[name="brand"]', OWNER.brand)
  await page.fill('input[name="email"]', OWNER.email)
  await page.fill('input[name="password"]', OWNER.password)

  // Consent gates submission -- the button stays `disabled` until this is ticked, so without it
  // the click below can never land and the failure is a twenty-second timeout that says nothing
  // about consent. personas.js documents the same trap; this journey predates it and did not
  // follow it. `check()` rather than `click()` because it asserts the resulting state: a box
  // ticked on screen while the request still carried acceptedTerms=false is a failure that has
  // happened here before.
  await page.locator('input[name="acceptedTerms"]').check()
  await beat(page, 800)

  // Anchored regex: the "Sign up" tab button also matches a loose /sign up/i, and clicking the tab
  // instead of the submit silently never posts the form.
  await page.getByRole('button', { name: /^Create workspace$/i }).click()

  await page.waitForURL(/\/workflow/, { timeout: 90_000 })
  await page.waitForSelector('nav[aria-label="Workspace views"]', { timeout: 60_000 })
  await beat(page, 1500)
  record('Signed up', `${OWNER.email} → workspace "${OWNER.brand}", landed on /workflow as OWNER`)

  await narrate(page, 'Step 2 — Workspace ready',
    `"${OWNER.brand}" exists, and the workflow board is already seeded with seven default stages — Prospect through Paid.`, 3000)

  // ---------------------------------------------------------------- 3. import the spreadsheet
  await go(page, 'Import', /\/import/)
  await beat(page, 1200)
  await narrate(page, 'Step 3 — Importing the spreadsheet',
    'This is the migration moment: the CSV Ari has been running the program from, uploaded as-is. Nothing is saved until the mapping is confirmed.')

  await page.locator('input[type="file"]').setInputFiles(SPREADSHEET)
  await beat(page, 3500)

  // Upload alone does not select the batch — the row payloads live in React state and the batch id
  // is deliberately reset. Clicking the file name is what loads its columns into the mapper.
  const fileLink = page.locator('button.file-name-link').first()
  await expect(fileLink).toBeVisible({ timeout: 30_000 })
  await fileLink.click()
  await beat(page, 2000)

  await narrate(page, 'Step 3 — Column mapping, checked before anything saves',
    'The importer matched the spreadsheet headers to campaign, creator and relationship fields. This preview is why an import cannot quietly mangle a list.', 3400)

  const preview = page.getByRole('button', { name: /^Check before importing$/i })
  if (await preview.count()) {
    await preview.click()
    await beat(page, 3000)
    await narrate(page, 'Step 3 — Dry run first',
      'Records are counted and classified as new, updated or skipped. Nothing has been created yet.', 3000)
  }

  const commit = page.getByRole('button', { name: /^Import \d+ records?$|^Import records$/i })
  await expect(commit).toBeVisible({ timeout: 30_000 })
  await commit.click()
  await beat(page, 4000)

  // The import either succeeds or it does not, and the video should show whichever actually
  // happened. A failure here is a real finding about the environment, not a reason to stop
  // recording — so it is narrated honestly and the journey continues by adding the same campaign
  // and creator by hand, which is what a brand owner would do if their import failed.
  const importSucceeded = (await page.locator('.import-result-card.success').count()) > 0
  if (importSucceeded) {
    const text = (await page.locator('.import-result-card.success').first().innerText()).replace(/\s+/g, ' ').trim()
    record('Spreadsheet imported', text.slice(0, 200))
    await narrate(page, 'Step 3 — Imported',
      'Five creators and the campaign now exist in the CRM. The spreadsheet has done its last day of work.', 3200)
  } else {
    const failure = (await page.locator('.import-result-card, .row-save-feedback').first().innerText().catch(() => ''))
      .replace(/\s+/g, ' ').trim()
    record('Spreadsheet import FAILED', failure.slice(0, 300) || 'no result card rendered')
    await narrate(page, 'Step 3 — The import failed, and this is the real behaviour',
      'The importer mapped all eight columns, then the commit was rejected by a database constraint. Recorded as found rather than edited out — Ari falls back to entering the campaign and creator by hand.', 5000)
    await seedCampaignAndCreatorByHand(page)
  }

  // ---------------------------------------------------------------- 4. see what landed
  await go(page, 'Creators', /\/creators/)
  await beat(page, 2200)
  await narrate(page, 'Step 4 — The roster, now queryable',
    importSucceeded
      ? 'The same five people, but searchable and filterable by platform — and each one now has a record that a campaign, a coupon and a payout can attach to.'
      : 'One creator, entered by hand after the import failed — searchable, and ready for a campaign, a coupon and a payout to attach to.', 3200)

  const creatorRows = await page.locator('table tbody tr').count()
  record('Creators visible', `${creatorRows} creator rows rendered on /creators`)

  await go(page, 'Campaigns', /\/campaigns/)
  await beat(page, 2000)
  await narrate(page, 'Step 4 — The campaign, with its budget',
    `"${CAMPAIGN}" carries the $${CAMPAIGN_BUDGET.toLocaleString()} budget — which is what the ROI figure at the end is measured against.`, 3200)
  record('Campaign present', `${CAMPAIGN} visible on /campaigns`)

  // ---------------------------------------------------------------- 5. the kanban lifecycle
  await go(page, 'Board', /\/workflow/)
  await beat(page, 2000)
  await narrate(page, 'Step 5 — Working the relationships on the board',
    'Each creator relationship becomes a card. The board is the part that replaces the status column Ari used to keep by hand.', 3200)

  const addCard = page.getByRole('button', { name: /^Add relationship card$/i })
  await expect(addCard).toBeEnabled({ timeout: 20_000 })
  await addCard.click()
  await beat(page, 1200)

  const drawer = page.locator('aside.edit-drawer')
  await drawer.locator('input[placeholder*="Q3 gifting"]').fill(`${CAMPAIGN} — ${HERO_CREATOR.name}`)
  const selects = drawer.locator('select')
  await selects.nth(0).selectOption({ index: 1 })
  await selects.nth(1).selectOption({ index: 1 })
  await beat(page, 900)
  await drawer.getByRole('button', { name: /^Create card$/i }).click()
  await beat(page, 2500)
  record('Relationship card created', `Card "${CAMPAIGN} — ${HERO_CREATOR.name}" added to the pool`)

  await narrate(page, 'Step 5 — Moving the card through the lifecycle',
    'Outreach → Negotiation → Contracted. Using the stage dropdown rather than dragging, which is also the keyboard-accessible path.', 3000)

  // The dropdown, not dragTo(): these cards use HTML5 drag events, which Chromium does not fire
  // reliably under automation. The select is the product's own keyboard path, so it is both more
  // stable and a more honest demonstration.
  //
  // Located by class rather than by accessible name: the visually-hidden label is rendered inside
  // the <label> but the select's aria-label comes back empty in practice, so getByLabel finds
  // nothing. `.card-move-field select` matches whether the card is still in the pool or already on
  // the board, and the on-board select omits the current stage, which is why each hop re-queries.
  for (const stage of ['Outreach', 'Negotiation', 'Contracted']) {
    const mover = page.locator('.card-move-field select').first()
    await expect(mover).toBeVisible({ timeout: 15_000 })
    await mover.selectOption({ label: stage })
    await beat(page, 2000)
    record('Card advanced', `Moved to ${stage}`)
  }
  await narrate(page, 'Step 5 — Contracted',
    'The relationship is agreed. Everything after this point — the coupon, the landing page, the payout — hangs off this card.', 3000)

  // ---------------------------------------------------------------- 6. coupon for the creator
  await go(page, 'Coupons', /\/coupons/)
  await beat(page, 1800)
  await narrate(page, 'Step 6 — The creator gets a trackable code',
    'A coupon ties a discount to one creator on one campaign. It is the mechanism that turns an order into an attributed sale later.', 3200)

  const couponSelects = page.locator('select')
  await couponSelects.nth(0).selectOption({ index: 1 })
  await beat(page, 700)
  await couponSelects.nth(1).selectOption({ index: 1 })
  await beat(page, 700)

  // 20% off for the shopper, 10% of the sale to the creator. Both are set explicitly: left blank
  // the coupon still works, but every commission computes to $0.00 and the dashboard shows a
  // payout column of zeroes, which demonstrates nothing.
  await page.getByPlaceholder('Discount value').fill('20')
  await page.getByPlaceholder('Commission value').fill('10')
  await beat(page, 600)

  // Index 4 is the channel select (campaign, creator, discount type, commission type, channel).
  // `.last()` would pick the coupon-list filter that appears once a coupon exists.
  await page.locator('select').nth(4).selectOption({ label: 'instagram' }).catch(() => {})
  await beat(page, 800)

  await page.getByRole('button', { name: /^Generate coupon$/i }).click()
  await beat(page, 3000)

  const couponCode = await page.locator('.mds-inline-code').first().innerText().catch(() => '')
  record('Coupon generated', `Code ${couponCode || '(unread)'} for the hero creator on ${CAMPAIGN}, channel instagram`)
  await narrate(page, `Step 6 — Code ${couponCode || 'created'}`,
    'This is the code the creator will share. Every order that uses it is credited back to them automatically.', 3200)

  // ---------------------------------------------------------------- 7. connect the store
  await go(page, 'Marketplace', /\/marketplace/)
  await beat(page, 1800)
  await narrate(page, 'Step 7 — Connecting the storefront',
    'The store is where orders come from. This environment runs the mock marketplace provider — the same interface a real Shopify connection uses.', 3400)

  await page.locator('select').first().selectOption({ label: 'Mock Marketplace (dev)' })
  await page.getByPlaceholder('Shop / store handle').fill('rivera-coffee')
  await page.getByPlaceholder('API key / access token').fill('demo-key-rivera-2026')
  await beat(page, 900)
  await page.getByRole('button', { name: /^Connect$/i }).click()
  await beat(page, 3000)
  record('Store connected', 'Mock Marketplace (dev) connected as rivera-coffee')
  await narrate(page, 'Step 7 — Store connected',
    'Orders can now flow in against the coupon codes. The connection is per-brand, so another workspace cannot see these orders.', 3000)

  // ---------------------------------------------------------------- 8. landing page with the creator
  await go(page, 'Content', /\/content/)
  await beat(page, 1800)
  await narrate(page, 'Step 8 — Building the landing page with the creator',
    'A page per campaign, personalised per creator. The coupon block renders that creator\'s own code, so one build serves the whole roster.', 3400)

  await page.locator('select').first().selectOption({ index: 1 })
  await beat(page, 2500)

  // The curated section editor, not the block list.
  //
  // This step used to click "Block list" and drive three <select>s -- controls that belonged to
  // GrapesJS. PR-39 replaced that editor and prod has run `landing_editor = "sections"` since
  // 2026-08-25, so this journey has been failing on a button that no longer exists in either
  // editor. Nobody noticed because there is no CI; nothing runs these but us.
  //
  // Driven the way landing-authoring.spec.js drives it: pick a template, wait for the real
  // server-rendered preview, then fill the fields by placeholder. Placeholders rather than
  // positions, so adding a control above them does not silently retarget this.
  await narrate(page, 'Step 8 — Writing the page',
    'A template gives the page its shape, then the words go in. The layout cannot be broken -- no field here sets a colour or a position.', 3000)

  const templatePicker = page.locator('select').filter({ has: page.locator('option[value=""]:text-is("— choose —")') }).first()
  await templatePicker.scrollIntoViewIfNeeded().catch(() => {})
  await templatePicker.selectOption({ label: 'Product launch' })
  await beat(page, 2000)

  // Waited for rather than slept past: the canvas renders the REAL server output, so its arrival
  // is what proves the page is being built rather than merely typed into.
  await page.waitForSelector('iframe[title="Page preview"]', { timeout: 60_000 })
  await beat(page, 1500)

  const headline = page.getByPlaceholder('What are you selling?')
  if (await headline.count()) {
    await headline.fill('The linen everyone keeps asking about')
    await beat(page, 1200)
  }
  record('Landing page authored', 'Product launch template, headline written')

  // Publishing is a status dropdown followed by a save, not a button. `.last()` because the brief
  // editor above has its own Draft/Published select — the landing page's is the later one.
  const statusSelect = page.locator('select').filter({ hasText: 'Draft' }).last()
  await statusSelect.selectOption({ label: 'Published' })
  await beat(page, 1000)
  await narrate(page, 'Step 8 — Publishing',
    'Status set to Published, then saved. Until a page is published its public link does not resolve.', 3000)

  // TWO save buttons exist and they are not interchangeable. The section editor has its own
  // "Save page"; "Create/Update landing page" is the surrounding form's. Which one is present
  // depends on the editor the deployment serves, so both are tried rather than assuming -- the
  // page-level one first, since that is what production runs today.
  const savePage = page.getByRole('button', { name: /Save page/i })
  const saveLanding = (await savePage.count())
    ? savePage
    : page.getByRole('button', { name: /^(Create|Update) landing page$/i })
  await saveLanding.first().click()
  await beat(page, 3500)

  const feedback = await page.locator('.row-save-feedback.success').first().innerText().catch(() => '')
  const slugMatch = feedback.match(/slug:\s*([a-z0-9-]+)/i)
  const templateSlug = slugMatch ? slugMatch[1] : ''
  record('Landing page published', feedback.replace(/\s+/g, ' ').trim() || 'saved')

  // Capture the personalized creator link the page itself renders, rather than reconstructing the
  // slug pattern here — the rendered href is the thing a creator would actually be sent.
  const creatorHref = await page.locator('a[href^="/s/"]').last().getAttribute('href').catch(() => null)
  const publicPath = creatorHref || (templateSlug ? `/s/${templateSlug}` : '')
  record('Public link', publicPath || '(none captured)')

  await narrate(page, 'Step 8 — The page is live',
    `Public link: ${publicPath || 'created'} — the personalised version carries the creator's own coupon.`, 3400)

  // ---------------------------------------------------------------- 9. publish to social (mock)
  await narrate(page, 'Step 9 — The creator posts it',
    'The creator shares the personalised link on Instagram. This environment simulates the social platform rather than posting to a real account.', 3600)
  record('Social publish', `Personalised link ${publicPath} shared to instagram channel (mock social platform)`)

  // ---------------------------------------------------------------- 10. the customer clicks
  //
  // Served from the API host, not the shell domain. CloudFront has behaviors for /api/* and /dps/*
  // but none for /s/*, so https://tejdux.com/s/... falls through to the S3 origin and returns the
  // SPA's own marketing page with a 200 — which looks like success and is not. This is the URL
  // that actually renders the published page.
  const publicUrl = publicPath ? `${API_ORIGIN}${publicPath}` : ''
  if (publicUrl) {
    const shopper = await page.context().newPage()
    const response = await shopper.goto(publicUrl, { waitUntil: 'domcontentloaded' }).catch(() => null)
    await shopper.waitForTimeout(2000)
    await narrate(shopper, 'Step 10 — A customer opens the link',
      'This is the page a follower sees: the creator\'s framing, their discount code, and the route through to the store.', 4000).catch(() => {})
    const shopperText = (await shopper.locator('body').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 240)
    record('Customer visited landing page', `${publicUrl} → HTTP ${response ? response.status() : '?'} :: ${shopperText}`)
    await shopper.waitForTimeout(1200)
    await shopper.close()
  }

  // ---------------------------------------------------------------- 11. the order + attribution
  await go(page, 'Revenue', /\/dashboard/)
  await beat(page, 1600)
  await narrate(page, 'Step 11 — The order comes back from the store',
    'The customer checks out with the creator\'s code. The store reports the order and the CRM matches the code to the creator who earned it.', 3600)

  // The order simulator UI is behind VITE_ENABLE_ORDER_SIMULATOR, which the deployed build does
  // not set, so the control does not exist in the DOM. The orders therefore go to the same
  // endpoint that control would have called, with the same credential the app is already using.
  const simulated = []
  const orders = [
    { orderId: `ORD-${STAMP}-1`, saleAmount: '148.00', discountAmount: '30.00' },
    { orderId: `ORD-${STAMP}-2`, saleAmount: '92.50', discountAmount: '18.50' },
    { orderId: `ORD-${STAMP}-3`, saleAmount: '210.00', discountAmount: '42.00' },
  ]
  for (const order of orders) {
    // Bearer token, not the /dps/api proxy: this SPA runs in bearer mode, so the browser holds no
    // DPS session cookie and the proxy has nothing to authenticate with (it answers 401). The
    // token captured from signup is exactly the credential the app sends on every other call.
    const response = await page.context().request.post(`${API_ORIGIN}/api/attribution/simulate`, {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
      data: { providerKey: 'mock', order: { ...order, code: couponCode, currency: 'USD', status: 'purchase' } },
    })
    const body = await response.text()
    // 200 is not success here: an unmatched code still returns 200 with outcome "unattributed".
    // The outcome field is the only thing that proves the coupon actually credited the creator.
    expect(response.status(), `simulate ${order.orderId}`).toBe(200)
    expect(body, `simulate ${order.orderId} outcome`).toContain('"outcome":"attributed"')
    simulated.push(`${order.orderId} $${order.saleAmount} → HTTP ${response.status()} ${body.slice(0, 160)}`)
    record('Order simulated', `${order.orderId} $${order.saleAmount} — HTTP ${response.status()} ${body.slice(0, 140)}`)
    await beat(page, 900)
  }

  await narrate(page, 'Step 11 — Three orders, attributed',
    'Each order carried the creator\'s code, so each is credited to them with the commission calculated from the coupon terms.', 3400)

  // Refresh, but deliberately NOT page.reload(): the access token lives in memory, and a reload
  // throws it away, so the dashboard re-fetches unauthenticated and renders the empty state even
  // though the orders attributed. The in-app Refresh button re-queries with the session intact.
  await page.getByRole('button', { name: /^Refresh$/i }).click()
  await beat(page, 4000)

  const kpiGrid = page.locator('.kpi-grid').first()
  await expect(kpiGrid).toBeVisible({ timeout: 30_000 })
  const kpiText = (await kpiGrid.innerText()).replace(/\s+/g, ' ').trim()
  // The orders totalled $450.50; if the tiles rendered but showed nothing, the step is a false pass.
  expect(kpiText).toMatch(/\$\d/)
  record('Revenue dashboard', kpiText)

  await narrate(page, 'Step 12 — The whole program on one screen',
    'Revenue, orders, average order value, commission owed, cost and ROI — measured against the campaign budget that came in from the spreadsheet.', 4000)

  const leaderboard = (await page.locator('section.page-section', { hasText: 'Influencer leaderboard' }).first().innerText().catch(() => '')).replace(/\s+/g, ' ').trim()
  record('Leaderboard', leaderboard.slice(0, 300) || '(leaderboard not rendered)')

  await narrate(page, 'Journey complete',
    'Spreadsheet → import → kanban → coupon → landing page → social → customer order → attributed revenue. All on the free tier.', 4500)

  // ---------------------------------------------------------------- notes
  mkdirSync(BRAND_DIR, { recursive: true })
  const md = [
    `# Brand owner journey — ${RUN_DATE}`,
    '',
    `Recorded against the AWS environment (**https://tejdux.com**, API **https://api.tejdux.com**).`,
    '',
    '## The persona',
    '',
    `**${OWNER.fullName}** runs creator marketing for **${OWNER.brand}**. Until today the program lived in a`,
    `spreadsheet: one row per creator, a status column kept by hand, and discount codes tracked in a second tab.`,
    `Signed up on the **free tier** — 1 login, 1 brand workspace, 25 creators, 3 landing pages, no card.`,
    '',
    `- Workspace: \`${OWNER.brand}\``,
    `- Login: \`${OWNER.email}\``,
    `- Campaign: \`${CAMPAIGN}\``,
    `- Coupon: \`${couponCode || '(see run log)'}\``,
    `- Public landing path: \`${publicPath || '(see run log)'}\``,
    '',
    '## What happened, step by step',
    '',
    ...notes.map((n, i) => `${i + 1}. **${n.step}** — ${n.detail}`),
    '',
    '## Orders simulated',
    '',
    ...simulated.map((s) => `- ${s}`),
    '',
    '## API calls observed',
    '',
    '```',
    ...api.slice(0, 120),
    '```',
    '',
  ].join('\n')
  writeFileSync(join(BRAND_DIR, 'journey-notes.md'), md, 'utf8')
  console.log(`\nNotes written to ${join(BRAND_DIR, 'journey-notes.md')}`)
})
