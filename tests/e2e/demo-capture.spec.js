// The suite is ESM ("type": "module"); a require() here fails at collection and takes the whole
// run down with it, not just this spec.
import { expect, test } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = join(HERE, 'artifacts')
const FIXTURES = join(HERE, 'fixtures')

/**
 * Records the product demo: each feature is named, then performed.
 *
 * <p><b>The shape changed, and this is the point.</b> The first version signed into a seeded
 * workspace and NAVIGATED -- it moved between pages and held on each, so the narration described
 * features nobody was operating. That reads as a tour rather than a demonstration, and no amount of
 * timing precision fixes it, because the problem was never the timing. Here the spreadsheet is
 * really uploaded and hydrated, the coupons are really generated, the page is really built. The
 * words have something to describe because something is happening.
 *
 * <p><b>It signs UP rather than signing in.</b> Opening on a populated workspace skips the thing a
 * prospect most wants to see -- how little work it is to get started. The workspace is therefore
 * built on camera, which also means this no longer depends on seed-demo-workspace.mjs.
 *
 * <p><b>Beats come in pairs.</b> An `-intro` is spoken over a still screen while the viewer reads
 * what is there; the `-do` begins exactly when the click does. One combined beat would put the
 * words for the action over the seconds before it, which is the same desync in miniature.
 *
 * <p><b>A campaign is created inside the import beat</b>, not given one of its own. Both the bulk
 * coupon generator and the landing page builder are gated on a campaign existing -- with none, the
 * two beats after this film a disabled button and the note "Pick a campaign above". It rides along
 * with the import because that beat is already about getting your data in, and the narration line
 * ends on "the roster is in" either way.
 *
 * <p><b>This is a recording, not a test.</b> It asserts almost nothing on purpose: a failed
 * assertion mid-way leaves truncated footage, and the deliverable here is footage rather than a
 * verdict. The suite next door is what proves the product works.
 *
 * <p>Usage:
 * <pre>
 *   npx playwright test demo-capture.spec.js
 *   node tests/e2e/build-demo.mjs
 * </pre>
 */

const BASE = process.env.E2E_BASE_URL || 'https://tejdux.com'

// Unique per run: signup is real, and a reused address collides with the previous take's account.
const STAMP = Date.now().toString().slice(-8)
const DEMO = {
  name: 'Ari Mendel',
  brand: 'Linen & Trail',
  email: `demo.${STAMP}@tejdux.test`,
  password: 'DemoPass123!',
  campaign: 'Autumn Layers',
}

// Measured from the rendered narration, not estimated -- demo-narrate.mjs prints the real
// durations and flags any beat that outruns its hold.
//
// These are MINIMUMS, not positions. The first version treated them as a timeline and assumed beat
// N started at the sum of the holds before it; page loads added 38.6 seconds of navigation the
// budget never accounted for, so every beat after the first drifted further out of sync with the
// words. The capture now RECORDS where each beat actually began and writes those marks out for the
// render to cut on -- measuring beats guessing, and the guess compounds.
const BEAT = {
  open: 14,
  signupIntro: 10,
  signupDo: 12,
  importIntro: 11,
  importDo: 18,
  couponIntro: 10,
  couponDo: 16,
  boardIntro: 9,
  boardDo: 14,
  pageIntro: 10,
  pageDo: 25,
  numbers: 15,
  close: 17,
}

/**
 * Where each beat began, in seconds from the start of the recording.
 *
 * <p>Written to artifacts/beat-marks.json for build-demo.mjs. Without it the render has to assume
 * the footage runs to the same schedule as the narration, and it never does.
 */
const marks = []
let recordingStart = 0

function mark(id) {
  marks.push({ id, at: (Date.now() - recordingStart) / 1000 })
}

/** Hold the current frame long enough for the narration over it to finish. */
async function hold(page, seconds) {
  await page.waitForTimeout(seconds * 1000)
}

/**
 * Move the mouse to an element before clicking it.
 *
 * <p>Playwright clicks by teleporting the cursor, which on video looks like the UI operating
 * itself. Steering there first is the difference between footage of a product being used and
 * footage of a script running.
 */
