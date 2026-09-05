import { chromium } from 'playwright'
const b = await chromium.launch(); const p = await b.newPage()
await p.goto('https://app.tejdux.com/', { waitUntil: 'networkidle', timeout: 60000 })
// Ask the running app to resolve the specifier the gateway uses.
const r = await p.evaluate(async () => {
  try {
    const m = await import('mf_creators/CreatorsPage')
    return { ok: true, keys: Object.keys(m).slice(0, 5) }
  } catch (e) {
    return { ok: false, name: e.constructor.name, msg: String(e.message).slice(0, 220) }
  }
})
console.log('  import("mf_creators/CreatorsPage") ->', JSON.stringify(r))
await b.close()
