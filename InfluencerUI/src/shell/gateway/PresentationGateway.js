/**
 * The presentation gateway.
 *
 * One place that authenticates a user, holds the resulting session, and decides which routes —
 * across which origins — that user may reach. Remotes never authenticate, never read storage, and
 * never construct an API client; they ask the gateway.
 *
 * <h3>Why a gateway rather than letting each remote handle its own auth</h3>
 * Micro-frontends are served from different origins. `localStorage` is origin-scoped, so a token
 * written by the shell at :5173 is simply invisible to a remote at :5174. Left alone, each remote
 * would need its own login — which is both a terrible experience and a much larger attack surface,
 * since the token would then exist in N places instead of one.
 *
 * The gateway makes the shell the sole holder of the credential. Remotes receive a narrow, revocable
 * capability (`fetch` bound to the session) rather than the token itself.
 *
 * <h3>What it deliberately does not do</h3>
 * It does not enforce authorization. Hiding a route the user cannot use avoids a dead end; the
 * server re-checks every call regardless. Treating this as the security boundary would be a mistake
 * — a remote is just JavaScript in the user's browser.
 */

const STORAGE_KEY = 'influencrm_gateway_session_v1'

/** Session shape the gateway owns. Remotes see a read-only projection of this. */
const EMPTY_SESSION = {
  authenticated: false,
  userId: '',
  email: '',
  userName: '',
  accessToken: '',
  refreshToken: '',
  accountId: '',
  brandId: '',
  brandName: '',
  role: '',
  permissions: [],
  availableBrands: [],
}

export class PresentationGateway {
  constructor({ apiBaseUrl = '', onSessionChange = () => {} } = {}) {
    this.apiBaseUrl = apiBaseUrl
    this.onSessionChange = onSessionChange
    this.session = this.#restore()
    this.subscribers = new Set()
    // Collapses concurrent 401s into one refresh, so a burst of parallel calls from several
    // remotes cannot rotate the refresh token repeatedly and invalidate each other.
    this.inFlightRefresh = null
  }

  // ---------------------------------------------------------------- session

