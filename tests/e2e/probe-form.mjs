import { chromium } from 'playwright'
const b = await chromium.launch(); const p = await b.newPage()
await p.goto('https://app.tejdux.com/', { waitUntil: 'networkidle', timeout: 60000 })
const buttons = await p.getByRole('button').allInnerTexts()
console.log('  buttons:', JSON.stringify(buttons.slice(0, 10)))
const inputs = await p.locator('input').evaluateAll((els) =>
  els.map((e) => ({ type: e.type, name: e.name, id: e.id, ph: e.placeholder })))
console.log('  inputs:', JSON.stringify(inputs.slice(0, 8)))
const links = await p.getByRole('link').allInnerTexts()
console.log('  links:', JSON.stringify(links.slice(0, 8)))
await b.close()
