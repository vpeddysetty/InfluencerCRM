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

export function setActiveBrandId(brandId) {
  activeBrandId = brandId || ''
}

export function getActiveBrandId() {
  return activeBrandId
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
  const send = (bearer) =>
    fetch(path, {
      method,
      headers: buildHeaders(
        bearer,
        isFormData ? headers : { 'Content-Type': 'application/json', ...headers },
      ),
      body: body == null ? undefined : isFormData ? body : JSON.stringify(body),
    })

  let response = await send(token)

  if (response.status === 401 && token && !skipRefresh) {
    const newToken = await refreshAccessToken()
    if (newToken) {
      response = await send(newToken)
    }
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

export async function listAccountMembers(token) {
  return request('/api/brands/members', { token })
}

export async function refreshSession(refreshToken) {
  return request('/api/auth/refresh', { method: 'POST', body: { refreshToken }, skipRefresh: true })
}


