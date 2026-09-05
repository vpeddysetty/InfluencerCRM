import { chromium } from 'playwright'
import { readFileSync } from 'node:fs'
const [email, password] = readFileSync(process.env.UI_CHECK_CREDS, 'utf8').trim().split('\n')
const b = await chromium.launch(); const p = await b.newPage()
const reqs = []
p.on('response', (r) => { const u = r.url(); if (u.includes('creators.tejdux')) reqs.push(r.status() + ' ' + u.replace('https://creators.tejdux.com','')) })
const errs = []
p.on('console', (m) => { if (m.type() === 'error' || m.text().includes('gateway')) errs.push(m.text().slice(0, 180)) })
p.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 180)))
await p.goto('https://app.tejdux.com/', { waitUntil: 'networkidle', timeout: 60000 })
await p.getByRole('button', { name: /^log in$/i }).first().click()
await p.waitForTimeout(1200)
await p.locator('input[name="email"]').first().fill(email)
await p.locator('input[name="password"]').first().fill(password)
await p.getByRole('button', { name: /enter workspace/i }).first().click()
await p.waitForTimeout(6000)
await p.getByRole('link', { name: /^creators$/i }).first().click()
await p.waitForTimeout(4000)
console.log('  requests to creators.tejdux.com:')
console.log(reqs.length ? reqs.map(r => '    ' + r).join('\n') : '    NONE')
console.log('  errors/warnings:')
console.log(errs.length ? errs.slice(0,4).map(e => '    ' + e).join('\n') : '    none')
await b.close()
