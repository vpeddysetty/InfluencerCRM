// Shared HTTP transport for every context slice: bearer auth, the X-Brand-Id tenancy
// header, and transparent one-shot token refresh. Split from the per-context modules in
// Phase 6 so a remote imports the transport without importing every other context.

export function unwrapList(payload) {
  if (Array.isArray(payload)) {
    return payload
  }

  if (payload && Array.isArray(payload.items)) {
    return payload.items
  }

  return []
}

// The active brand is the tenancy key. It travels as a header rather than inside each
// payload so that one place decides it and no individual call site can pick another.
let activeBrandId = ''

/**
 * Cookie-session mode: send requests through the DPS proxy instead of straight to the BFF.
 *
 * <p>Turned on when the app boots into a session it restored from the DPS cookie rather than one it
 * holds a token for. The proxy at {@code /dps/api/**} forwards to {@code /api/**} and attaches the
 * bearer token and the tenancy header server-side — the same two things buildHeaders does here —
 * so a call is identical from the caller's side either way. That equivalence is what lets this be
 * a transport switch in one function rather than a change at all 90-odd call sites.
 *
 * <p>Off by default. A password sign-in still returns a token directly to the SPA, and that path is
 * untouched; this only carries the sessions where the browser was never given a token to hold.
 */
let cookieMode = false
let dpsBaseUrl = ''

export function useCookieSession(baseUrl) {
  cookieMode = true
  dpsBaseUrl = baseUrl || ''
}

export function clearCookieSession() {
  cookieMode = false
  dpsBaseUrl = ''
}

export function isCookieSession() {
  return cookieMode
}

