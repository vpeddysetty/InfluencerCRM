/**
 * The creator portal's API client (roadmap PR-43).
 *
 * Talks to the BFF directly with `X-Creator-Token`, never through the DPS — see vite.config.js for
 * why routing a creator through the operator gateway is structurally wrong rather than merely
 * inconvenient.
 */

const BASE = import.meta.env.VITE_API_BASE_URL || ''

/**
 * Where the session token lives.
 *
 * `sessionStorage`, not `localStorage`, and the difference is the point: this is a bearer
 * credential for an identity that spans several brands, and localStorage would keep it readable by
 * anything running on this origin until someone explicitly signed out — including in a tab the
 * creator forgot about on a shared laptop. sessionStorage dies with the tab.
 *
 * It is still JavaScript-readable, which an httpOnly cookie would not be. That is the documented
 * cost of talking to the BFF directly instead of through the DPS, and the mitigations are
 * elsewhere: the token is opaque and server-side, every request re-reads the session so revocation
 * is immediate, and the TTL is hours rather than days.
 */
const TOKEN_KEY = 'tejdux.creator.token'

export function getToken() {
  try {
    return sessionStorage.getItem(TOKEN_KEY) || ''
  } catch {
    // Private browsing, or storage disabled. Treated as signed out rather than crashing: a portal
    // that throws on load is worse than one that asks for a password again.
    return ''
  }
}

export function setToken(token) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    // Ignored for the same reason as above. The session then lasts only as long as this page,
    // which is a degraded experience rather than a broken one.
  }
}

async function request(path, { method = 'GET', body, token = getToken() } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  // Only sent when we have one. An absent header is how the BFF distinguishes "not signed in"
  // from "signed in with something stale", and the two produce different screens.
  if (token) headers['X-Creator-Token'] = token

  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (response.status === 401 || response.status === 403) {
    // The session was revoked, expired, or the brand ended the relationship. Clearing here rather
    // than at each call site means a revoked creator cannot keep hammering a dead token.
    setToken('')
    const error = new Error('Your session has ended. Please sign in again.')
    error.code = 'session_expired'
    throw error
  }

  const text = await response.text()
  const payload = text ? safeParse(text) : null

  if (!response.ok) {
    const error = new Error(payload?.message || `Request failed (${response.status})`)
    error.status = response.status
    // The server's own code, when it sent one. `access_revoked` in particular drives a specific
    // screen — "Acme ended your access" plus a download of the draft — rather than a generic error.
    error.code = payload?.code
    throw error
  }
  return payload
}

function safeParse(text) {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

// ---- invitation (no session yet, by definition) ----

/**
 * What the invite screen shows before anyone commits to anything.
 *
 * Redacted server-side to status and brand — never the page. A GET that rendered stored
 * unpublished content would be fetched automatically by email scanners and link unfurlers, so one
 * forwarded invitation would leak an unreleased campaign.
 */
export async function previewInvite(token) {
  return request(`/api/public/creator-invites/preview?token=${encodeURIComponent(token)}`)
}

/** A POST, so a mail scanner following the link cannot accept the invitation for the creator. */
export async function redeemInvite({ token, displayName }) {
  return request('/api/public/creator-invites/redeem', {
    method: 'POST',
    body: { token, displayName },
    token: null,
  })
}

// ---- session ----

export async function login({ email, password }) {
  const session = await request('/api/creator-portal/auth/login', {
    method: 'POST',
    body: { email, password },
    token: null,
  })
  if (session?.token) setToken(session.token)
  return session
}

export async function signup({ email, password, displayName, acceptedTerms }) {
  const session = await request('/api/creator-portal/auth/signup', {
    method: 'POST',
    body: { email, password, displayName, acceptedTerms },
    token: null,
  })
  if (session?.token) setToken(session.token)
  return session
}

export async function logout() {
  try {
    await request('/api/creator-portal/auth/logout', { method: 'POST' })
  } finally {
    // Cleared even when the call fails. The creator asked to sign out; leaving a usable token in
    // the tab because the network hiccupped would be the opposite of what they asked for.
    setToken('')
  }
}

export async function me() {
  return request('/api/creator-portal/me')
}

// ---- pages ----

export async function listMyPages() {
  const payload = await request('/api/creator-portal/pages')
  return Array.isArray(payload) ? payload : payload?.items || []
}
