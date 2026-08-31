import { chromium } from '@playwright/test'

/**
 * Is the AI allowance actually metering in production?
 *
 *   node tests/e2e/probe-ai-allowance.mjs
 *
 * Signs a brand up, generates a page, and checks the call was RECORDED — the failure this exists
 * to catch is a ceiling that is deployed and counting nothing, which looks identical to a working
 * one until the month somebody runs up a bill.
 */
const BASE = 'https://tejdux.com'
const STAMP = Date.now().toString().slice(-6)
const EMAIL = `ai.probe.${STAMP}@tejdux.test`
const PASSWORD = 'DemoPass123!'

const b = await chromium.launch()
const ok = (m, v) => console.log((v ? 'PASS ' : 'FAIL ') + m)
const p = await (await b.newContext()).newPage()

let generations = 0
p.on('response', async (r) => {
  const u = r.url()
  if (/campaign-pages\/(generate|variants\/regenerate|sections\/rewrite)/.test(u)) {
    generations += 1
    let t = ''
    try { t = (await r.text()).slice(0, 260) } catch {}
    console.log('  [gen]', r.status(), u.replace(/^https:\/\/[^/]+/, ''))
    const g = t.match(/"generator":"([a-z]+)"/)
    const f = t.match(/"fallback":(true|false)/)
    console.log('      generator=', g ? g[1] : '(absent)', ' fallback=', f ? f[1] : '(absent)')
  }
  if (/ai-generation-usage/.test(u)) {
    let t = ''
    try { t = (await r.text()).slice(0, 200) } catch {}
    console.log('  [usage]', r.status(), r.request().method(), t)
  }
})

await p.goto(BASE, { waitUntil: 'domcontentloaded' })
await p.getByRole('button', { name: /^Sign up$/ }).first().click()
await p.fill('input[name="fullName"]', 'AI Probe')
await p.fill('input[name="brand"]', 'AI Probe Brand')
await p.fill('input[name="email"]', EMAIL)
await p.fill('input[name="password"]', PASSWORD)
await p.locator('input[name="acceptedTerms"]').check()
await p.getByRole('button', { name: /^Create workspace$/i }).click()
await p.waitForURL(/\/(workflow|dashboard|campaigns)/, { timeout: 120_000 })

await p.getByRole('link', { name: /^Campaigns$/i }).first().click()
const nb = p.getByRole('button', { name: /^New campaign$/ })
await nb.first().waitFor({ state: 'visible', timeout: 30_000 })
await nb.first().click()
await p.locator('#campaign-name').waitFor({ state: 'visible', timeout: 15_000 })
await p.locator('#campaign-name').fill('AI Probe Campaign')
await p.getByRole('button', { name: /^Create campaign$/i }).click()
await p.waitForTimeout(4000)

await p.getByRole('link', { name: /^Content$/i }).first().click()
await p.waitForTimeout(4000)
await p.locator('select').first().selectOption({ label: 'AI Probe Campaign' })
await p.waitForTimeout(3500)

// Open "Start from a campaign goal" and generate.
const summary = p.locator('summary', { hasText: /Start from a campaign goal/i })
ok('the goal generator is on the page', (await summary.count()) > 0)
if (await summary.count()) {
  await summary.first().click()
  await p.waitForTimeout(1500)

  const goal = p.locator('textarea, input[type=text]').filter({ hasNot: p.locator('[readonly]') })
  const fields = await goal.count()
  console.log('  fields in the generator:', fields)
  // #cpg-goal is the ONLY required field -- canGenerate gates on it alone, so the button stays
  // disabled until it has text and a loose textarea guess leaves it that way.
  const brief = p.locator('#cpg-goal')
  await brief.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {})
  await brief.fill('Sell an autumn linen collection to people who walk to work in the rain.')
  await p.waitForTimeout(1000)
  // The generator's own button, not 'Draft with AI' (which drafts the BRIEF) and not
  // 'Create brief'. A loose match picked the first of the three and generated nothing.
  const go = p.getByRole('button', { name: /^Generate page drafts$/i })
  console.log('  generator buttons:', await go.count())
  for (let i = 0; i < Math.min(4, await go.count()); i++) {
    console.log('   ', JSON.stringify((await go.nth(i).textContent()).trim()))
  }
  if (await go.count()) {
    await go.first().click().catch((e) => console.log('  click err', e.message.split('\n')[0]))
    // Generation is a model call: give it room.
    await p.waitForTimeout(45_000)
  }
}

ok('a generation request was made', generations > 0)

// The point of the whole exercise: was it COUNTED? Asked through the app's own session so the
// answer comes from the same tenancy the generation was billed against.
const usage = await p.evaluate(async () => {
  try {
    const r = await fetch('/api/ai-generation-usage/summary', { headers: { Accept: 'application/json' } })
    return { status: r.status, body: (await r.text()).slice(0, 200) }
  } catch (e) { return { status: 0, body: e.message } }
})
console.log('  usage summary:', JSON.stringify(usage))

await b.close()