async function pointAndClick(page, locator) {
  const box = await locator.boundingBox().catch(() => null)
  if (box) {
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 18 })
    await page.waitForTimeout(300)
  }
  await locator.click()
}

/**
 * Type at reading speed.
 *
 * <p>fill() lands the whole string in one frame, which on video looks like a paste and gives the
 * narration nothing to sit over. The delay is the shot.
 */
async function typeInto(page, locator, text) {
  await locator.click()
  await locator.pressSequentially(text, { delay: 55 })
}

/**
 * Wait for a control, then click it. Every step of every -do beat goes through this or its kin.
 *
 * <p><b>It WAITS rather than glancing.</b> The first version asked `count()` once and treated zero
 * as absent -- but these are module-federation remotes, and `networkidle` fires before the remote's
 * bundle has mounted, so the count is legitimately 0 for a moment on every navigation. Measured on
 * production: "New campaign" reads 0 immediately after networkidle and 1 after an explicit wait.
 * That single glance cost a whole take -- no campaign was created, so the coupon beat filmed "Pick
 * a campaign to see how many coupons will be generated" and the page beat filmed the same refusal.
 *
 * <p>A miss still returns false rather than throwing, for the reason every catch here is empty: one
 * shot short is recoverable in an edit, and a take that dies at beat five is not.
 */
async function clickIfPresent(page, locator, settle = 1500, timeout = 20_000) {
  try {
    await locator.first().waitFor({ state: 'visible', timeout })
  } catch {
    return false
  }
  await pointAndClick(page, locator.first()).catch(() => {})
  await page.waitForTimeout(settle)
  return true
}

/** Same wait-then-act contract as clickIfPresent, for fields. */
async function typeIfPresent(page, locator, text, timeout = 20_000) {
  try {
    await locator.first().waitFor({ state: 'visible', timeout })
  } catch {
    return false
  }
  await typeInto(page, locator.first(), text).catch(() => {})
  return true
}

test.describe('Demo', () => {
  test('The free tier, feature by feature', async ({ page }) => {
    // Generous: thirteen beats plus real work against production. A timeout mid-capture wastes the
    // entire take, and there is no cheap way to resume one.
    test.setTimeout(20 * 60 * 1000)
    mkdirSync(OUT_DIR, { recursive: true })

    // ---- open: the landing page -------------------------------------------
    await page.goto(BASE, { waitUntil: 'domcontentloaded' })
    await page.waitForLoadState('networkidle').catch(() => {})
    recordingStart = Date.now()
    mark('open')
    await hold(page, BEAT.open)

    // ---- signup: what the free tier is, then taking it ---------------------
    // The plan comparison lives further down the landing page, so the intro is spoken over the
    // actual pricing rather than over a form -- the viewer reads the limits while they hear them.
    mark('signup-intro')
    await page.mouse.wheel(0, 500)
    await hold(page, BEAT.signupIntro)
    await page.mouse.wheel(0, -500)

    // "Sign up" is a TAB on the landing page, not a /signup route. Anchored ^...$ because a loose
    // /sign up/i also matches the submit button; brand-owner-journey records the same trap, where
    // clicking the wrong one silently never posts the form.
    await pointAndClick(page, page.getByRole('button', { name: /^Sign up$/ }).first())
    mark('signup-do')
    await typeInto(page, page.locator('input[name="fullName"]'), DEMO.name)
    await typeInto(page, page.locator('input[name="brand"]'), DEMO.brand)
    await typeInto(page, page.locator('input[name="email"]'), DEMO.email)
    await typeInto(page, page.locator('input[name="password"]'), DEMO.password)

    // Consent gates submission: without this tick the button stays disabled and the click below can
    // never land. This is what broke brand-owner-journey.spec.js for three weeks.
    await page.locator('input[name="acceptedTerms"]').check()
    await pointAndClick(page, page.getByRole('button', { name: /^Create workspace$/i }))
    await page.waitForURL(/\/(workflow|dashboard|campaigns)/, { timeout: 120_000 })
    await page.waitForLoadState('networkidle').catch(() => {})
    await hold(page, BEAT.signupDo)

    // ---- import: the spreadsheet, actually uploaded and committed ----------
    await gotoSection(page, /^Import$/i)
    mark('import-intro')
    await hold(page, BEAT.importIntro)

    mark('import-do')
    await runImport(page)
    await createCampaign(page)
    await hold(page, BEAT.importDo)

    // ---- coupons: generated per creator ------------------------------------
    await gotoSection(page, /^Coupons$/i)
    mark('coupon-intro')
    await hold(page, BEAT.couponIntro)

    mark('coupon-do')
    await generateCoupons(page)
    await hold(page, BEAT.couponDo)

    // ---- board -------------------------------------------------------------
    await gotoSection(page, /^Board$/i)
    mark('board-intro')
    await hold(page, BEAT.boardIntro)

    mark('board-do')
    await page.mouse.wheel(0, 250)
    await hold(page, BEAT.boardDo)
    await page.mouse.wheel(0, -250)

    // ---- the page, with the coupon on it -----------------------------------
    await gotoSection(page, /^Content$/i)
    mark('page-intro')
    await hold(page, BEAT.pageIntro)

    mark('page-do')
    await authorPage(page)
    await hold(page, BEAT.pageDo)

    // ---- the numbers -------------------------------------------------------
    await gotoSection(page, /^Revenue$/i)
    mark('numbers')
    await hold(page, BEAT.numbers)

    // ---- close -------------------------------------------------------------
    mark('close')
    await hold(page, BEAT.close)

    writeFileSync(join(OUT_DIR, 'beat-marks.json'), JSON.stringify(marks, null, 2))

    // Nothing is asserted about the product. See the header: a failed assertion here truncates the
    // footage, and proving the product works is the job of every other spec in this directory.
    // The one check is that every beat was recorded, because a short marks file renders as silence
    // over a still frame and is worth failing on -- and it runs after the marks are safely written.
    expect(marks.length).toBe(13)
  })
})

