import { chromium } from 'playwright'
import { readFileSync } from 'node:fs'
const [email, password] = readFileSync(process.env.UI_CHECK_CREDS, 'utf8').trim().split('\n')
const b = await chromium.launch(); const p = await b.newPage()
const logs = []
p.on('console', (m) => logs.push(m.type() + ': ' + m.text().slice(0, 220)))
p.on('pageerror', (e) => logs.push('pageerror: ' + e.message.slice(0, 220)))
await p.goto('https://app.tejdux.com/', { waitUntil: 'networkidle', timeout: 60000 })
await p.getByRole('button', { name: /^log in$/i }).first().click()
await p.waitForTimeout(1200)
await p.locator('input[name="email"]').first().fill(email)
await p.locator('input[name="password"]').first().fill(password)
await p.getByRole('button', { name: /enter workspace/i }).first().click()
await p.waitForTimeout(6000)
await p.getByRole('link', { name: /^creators$/i }).first().click()
await p.waitForTimeout(4500)
console.log('  all console output:')
console.log(logs.length ? logs.map(l => '    ' + l).join('\n') : '    (silent)')
await b.close()