  #restore() {
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY)
      if (!raw) return { ...EMPTY_SESSION }
      const parsed = JSON.parse(raw)
      return parsed && typeof parsed === 'object' ? { ...EMPTY_SESSION, ...parsed } : { ...EMPTY_SESSION }
    } catch {
      return { ...EMPTY_SESSION }
    }
  }

  #persist() {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(this.session))
    } catch {
      // A full or blocked storage quota must not break the running session — it only means the
      // user will have to log in again after a reload.
    }
  }

  #emit() {
    this.#persist()
    this.onSessionChange(this.getSession())
    this.subscribers.forEach((fn) => fn(this.getSession()))
  }

  /** Read-only projection. Callers cannot mutate the gateway's state by holding this. */
  getSession() {
    return Object.freeze({ ...this.session, permissions: [...this.session.permissions] })
  }

  subscribe(listener) {
    this.subscribers.add(listener)
    return () => this.subscribers.delete(listener)
  }

  #applyAuthResponse(response) {
    const email = response?.email || ''
    const inferredName = email.includes('@') ? email.split('@')[0] : email

    this.session = {
      ...this.session,
      authenticated: Boolean(response?.accessToken),
      userId: response?.userId || '',
      email,
      userName: inferredName || 'Brand Operator',
      accessToken: response?.accessToken || '',
      // A refresh response omits the refresh token when it did not rotate; keep the existing one
      // rather than blanking it, or the next refresh would fail.
      refreshToken: response?.refreshToken || this.session.refreshToken || '',
      accountId: response?.accountId || '',
      brandId: response?.brandId || '',
      brandName: response?.brandName || this.session.brandName || '',
      role: response?.role || '',
      permissions: readPermissions(response?.accessToken),
    }
    this.#emit()
    return this.getSession()
  }

  // ------------------------------------------------------------------ auth

  async login({ email, password }) {
    const response = await this.#call('/api/auth/login', {
      method: 'POST',
      body: { email, password },
      anonymous: true,
    })
    return this.#applyAuthResponse(response)
  }

  async signup({ email, password, brandName }) {
    const response = await this.#call('/api/auth/signup', {
      method: 'POST',
      body: { email, password, brandName },
      anonymous: true,
    })
    return this.#applyAuthResponse(response)
  }

  async logout() {
    try {
      if (this.session.refreshToken) {
        await this.#call('/api/auth/logout', {
          method: 'POST',
          body: { refreshToken: this.session.refreshToken },
          anonymous: true,
        })
      }
    } catch {
      // The desired end state — no live session — is reached locally regardless.
    }
    this.session = { ...EMPTY_SESSION }
    this.#emit()
  }

  /**
   * Renews the access token. Concurrent callers share one in-flight request: several remotes
   * hitting 401 simultaneously must not each rotate the refresh token.
   */
  async refresh() {
    if (!this.session.refreshToken) return null
    if (this.inFlightRefresh) return this.inFlightRefresh

    this.inFlightRefresh = (async () => {
      try {
        const response = await this.#call('/api/auth/refresh', {
          method: 'POST',
          body: { refreshToken: this.session.refreshToken },
          anonymous: true,
        })
        return this.#applyAuthResponse(response).accessToken
      } catch {
        this.session = { ...EMPTY_SESSION }
        this.#emit()
        return null
      } finally {
        this.inFlightRefresh = null
      }
    })()

    return this.inFlightRefresh
  }

  // ----------------------------------------------------------------- brands

  async loadBrands() {
    if (!this.session.authenticated) return []
    try {
      const brands = await this.#call('/api/brands')
      this.session = { ...this.session, availableBrands: Array.isArray(brands) ? brands : [] }
      this.#emit()
      return this.session.availableBrands
    } catch {
      // Non-fatal: the workspace still works against the brand already in the token.
      return this.session.availableBrands
    }
  }

  /**
   * Switches the active brand.
   *
   * The server re-mints the token because role and permissions are per-brand — carrying the old
   * brand's role across would over-grant. This is why brand switching is a gateway concern and not
   * something a remote can do for itself.
   */
  async switchBrand(brandId) {
    if (!brandId || brandId === this.session.brandId) return this.getSession()
    const response = await this.#call('/api/brands/switch', {
      method: 'POST',
      body: { brandId },
    })
    return this.#applyAuthResponse(response)
  }

  // ------------------------------------------------------------ authorization

  /**
   * Whether the session may use a capability.
   *
   * A UX affordance only. An empty permission set means the token predates permission claims, in
   * which case showing everything is safer than an empty app — the server still refuses.
   */
  can(permission) {
    if (!permission) return true
    if (!this.session.permissions || this.session.permissions.length === 0) return true
    return this.session.permissions.includes(permission)
  }

  // ------------------------------------------------------------------- fetch

  /**
   * The capability handed to remotes.
   *
   * Remotes call this instead of `fetch`. They never see the token, so a compromised or careless
   * remote cannot exfiltrate a credential it was never given — and revoking access is a matter of
   * no longer passing this function.
   *
   * Retries once through refresh on 401, so an expired token mid-session is invisible to the caller.
   */
  authorizedFetch = async (path, options = {}) => {
    const send = async (token) => {
      const headers = { ...(options.headers || {}) }
      if (!options.isFormData) headers['Content-Type'] = headers['Content-Type'] || 'application/json'
      if (token) headers.Authorization = `Bearer ${token}`
      // The active brand is the tenancy key and travels as a header, so no call site can pick
      // a different one.
      if (this.session.brandId) headers['X-Brand-Id'] = this.session.brandId

      return fetch(this.apiBaseUrl + path, {
        method: options.method || 'GET',
        headers,
        body:
          options.body == null
            ? undefined
            : options.isFormData
              ? options.body
              : JSON.stringify(options.body),
      })
    }

    let response = await send(this.session.accessToken)
    if (response.status === 401 && this.session.accessToken && !options.anonymous) {
      const renewed = await this.refresh()
      if (renewed) response = await send(renewed)
    }
    return response
  }

  async #call(path, options = {}) {
    const response = await this.authorizedFetch(path, options)
    const text = await response.text()
    const data = text ? JSON.parse(text) : null
    if (!response.ok) {
      throw new Error(data?.message || data?.error || `Request failed with status ${response.status}`)
    }
    return data
  }
}

/**
 * Reads permission claims from the access token.
 *
 * Decoded, never verified — this decides what to render, not what is allowed. A tampered token
 * buys nothing but a UI offering links the API will refuse.
 */
function readPermissions(accessToken) {
  if (!accessToken) return []
  try {
    const payload = accessToken.split('.')[1]
    if (!payload) return []
    const claims = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
    return Array.isArray(claims.perms) ? claims.perms : []
  } catch {
    return []
  }
}

export default PresentationGateway
