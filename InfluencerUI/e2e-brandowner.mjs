// Playwright end-to-end brand-owner flow driven through the real UI.
// Run: node e2e-brandowner.mjs
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5173'
const SHOTS = 'e2e-shots'
mkdirSync(SHOTS, { recursive: true })

const stamp = Date.now()
const EMAIL = `owner.ui.${stamp}@luminaraskin.com`
const PASSWORD = 'Lumina!2026'
const BRAND = 'Luminara UI Co'
const NAME = 'Maya UI Rivera'

const log = (...a) => console.log('•', ...a)
let step = 0
async function shot(page, label) {
  step += 1
  const file = `${SHOTS}/${String(step).padStart(2, '0')}-${label}.png`
  await page.screenshot({ path: file, fullPage: true })
  log(`screenshot: ${file}`)
}

const results = []
async function check(name, fn) {
  try {
    await fn()
    results.push({ name, ok: true })
    log(`PASS: ${name}`)
  } catch (err) {
    results.push({ name, ok: false, error: String((err && err.message) || err) })
    console.error(`FAIL: ${name}\n   ${err && err.stack ? err.stack.split('\n').slice(0, 3).join('\n   ') : err}`)
  }
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1700 } })
const page = await context.newPage()
page.on('pageerror', (e) => console.error('  [pageerror]', e.message))
page.on('console', (m) => { if (m.type() === 'error') console.error('  [console.error]', m.text()) })

