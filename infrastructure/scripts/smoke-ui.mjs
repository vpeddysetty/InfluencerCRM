/**
 * Does the deployed UI actually RENDER, or does it merely return 200?
 *
 *   node infrastructure/scripts/smoke-ui.mjs                        # every deployed host
 *   node infrastructure/scripts/smoke-ui.mjs content shell          # a subset
 *
 * WHY THIS EXISTS. On 2026-09-01 the GrapesJS removal left three calls to a `setEditorMode` that no
 * longer existed. Vite compiles a call to an undefined identifier without complaint, so the bundle
 * built clean, uploaded clean, and every check that mattered stayed green: S3 had the files,
 * CloudFront served them, and `curl` got 200 with a real index.html. The page threw
 * "setEditorMode is not defined" on mount and rendered COMPLETELY BLANK in production for about
 * fifteen minutes, and the thing that eventually noticed was an end-to-end journey failing on a
 * selector three steps later.
 *
 * A 200 from a static host says the FILE is there. It says nothing about whether the app inside it
 * runs. That gap is the whole reason for this script.
 *
 * WHAT IT ASSERTS, deliberately narrow: the document loads, the JS executes without throwing an
 * uncaught error, and the page puts something on screen. It is not a functional test -- the E2E
 * suite is, and it takes minutes rather than seconds. This is the check that belongs between
 * `deploy-ui.sh` finishing and anyone calling the deploy done.
 *
 * Exit code is 1 on any failure, so it can gate a script or a pipeline.
 */
// Resolved from tests/e2e rather than imported by bare name: Playwright is a devDependency of the
// e2e suite, not of the repo root, and this script deliberately lives beside the deploy script it
// guards rather than inside the test tree. A bare import works only when run from tests/e2e, which
// is exactly the sharp edge that gets it skipped.
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const e2eRequire = createRequire(pathToFileURL(resolve(here, '../../tests/e2e/package.json')))
// Playwright is CommonJS, so a dynamic import wraps it: the named exports land under `default`
// when Node's interop cannot statically see them. Reading both covers either shape.
const playwright = await import(pathToFileURL(e2eRequire.resolve('playwright')).href)
const chromium = playwright.chromium ?? playwright.default?.chromium

const HOSTS = {
  shell: 'https://app.tejdux.com/',
  content: 'https://content.tejdux.com/',
  campaigns: 'https://campaigns.tejdux.com/',
  creators: 'https://creators.tejdux.com/',
  commerce: 'https://commerce.tejdux.com/',
  finance: 'https://finance.tejdux.com/',
  workflow: 'https://workflow.tejdux.com/',
  'creator-portal': 'https://portal.tejdux.com/',
}

const requested = process.argv.slice(2)
const targets = requested.length
  ? requested.filter((name) => {
      if (!HOSTS[name]) console.warn(`  ? unknown target "${name}" — skipped`)
      return HOSTS[name]
    })
  : Object.keys(HOSTS)

// A federated remote's own host renders a bare mount point rather than an app: it exists to serve
// remoteEntry.js to the shell. So "something on screen" is asserted only where a user actually
// lands, and every host is still checked for a JS error, which is the failure this exists to catch.
const RENDERS_ITS_OWN_UI = new Set(['shell', 'creator-portal'])

const browser = await chromium.launch()
let failed = 0

for (const name of targets) {
  const url = HOSTS[name]
  const context = await browser.newContext()
  const page = await context.newPage()
  const errors = []

  page.on('pageerror', (e) => errors.push(String(e).split('\n')[0].slice(0, 200)))
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text().slice(0, 200))
  })

  let status = 0
  try {
    const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45_000 })
    status = response?.status() ?? 0
    // networkidle would be the stricter wait, but a page that mounts a poller never reaches it.
    // A fixed settle is enough: a mount-time throw happens immediately, which is the case here.
    await page.waitForTimeout(3_000)
  } catch (e) {
    errors.push(`navigation failed: ${String(e).split('\n')[0].slice(0, 160)}`)
  }

  const text = await page.evaluate(() => document.body?.innerText?.trim() ?? '').catch(() => '')
  const painted = await page
    .evaluate(() => (document.getElementById('root') ?? document.body)?.children.length ?? 0)
    .catch(() => 0)

  const problems = []
  if (status !== 200) problems.push(`HTTP ${status}`)
  if (errors.length) problems.push(`${errors.length} JS error(s)`)
  // The blank-page test. A thrown mount leaves #root with no children and no text, which is exactly
  // what shipped on 2026-09-01 while the host answered 200.
  if (RENDERS_ITS_OWN_UI.has(name) && painted === 0 && text.length === 0) {
    problems.push('rendered nothing')
  }

  if (problems.length) {
    failed++
    console.log(`  FAIL  ${name.padEnd(15)} ${url}`)
    problems.forEach((p) => console.log(`          ${p}`))
    errors.slice(0, 3).forEach((e) => console.log(`          → ${e}`))
  } else {
    console.log(`  ok    ${name.padEnd(15)} HTTP ${status}, no JS errors${
      RENDERS_ITS_OWN_UI.has(name) ? `, ${painted} root node(s)` : ''}`)
  }

  await context.close()
}

await browser.close()

console.log('')
if (failed) {
  console.log(`${failed} of ${targets.length} deployed UIs are broken. The files are there; the app is not.`)
  process.exit(1)
}
console.log(`All ${targets.length} deployed UIs load and run.`)
