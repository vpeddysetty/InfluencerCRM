import test from 'node:test'
import assert from 'node:assert/strict'

import {
  request,
  useCookieSession,
  clearCookieSession,
  listConnectedAccounts,
  disconnectAccount,
} from './core.js'

// Which URL the transport actually reached for. In cookie mode a call must go to the DPS proxy,
// which is the only party holding the credential; a call that leaves for the BFF direct arrives
// with no token at all and is refused.
//
// This is the regression these tests exist for: /api/auth/connected-accounts was excluded from
// the proxy by an /api/auth/ prefix match written for login and refresh. A linked provider then
// showed as "Not connected" while its row sat in the database, because the settings page was
// reporting a failed fetch rather than the server's answer.
// The proxy branch reads the CSRF cookie off document.cookie. Node has no document, so stub the
// one property that path touches.
globalThis.document = { cookie: 'dps_csrf=test-csrf-token' }

function captureFetch(status = 200, body = '[]') {
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method || 'GET' })
    return {
      status,
      ok: status >= 200 && status < 300,
      headers: { get: () => 'application/json' },
      text: async () => body,
      json: async () => JSON.parse(body),
    }
  }
  return calls
}

const DPS = 'https://dps.example.test'

test('a cookie session reads connected accounts through the DPS proxy', async () => {
  const calls = captureFetch()
  useCookieSession(DPS)
  try {
    await listConnectedAccounts('')
  } finally {
    clearCookieSession()
  }

  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, `${DPS}/dps/api/auth/connected-accounts`)
})

test('a cookie session disconnects through the DPS proxy', async () => {
  const calls = captureFetch(204, '{}')
  useCookieSession(DPS)
  try {
    await disconnectAccount('', 'b0a1f0de-0000-4000-8000-000000000001')
  } finally {
    clearCookieSession()
  }

  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'DELETE')
  assert.match(calls[0].url, /^https:\/\/dps\.example\.test\/dps\/api\/auth\/connected-accounts\//)
})

// The other side of the rule. These four create, rotate, or destroy the session itself, so they
// cannot travel through a proxy that authenticates with it.
for (const path of ['/api/auth/login', '/api/auth/signup', '/api/auth/refresh', '/api/auth/logout']) {
  test(`a cookie session does not proxy ${path}`, async () => {
    const calls = captureFetch()
    useCookieSession(DPS)
    try {
      await request(path, { method: 'POST', body: {}, skipRefresh: true })
    } finally {
      clearCookieSession()
    }

    assert.equal(calls.length, 1)
    assert.ok(
      !calls[0].url.startsWith(`${DPS}/dps/api`),
      `${path} must not go through the DPS proxy, but went to ${calls[0].url}`,
    )
  })
}

// A bearer session is untouched by any of this: it holds its own token and calls the BFF direct.
test('a bearer session calls connected accounts directly', async () => {
  const calls = captureFetch()
  await listConnectedAccounts('a-real-token')

  assert.equal(calls.length, 1)
  assert.ok(!calls[0].url.includes('/dps/api'), `expected a direct call, got ${calls[0].url}`)
})