/**
 * Upload the roster and commit it, on camera.
 *
 * <p>Upload alone creates nothing. The real flow is upload -> the visual mapper populates from the
 * headers -> "Run preview" (a dry run, which reports planned ops) -> "Hydrate records" (the commit).
 * The preview step is filmed because it is the narration's actual claim -- that it shows you what
 * it found before it commits to anything -- and skipping straight to hydrate would make that line
 * a thing the viewer has to take on trust.
 */
async function runImport(page) {
  try {
    // The visible drop zone opens a file chooser on click; the input behind it is what
    // setInputFiles needs, and it is reachable without the dialog.
    await page.locator('input[type="file"]').first()
      .setInputFiles(join(FIXTURES, 'creator-roster.csv'))
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(3000)

    // Uploading only completes step 1. The page says so -- "Click a file name in the summary to
    // view columns" -- and steps 2 to 4 stay empty until the batch is selected. A take was lost to
    // assuming the upload was the whole interaction.
    await clickIfPresent(page, page.getByRole('button', { name: /creator-roster\.csv/i }), 3500)

    await resolveMapping(page)

    // "Check before importing" then "Import N records" -- NOT "Run preview"/"Hydrate records".
    // Those are the labels in InfluencerCampaignsUI's ImportPage, which is the remote; production
    // serves the SHELL's bundled InfluencerUI/src/pages/ImportPage.jsx, a later redesign built
    // around four numbered steps. Confirmed from the footage, which showed "Choose your file".
    await clickIfPresent(page, page.getByRole('button', { name: /Check before importing/i }), 5000)
    await clickIfPresent(page, page.getByRole('button', { name: /^Import \d+ records?$/i }), 6000)
  } catch {
    // Deliberately swallowed -- a half-finished import is a shot that can be trimmed, whereas a
    // throw here loses the six beats after it and the whole take with them.
  }
}