/** Reads a non-httpOnly cookie. Only ever used for the CSRF token, which is readable by design. */
function readCookie(name) {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * Headers for a direct (non-proxied) call to the DPS, carrying the double-submit CSRF token.
 *
 * <p>Exported because logout is a state-changing DPS call made outside `request()`. Without the
 * header the DPS answers 403 and the session survives — so the user is told they signed out while
 * the cookie stays valid and the next boot restores them. Verified against the live DPS: the same
 * call returns 403 without this and 204 with it.
 */
export function dpsCsrfHeaders(extra = {}) {
  const headers = { ...extra }
  const csrf = readCookie('XSRF-TOKEN')
  if (csrf) headers['X-XSRF-TOKEN'] = csrf
  return headers
}

export function setActiveBrandId(brandId) {
  activeBrandId = brandId || ''
}

export function getActiveBrandId() {
  return activeBrandId
}

function resolveApiUrl(path) {
  if (!path || /^https?:\/\//i.test(path)) {
    return path
  }

  const baseUrl =
    import.meta?.env?.VITE_BFF_URL ||
    (typeof window !== 'undefined' && window.location?.origin ? window.location.origin : '')

  if (!baseUrl) {
    return path
  }

  return new URL(path, baseUrl).toString()
}

function buildHeaders(token, extraHeaders = {}) {
  const headers = {
    ...extraHeaders,
  }

  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  if (activeBrandId) {
    headers['X-Brand-Id'] = activeBrandId
  }

  return headers
}

async function readResponse(response) {
  const text = await response.text()
  const data = text ? JSON.parse(text) : null

  if (!response.ok) {
    const message = data?.message || data?.error || `Request failed with status ${response.status}`
    throw new Error(message)
  }

  return data
}

// Access tokens are now short-lived (30 min by default) and paired with a longer-lived refresh
// token. Rather than making every caller handle expiry, `request` retries once through
// /api/auth/refresh on a 401. The host app supplies the refresh handler via setAuthHandlers.
let refreshHandler = null
let sessionExpiredHandler = null
let inFlightRefresh = null

/**
 * Wires the session callbacks owned by the app shell.
 * - getRefreshToken: returns the current refresh token, if any
 * - onRefreshed:     receives the new auth payload so the app can persist it
 * - onSessionExpired: called when refresh fails and the user must log in again
 */
export function setAuthHandlers({ getRefreshToken, onRefreshed, onSessionExpired } = {}) {
  refreshHandler = getRefreshToken && onRefreshed ? { getRefreshToken, onRefreshed } : null
  sessionExpiredHandler = onSessionExpired || null
}

async function refreshAccessToken() {
  if (!refreshHandler) {
    return null
  }

  // Collapse concurrent 401s into a single refresh call so a burst of parallel
  // requests doesn't rotate the refresh token several times over.
  if (!inFlightRefresh) {
    inFlightRefresh = (async () => {
      try {
        const refreshToken = refreshHandler.getRefreshToken()
        if (!refreshToken) {
          return null
        }
        const refreshed = await request('/api/auth/refresh', {
          method: 'POST',
          body: { refreshToken },
          skipRefresh: true,
        })
        refreshHandler.onRefreshed(refreshed)
        return refreshed?.accessToken || null
      } catch {
        if (sessionExpiredHandler) {
          sessionExpiredHandler()
        }
        return null
      } finally {
        inFlightRefresh = null
      }
    })()
  }

  return inFlightRefresh
}

export async function request(
  path,
  { method = 'GET', token, body, headers, isFormData = false, skipRefresh = false } = {},
) {
  const extraHeaders = isFormData ? headers : { 'Content-Type': 'application/json', ...headers }
  const payload = body == null ? undefined : isFormData ? body : JSON.stringify(body)

  // Cookie mode routes through the DPS proxy, which supplies the credential this client does not
  // have. Auth endpoints are excluded: /api/auth/refresh rotates a token the browser is not
  // holding, and login/signup are how a session begins, so neither can be proxied through a
  // session that does not exist yet.
  const proxied = cookieMode && !token && !path.startsWith('/api/auth/')

  const send = (bearer) => {
    if (proxied) {
      // Double-submit CSRF: the cookie rides along automatically, so possession of this token —
      // readable only by same-origin script — is what proves the request was not forged.
      return fetch(`${dpsBaseUrl}/dps/api${path.startsWith('/api') ? path.slice(4) : path}`, {
        method,
        headers: dpsCsrfHeaders(extraHeaders),
        credentials: 'include',
        body: payload,
      })
    }

    return fetch(resolveApiUrl(path), { method, headers: buildHeaders(bearer, extraHeaders), body: payload })
  }

  let response = await send(token)

  // Only the bearer path retries here. In cookie mode the DPS refreshes its own tokens
  // server-side (findRefreshed), so a 401 back from the proxy means the session itself is gone —
  // retrying would just ask the same dead session twice.
  if (response.status === 401 && token && !skipRefresh) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      response = await send(newToken)
    }
  }

  if (response.status === 401 && proxied && sessionExpiredHandler) {
    sessionExpiredHandler()
  }

  return readResponse(response)
}

export async function signup(payload) {
  return request('/api/auth/signup', { method: 'POST', body: payload })
}

export async function login(payload) {
  return request('/api/auth/login', { method: 'POST', body: payload })
}

// The server revokes the refresh token; the access token stays valid until it expires.
export async function logout(refreshToken) {
  return request('/api/auth/logout', { method: 'POST', body: { refreshToken } })
}

export async function listBrands(token) {
  return request('/api/brands', { token })
}

// Switching brand re-mints the token: role and permissions are per-brand, so the
// old token cannot simply be reused against a different one.
export async function switchBrand(token, brandId) {
  return request('/api/brands/switch', { method: 'POST', token, body: { brandId } })
}

export async function createBrand(token, name) {
  return request('/api/brands', { method: 'POST', token, body: { name } })
}

// Names the workspace after a social sign-up, and promotes it to an agency if that is what the
// user picked before the redirect. Like switchBrand this re-mints the token, because the old one
// still carries the provider-derived brandName the session was created with.
export async function completeOnboarding(token, { workspaceName, accountType }) {
  return request('/api/brands/onboarding', {
    method: 'POST',
    token,
    body: { workspaceName, accountType },
  })
}

export async function listAccountMembers(token) {
  return request('/api/brands/members', { token })
}

// The account's plan and what it has used of it (M2.3). Read live rather than taken from the
// token: the server deliberately keeps the plan out of the JWT so an upgrade takes effect at once
// instead of waiting for a token to expire, and the UI must not reintroduce that staleness by
// caching it against the session.
export async function loadPlanUsage(token) {
  return request('/api/brands/plan', { token })
}