try {
  // ---- 1. Sign up as a brand owner ----
  await check('sign up brand owner', async () => {
    await page.goto(BASE, { waitUntil: 'networkidle' })
    // Ensure Sign up tab is active
    await page.getByRole('button', { name: 'Sign up' }).click().catch(() => {})
    await page.locator('input[name="fullName"]').fill(NAME)
    await page.locator('input[name="brand"]').fill(BRAND)
    await page.locator('input[name="email"]').fill(EMAIL)
    await page.locator('input[name="password"]').fill(PASSWORD)
    await shot(page, 'signup-filled')
    await page.getByRole('button', { name: /create workspace/i }).click()
    await page.waitForURL(/\/(import|campaigns|creators|workflow)/, { timeout: 20000 })
  })
  await shot(page, 'after-signup')

  // ---- 2. Create creators ----
  const creators = [
    ['Nadia UI Kim', '@nadiakim', 'Instagram', 'nadia@example.com'],
    ['Theo UI Blake', '@theoblake', 'YouTube', 'theo@example.com'],
    ['Iris UI Wen', '@iriswen', 'TikTok', 'iris@example.com'],
  ]
  await check('create creators', async () => {
    await page.getByRole('link', { name: 'Creators' }).click()
    await page.waitForLoadState('networkidle')
    for (const [name, handle, platform, email] of creators) {
      await page.getByPlaceholder('Creator name').fill(name)
      await page.getByPlaceholder('@handle').fill(handle)
      await page.locator('form.inline-form select').first().selectOption({ label: platform })
      await page.getByPlaceholder('Email').fill(email)
      await page.getByRole('button', { name: 'Add creator' }).click()
      await page.waitForTimeout(600)
    }
    const listed = await page.locator('.simple-list li').count()
    if (listed < 3) throw new Error(`expected >=3 creators listed, found ${listed}`)
  })
  await shot(page, 'creators')

  // ---- 3. Create campaigns ----
  const campaigns = [
    ['UI Summer Glow', '25000'],
    ['UI Hydrate Gifting', '9000'],
  ]
  await check('create campaigns', async () => {
    await page.getByRole('link', { name: 'Campaigns' }).click()
    await page.waitForLoadState('networkidle')
    for (const [name, budget] of campaigns) {
      await page.getByPlaceholder('Campaign name').fill(name)
      await page.getByPlaceholder('Budget').fill(budget)
      await page.getByRole('button', { name: 'Add campaign' }).click()
      await page.waitForTimeout(600)
    }
    const listed = await page.locator('.simple-list li').count()
    if (listed < 2) throw new Error(`expected >=2 campaigns listed, found ${listed}`)
  })
  await shot(page, 'campaigns')

  // ---- 4. Workflow: open; a default board auto-creates on first load ----
  await check('open workflow (auto default board)', async () => {
    await page.getByRole('link', { name: 'Workflow' }).click()
    await page.waitForLoadState('networkidle')
    await page.locator('input[type="radio"][name="active-board"]').first().waitFor({ timeout: 10000 })
  })
  await shot(page, 'workflow-initial')

  // ---- 5. Add a new board via the slider drawer ----
  await check('add board via slider drawer', async () => {
    await page.getByRole('button', { name: 'Add board' }).click()
    await page.locator('.edit-drawer').waitFor({ timeout: 6000 })
    await page.locator('.edit-drawer input[type="text"]').first().fill('UI Q3 Pipeline')
    await shot(page, 'board-drawer')
    await page.getByRole('button', { name: /create board/i }).click()
    await page.locator('.edit-drawer').waitFor({ state: 'detached', timeout: 10000 })
    await page.locator('.board-row', { hasText: 'UI Q3 Pipeline' }).first().waitFor({ timeout: 6000 })
  })
  await shot(page, 'board-added')

  // ---- 6. Select the new board (radio) ----
  await check('select new board via radio', async () => {
    const row = page.locator('.board-row', { hasText: 'UI Q3 Pipeline' }).first()
    await row.locator('input[type="radio"]').check()
    await page.waitForTimeout(800)
    // Kanban for that board should render
    await page.getByRole('heading', { name: /UI Q3 Pipeline .* kanban/i }).waitFor({ timeout: 6000 })
  })
  await shot(page, 'board-selected')

  // ---- 7. Add relationship cards via the card drawer ----
  // Creator option labels render as "Name (@handle)".
  const cards = [
    ['UI Card - Nadia glow', 'UI Summer Glow', 'Nadia UI Kim (@nadiakim)'],
    ['UI Card - Theo review', 'UI Summer Glow', 'Theo UI Blake (@theoblake)'],
    ['UI Card - Iris gifting', 'UI Hydrate Gifting', 'Iris UI Wen (@iriswen)'],
  ]
  await check('create relationship cards via drawer', async () => {
    for (const [cardName, campaign, creatorLabel] of cards) {
      await page.getByRole('button', { name: /add relationship card/i }).click()
      await page.locator('.edit-drawer').waitFor({ timeout: 6000 })
      await page.locator('.edit-drawer input[type="text"]').first().fill(cardName)
      const selects = page.locator('.edit-drawer select')
      await selects.nth(0).selectOption({ label: campaign })       // campaign
      await selects.nth(1).selectOption({ label: creatorLabel })   // creator
      await page.getByRole('button', { name: /create card/i }).click()
      await page.locator('.edit-drawer').waitFor({ state: 'detached', timeout: 10000 })
      await page.waitForTimeout(500)
    }
    const count = await page.locator('.card-thumb').count()
    if (count < 3) throw new Error(`expected >=3 card thumbnails, found ${count}`)
  })
  await shot(page, 'cards-created')

  // ---- 8. Drag a card thumbnail onto a stage column ----
  await check('drag card thumbnail onto a stage', async () => {
    const thumb = page.locator('.card-thumb').first()
    const targetColumn = page.locator('.kanban-column').first()
    await thumb.scrollIntoViewIfNeeded()

    // First try Playwright's mouse-based dragTo.
    await thumb.dragTo(targetColumn).catch(() => {})
    await page.waitForTimeout(1000)
    let placed = await page.locator('.kanban-column .kanban-card').count()

    // Fallback: dispatch native HTML5 drag events with a shared DataTransfer,
    // since the app's drop handler reads dataTransfer.getData('text/plain').
    if (placed < 1) {
      log('  mouse dragTo did not place; dispatching native HTML5 DnD events')
      await page.evaluate(() => {
        const src = document.querySelector('.card-thumb')
        const col = document.querySelector('.kanban-column')
        if (!src || !col) return
        const dt = new DataTransfer()
        const fire = (el, type) => el.dispatchEvent(
          new DragEvent(type, { bubbles: true, cancelable: true, dataTransfer: dt }))
        fire(src, 'dragstart')
        fire(col, 'dragenter')
        fire(col, 'dragover')
        fire(col, 'drop')
        fire(src, 'dragend')
      })
      await page.waitForTimeout(1200)
      placed = await page.locator('.kanban-column .kanban-card').count()
    }
    if (placed < 1) throw new Error(`expected >=1 placed card, found ${placed}`)
  })
  await shot(page, 'card-dragged')

  // ---- 9. Search the card pool ----
  await check('search card pool narrows results', async () => {
    const search = page.getByPlaceholder(/search cards/i)
    const before = await page.locator('.card-thumb').count()
    await search.fill('zzz-no-match-term')
    await page.waitForTimeout(500)
    const none = await page.locator('.card-thumb').count()
    if (none !== 0) throw new Error(`nonsense search should show 0 thumbnails, found ${none}`)
    await search.fill('Iris')
    await page.waitForTimeout(500)
    const irisCount = await page.locator('.card-thumb').count()
    log(`  pool before search=${before}; 'zzz'=${none}; 'Iris'=${irisCount}`)
    // 'Iris' should match at most 1 and fewer than the full pool.
    if (irisCount > 1) throw new Error(`'Iris' search should narrow to <=1, found ${irisCount}`)
    await search.fill('')
  })
  await shot(page, 'card-search')
} catch (fatal) {
  console.error('FATAL', fatal)
  await shot(page, 'fatal').catch(() => {})
  results.push({ name: 'fatal error', ok: false, error: String((fatal && fatal.message) || fatal) })
} finally {
  console.log('\n===== UI E2E RESULTS =====')
  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.error ? '  -> ' + r.error : ''}`)
  const failed = results.filter((r) => !r.ok).length
  console.log(`\n${results.length - failed}/${results.length} checks passed`)
  process.exitCode = failed ? 1 : 0
  await browser.close()
}