/**
 * Answer the four columns the auto-matcher will not guess.
 *
 * <p>The roster is deliberately messy, so "IG handle", "email addr", "Followers" and "Notes" all
 * arrive flagged: each defaults to entity `campaign` with an EMPTY attribute, and step 3 stays
 * disabled while any row is unresolved. That is the product behaving correctly -- it asks rather
 * than guessing, which is the narration's actual claim -- so the capture answers rather than
 * avoiding the question with a tidy fixture.
 *
 * <p>Written through the advanced JSON editor in ONE assignment rather than by driving the two
 * selects per card. Changing a card's entity re-renders it and re-orders the flagged list, so a
 * handle to its sibling select goes stale mid-edit: driving the selects timed out on every row,
 * and merely re-picking the same value cleared the flag while leaving the mapping wrong -- which
 * imported eight blank campaigns and no usable creators.
 */
async function resolveMapping(page) {
  const MAPPING = [
    { spreadsheetColumn: 'Creator Name', targetEntity: 'creator', targetAttribute: 'name' },
    { spreadsheetColumn: 'IG handle', targetEntity: 'creator', targetAttribute: 'handle' },
    { spreadsheetColumn: 'email addr', targetEntity: 'creator', targetAttribute: 'email' },
    { spreadsheetColumn: 'Followers', targetEntity: 'creator', targetAttribute: 'customAttributes' },
    { spreadsheetColumn: 'agreed fee', targetEntity: 'campaign_creator', targetAttribute: 'agreedFee' },
    { spreadsheetColumn: 'Notes', targetEntity: 'campaign_creator', targetAttribute: 'customAttributes' },
  ]
  try {
    await clickIfPresent(page, page.locator('details').first(), 1200)
    const editor = page.locator('textarea').first()
    await editor.waitFor({ state: 'visible', timeout: 15_000 })
    await editor.fill(JSON.stringify(MAPPING, null, 2))
    await page.waitForTimeout(2500)
  } catch {
    // Deliberately swallowed -- see runImport.
  }
}

/**
 * Create the campaign the next two beats need.
 *
 * <p>Not its own beat -- see the header. Bulk coupons and the landing page builder are both gated
 * on one existing, and a fresh signup has none unless the import mapping happened to target a
 * campaign column, which this roster does not.
 */
async function createCampaign(page) {
  try {
    await gotoSection(page, /^Campaigns$/i)
    // Two ways in, and which one is on screen depends on whether the workspace is empty: the header
    // carries "New campaign", the empty state carries "Create your first campaign". Anchored, and
    // tried in turn -- not `.first()` on a loose pattern, because "New campaign" is ALSO the
    // drawer's own title once it opens.
    const opened =
      (await clickIfPresent(page, page.getByRole('button', { name: /^New campaign$/ }), 1200)) ||
      (await clickIfPresent(page, page.getByRole('button', { name: /^Create your first campaign$/ }), 1200))
    if (!opened) {
      return
    }
    await typeIfPresent(page, page.locator('#campaign-name'), DEMO.campaign)
    await typeIfPresent(page, page.locator('#campaign-budget'), '6000')
    await clickIfPresent(page, page.getByRole('button', { name: /^Create campaign$/i }), 3000)
  } catch {
    // Deliberately swallowed -- see runImport.
  }
}

/**
 * Generate a code for every creator on the campaign, in one pass.
 *
 * <p>Bulk rather than single, because "one per creator, automatically" is the narration's claim and
 * filming one code being typed by hand would contradict it. The button's label carries the count
 * ("Generate 8 coupons"), so it is matched loosely on the verb.
 */
async function generateCoupons(page) {
  try {
    await clickIfPresent(page, page.getByRole('button', { name: /Bulk \(per campaign\)/i }), 1500)

    // The first select in the bulk form is the campaign picker, and it is required -- the submit
    // stays disabled until it names a campaign with creators on it, which is exactly what the
    // previous take filmed instead of coupons.
    const campaign = page.locator('select').first()
    await campaign.waitFor({ state: 'visible', timeout: 20_000 }).catch(() => {})
    await campaign.selectOption({ label: DEMO.campaign }).catch(() => {})
    await page.waitForTimeout(1500)

    await typeIfPresent(page, page.getByPlaceholder('Discount value'), '15')
    await clickIfPresent(page, page.getByRole('button', { name: /^Generate .*coupons?$/i }), 4000)
  } catch {
    // Deliberately swallowed -- see runImport.
  }
}