// ---- subscriptions and billing (M2.1/M2.2) ----
// Viewing needs account:billing:read (OWNER and ADMIN); changing needs account:billing (OWNER
// only). The server decides — `canManage` on the response says whether this caller may act, so
// the UI never has to re-derive the rule and risk disagreeing with it.

export async function loadSubscription(token) {
  return request('/api/billing/subscription', { token })
}

export async function loadBillingInvoices(token) {
  return request('/api/billing/invoices', { token })
}

export async function subscribeToPlan(token, plan) {
  return request('/api/billing/subscribe', { method: 'POST', token, body: { plan } })
}

export async function pauseSubscription(token) {
  return request('/api/billing/pause', { method: 'POST', token, body: {} })
}

export async function resumeSubscription(token) {
  return request('/api/billing/resume', { method: 'POST', token, body: {} })
}

// Ends at the paid period by default. `immediate` is opt-in because someone who cancels on day 2
// of a month has paid for the other 28 and should keep them.
export async function cancelSubscription(token, { immediate = false } = {}) {
  return request('/api/billing/cancel', { method: 'POST', token, body: { immediate } })
}

// ---- member invitations (roadmap Stage 3) ----

// The token comes back exactly once, here. Only its hash is stored, so it cannot be read
// again — losing it means re-inviting.
export async function inviteMember(token, { email, role, brandId }) {
  return request('/api/brands/members/invite', {
    method: 'POST',
    token,
    body: { email, role, brandId: brandId || null },
  })
}

// No tokens come back from this one. Fifty invitation tokens in a single response would be fifty
// live credentials sitting in the browser's memory, in any screenshot of the results table, and in
// any error report that captured the body. An invitation nobody was emailed is recovered through
// `resendInvitation` below, one at a time and deliberately.
export async function bulkInviteMembers(token, invitations) {
  return request('/api/brands/members/invite/bulk', { method: 'POST', token, body: { invitations } })
}

export async function listInvitations(token) {
  const payload = await request('/api/brands/members/invitations', { token })
  return unwrapList(payload)
}

// Issues a fresh link and invalidates the previous one — two working tokens for one invitation
// would mean revoking the visible one still lets the other in.
export async function resendInvitation(token, id) {
  return request(`/api/brands/members/invitations/${id}/resend`, { method: 'POST', token, body: {} })
}

export async function revokeInvitation(token, id) {
  return request(`/api/brands/members/invitations/${id}/revoke`, { method: 'POST', token, body: {} })
}

export async function acceptInvitation(token, invitationToken) {
  return request('/api/brands/members/invitations/accept', {
    method: 'POST',
    token,
    body: { token: invitationToken },
  })
}

export async function updateMemberRole(token, userId, role) {
  return request(`/api/brands/members/${userId}`, { method: 'PUT', token, body: { role } })
}

export async function removeMember(token, userId) {
  return request(`/api/brands/members/${userId}`, { method: 'DELETE', token })
}

export async function refreshSession(refreshToken) {
  return request('/api/auth/refresh', { method: 'POST', body: { refreshToken }, skipRefresh: true })
}

/**
 * Asks the DPS whether this browser already has a session.
 *
 * <p>Called once at boot. The DPS sets an httpOnly cookie at sign-in — including at the end of the
 * OAuth flow, which redirects here with the cookie already set — so this is the only way the SPA
 * can discover a session it was never handed a token for.
 *
 * <p>Returns `{ authenticated: false }` rather than throwing when there is no session: on a first
 * visit that is the normal answer, and treating it as an error would put a failure in the console
 * on every anonymous page load. A network failure resolves the same way, because the honest
 * fallback when the DPS cannot be reached is "we do not know of a session", which lands the user
 * on the login page rather than a shell that cannot load anything.
 */
export async function fetchDpsSession(baseUrl) {
  try {
    const response = await fetch(`${baseUrl}/dps/session`, {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      return { authenticated: false }
    }
    const text = await response.text()
    return text ? JSON.parse(text) : { authenticated: false }
  } catch {
    return { authenticated: false }
  }
}


