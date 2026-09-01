/**
 * Does the public sign-up ceiling actually trip in production? (roadmap OP-25)
 *
 *   node tests/e2e/probe-public-signup-rate-limit.mjs
 *
 * The unit tests pin PublicSignupRateLimiter's arithmetic. What they cannot show is that the
 * limiter is WIRED — that the bean exists in the deployed image, that the controller consults it,
 * and that `prefer_heuristic` reaches the agent. A limiter that is deployed and counting nothing
 * looks identical to a working one until the month somebody runs up a bill.
 *
 * WHY THIS BUILDS ITS OWN PAGE RATHER THAN USING A REAL ONE. Every submission creates a lead with
 * an email, a consent row and personal-data handling. Pointing thirty-one of those at a brand's
 * live page would put junk in a customer's CRM and consent evidence against a real address. This
 * signs up a throwaway `@tejdux.test` account, publishes one page, submits to THAT, and reports
 * what it left behind so it can be removed.
 *
 * WHAT IT ASSERTS, and what it deliberately cannot. Every submission must be ACCEPTED — the
 * ceiling drops the model's opinion, never the lead, and a refused sign-up would be a worse bug
 * than the one being prevented. Past the ceiling the classification must come back stamped
 * `heuristic`. It cannot see the OpenAI bill, so "the model was not called" is inferred from that
 * stamp, which is the same signal the product itself shows a brand.
 */
const BASE = process.env.PROBE_BASE || 'https://api.tejdux.com'
const CEILING = 30
const OVERSHOOT = 3

const STAMP = Date.now().toString().slice(-8)
const EMAIL = `op25.probe.${STAMP}@tejdux.test`
const PASSWORD = 'DemoPass123!'

let pass = 0
let fail = 0
const ok = (msg, cond) => {
  console.log((cond ? '  PASS  ' : '  FAIL  ') + msg)
  cond ? pass++ : fail++
}

const post = async (path, body, token) => {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* not json */ }
  return { status: res.status, json, text }
}

console.log('\nOP-25 — public sign-up rate limit, against ' + BASE + '\n')

// ---- 1. a throwaway brand -------------------------------------------------
const signup = await post('/api/auth/signup', {
  // acceptedTerms is mandatory: consent is checked BEFORE the account exists, so omitting it
  // fails the signup rather than creating an account with no consent record.
  email: EMAIL, password: PASSWORD, brandName: 'OP25 Probe ' + STAMP, acceptedTerms: true,
})
const token = signup.json?.token || signup.json?.accessToken
if (!token) {
  console.error('Could not sign up (' + signup.status + '): ' + signup.text.slice(0, 300))
  process.exit(1)
}
const brandId = signup.json?.brandId || signup.json?.brand?.id
console.log('  account: ' + EMAIL)
console.log('  brandId: ' + brandId + '\n')

// ---- 2. one published page to submit against ------------------------------
const campaignId = crypto.randomUUID()
const saved = await post('/api/landing-templates/save', {
  brandId,
  campaignId,
  name: 'OP25 Probe Page ' + STAMP,
  status: 'published',
  sections: [
    { type: 'hero', variant: 'centred', fields: { headline: 'OP25 probe — delete me', subheadline: '', eyebrow: '', ctaLabel: '' } },
    { type: 'legal', variant: '', fields: { body: 'Probe page. Not a real offer.' } },
  ],
}, token)

const slug = saved.json?.publicSlug
if (!slug) {
  console.error('Could not publish a page (' + saved.status + '): ' + saved.text.slice(0, 400))
  console.error('\nCLEAN UP: account ' + EMAIL + ' was created.')
  process.exit(1)
}
console.log('  page slug: ' + slug + '  (' + saved.status + ')\n')

// ---- 3. submit past the ceiling -------------------------------------------
const sources = []
let accepted = 0
let refused = 0

for (let i = 1; i <= CEILING + OVERSHOOT; i++) {
  const res = await post('/api/public/landing/' + slug + '/signup', {
    handle: 'op25probe' + STAMP + '_' + i,
    platform: 'tiktok',
    email: `op25.lead.${STAMP}.${i}@tejdux.test`,
    name: 'OP25 Probe Lead ' + i,
    acceptedTerms: true,
  })

  if (res.status >= 200 && res.status < 300) accepted++
  else refused++

  const src = res.json?.classification?.source
    || res.json?.creator?.classificationSource
    || res.json?.classificationSource
    || null
  sources.push({ i, status: res.status, src })

  if (i <= 2 || i === CEILING || i > CEILING) {
    console.log(`  #${String(i).padStart(2)}  ${res.status}  source=${src ?? '(not surfaced)'}`)
  }
}

console.log('')

// ---- 4. what it means ------------------------------------------------------
ok('every submission was accepted — the ceiling never refuses a lead',
  refused === 0 && accepted === CEILING + OVERSHOOT)

const seen = sources.map((s) => s.src).filter(Boolean)
if (seen.length === 0) {
  console.log('  NOTE  the response does not surface classification.source, so the heuristic')
  console.log('        switch cannot be observed from outside. Accepted-not-refused is still')
  console.log('        proven above; the model/heuristic split needs a DB or log check.')
} else {
  const past = sources.filter((s) => s.i > CEILING && s.src)
  ok('past the ceiling the classification is heuristic',
    past.length > 0 && past.every((s) => s.src === 'heuristic'))
}

console.log('\n  CLEAN UP — this probe deliberately leaves data behind:')
console.log('    account : ' + EMAIL)
console.log('    brandId : ' + brandId)
console.log('    page    : ' + slug + ' (published — unpublish or delete)')
console.log('    leads   : ' + accepted + ' under op25probe' + STAMP + '_*\n')

console.log(fail === 0 ? `All ${pass} assertions passed.\n` : `${fail} assertion(s) FAILED.\n`)
process.exit(fail === 0 ? 0 : 1)
