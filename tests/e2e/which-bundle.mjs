import { chromium } from 'playwright'
import { readFileSync } from 'node:fs'
const [email, password] = readFileSync(process.env.UI_CHECK_CREDS, 'utf8').trim().split('\n')
const b = await chromium.launch(); const p = await b.newPage()
const loaded = []
const warns = []
p.on('console', (m) => { const t=m.text(); if (t.includes('gateway') || t.includes('remote')) warns.push(t.slice(0,150)) })
const failed = []
p.on('requestfailed', (r) => { if (r.url().includes('creators.tejdux')) failed.push(r.url().split('/').pop()+' '+r.failure()?.errorText) })
p.on('response', (r) => { const u = r.url(); if (u.includes('CreatorsPage')) loaded.push(u.split('/').pop() + ' -> ' + r.status()) })
await p.goto('https://app.tejdux.com/', { waitUntil: 'networkidle', timeout: 60000 })
await p.getByRole('button', { name: /^log in$/i }).first().click()
await p.waitForTimeout(1200)
await p.locator('input[name="email"]').first().fill(email)
await p.locator('input[name="password"]').first().fill(password)
await p.getByRole('button', { name: /enter workspace/i }).first().click()
await p.waitForTimeout(6000)
await p.getByRole('link', { name: /^creators$/i }).first().click()
await p.waitForTimeout(3500)
console.log('  CreatorsPage bundles loaded:', loaded.length ? loaded.join(' | ') : 'NONE (came from the shell bundle)')
const html = await p.content()
console.log('  page mentions "All niches":', html.includes('All niches'))
console.log('  gateway warnings:', warns.length? warns[0] : 'none')
console.log('  failed remote requests:', failed.length? failed.join(' | ') : 'none')
await b.close()
