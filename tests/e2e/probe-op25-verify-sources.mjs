/**
 * Reads back what the OP-25 probe created and reports each lead's classificationSource.
 *
 *   node tests/e2e/probe-op25-verify-sources.mjs <email> <password>
 *
 * The sign-up response does not surface the classification, so the ceiling's actual effect —
 * model before it, keyword matcher after — can only be seen on the stored creator rows. That
 * column is the same signal the product shows a brand, which is why it is the right thing to
 * assert against rather than an OpenAI invoice.
 */
const BASE = process.env.PROBE_BASE || 'https://api.tejdux.com'
const EMAIL = process.argv[2]
const PASSWORD = process.argv[3] || 'DemoPass123!'

if (!EMAIL) {
  console.error('usage: node tests/e2e/probe-op25-verify-sources.mjs <email> [password]')
  process.exit(1)
}

const login = await fetch(BASE + '/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
})
const auth = await login.json()
const token = auth.accessToken
if (!token) {
  console.error('login failed (' + login.status + '): ' + JSON.stringify(auth).slice(0, 300))
  process.exit(1)
}

const res = await fetch(BASE + '/api/creators?brandId=' + auth.brandId + '&size=200', {
  headers: { Authorization: 'Bearer ' + token },
})
const body = await res.json()
const rows = Array.isArray(body) ? body : (body.items || body.content || [])

const probes = rows
  .filter((c) => String(c.handle || '').startsWith('op25probe'))
  .map((c) => ({
    n: Number(String(c.handle).split('_').pop()) || 0,
    handle: c.handle,
    source: c.classificationSource ?? '(null)',
    niche: c.niche ?? '(null)',
  }))
  .sort((a, b) => a.n - b.n)

console.log('\n  ' + probes.length + ' probe creators found\n')
for (const p of probes) {
  if (p.n <= 2 || p.n >= 29) {
    console.log(`   #${String(p.n).padStart(2)}  source=${p.source}  niche=${p.niche}`)
  }
}

const tally = probes.reduce((acc, p) => ((acc[p.source] = (acc[p.source] || 0) + 1), acc), {})
console.log('\n  tally by source:', JSON.stringify(tally))

const early = probes.filter((p) => p.n > 0 && p.n <= 30)
const late = probes.filter((p) => p.n > 30)
console.log('  within ceiling (1-30):', JSON.stringify(
  early.reduce((a, p) => ((a[p.source] = (a[p.source] || 0) + 1), a), {})))
console.log('  past ceiling  (31+) :', JSON.stringify(
  late.reduce((a, p) => ((a[p.source] = (a[p.source] || 0) + 1), a), {})))
console.log('')