/**
 * Build the campaign page on camera, coupon block included.
 *
 * <p>The SECTION EDITOR, not the block builder. The property defaults to `builder`, but
 * `prod.tfvars` sets `landing_editor = "sections"` -- and that file is gitignored, so grepping the
 * repo for the flag finds only the default and gives the wrong answer. A take was spent filming a
 * page that never appeared because this drove "Add block", which the section editor does not have;
 * its controls are "Start from a template", "+ Offer" and the rewrite buttons.
 *
 * <p>The coupon block is the reason this beat exists -- the creator's own code, on the page their
 * audience lands on. It renders `{{coupon.code}}` per creator, which is the whole argument for
 * building the page here rather than in a page builder that knows nothing about the roster.
 */
async function authorPage(page) {
  try {
    // Campaign first: the builder shows only "Pick a campaign above" until one is chosen.
    const campaign = page.locator('select').first()
    await campaign.waitFor({ state: 'visible', timeout: 20_000 })
    await campaign.selectOption({ label: DEMO.campaign })
    await page.waitForTimeout(3000)

    // The page-name input carries no id, name or placeholder -- only an unassociated <label> above
    // it, so getByLabel cannot reach it either. Anchored to that label instead. A plain
    // `input[type=text]` .first() picks up the campaign BRIEF's hashtag field, which renders above
    // the builder once a campaign is chosen: an earlier take typed the page title into
    // "Required hashtags" and left the page unnamed.
    const pageName = page.locator('label.auth-label', { hasText: /^Page name$/ })
      .locator('xpath=following-sibling::input[1]')
    if (await pageName.isVisible().catch(() => false)) {
      await pageName.fill('')
      await typeInto(page, pageName, 'Autumn Layers — the linen everyone asks about')
    }

    // Start from a template. The picker is the select whose first option is the "— choose —"
    // placeholder; matched on that rather than by position, because five selects render here once
    // a campaign is picked (campaign, brief status, brief template, page status, page template)
    // and their order is not a contract.
    const template = page.locator('select').filter({ hasText: '— choose —' }).first()
    if (await template.count()) {
      // "Coupon offer" for the obvious reason: this beat exists to put the creator's code on the
      // page, and that template opens with the Offer section already in place.
      await template.selectOption({ label: 'Coupon offer' }).catch(() => {})
      await page.waitForTimeout(3500)
    }

    // Then write in it. Selecting a section opens its fields on the right, and the preview beside
    // them is the real renderer rather than a mock -- which is the claim the narration makes.
    await clickIfPresent(page, page.getByRole('button', { name: /^Offer/ }), 2000)
    const headline = page.getByPlaceholder('20% off your first order')
    if (await headline.count()) {
      await headline.first().fill('')
      await typeInto(page, headline.first(), '15% off, and it is her code')
    }
    await page.waitForTimeout(1500)

    // Scroll the built page into shot: the brief form sits above the builder, so the sections and
    // their preview are below the fold when the beat starts.
    await page.mouse.wheel(0, 700)
  } catch {
    // Deliberately swallowed -- see runImport.
  }
}

/**
 * Navigate by nav link, tolerantly.
 *
 * <p>Labels drift as the product changes and a capture that dies on a renamed link wastes a take,
 * so a miss holds on the current screen rather than failing. The footage is then short by one shot
 * instead of absent entirely, which is recoverable in an edit.
 *
 * <p>Callers anchor their patterns (`/^Coupons$/i`), because the nav labels in routeManifest.js are
 * short single words and a loose match crosses between them.
 */
async function gotoSection(page, pattern) {
  const link = page.getByRole('link', { name: pattern }).first()
  try {
    await pointAndClick(page, link)
    await page.waitForLoadState('networkidle')
  } catch {
    // Deliberately swallowed -- see above.
  }
}
