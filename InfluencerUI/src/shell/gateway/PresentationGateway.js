/**
 * Thin client for the Digital Presentation Service (DPS).
 *
 * <p>This used to hold the session itself: tokens in `localStorage`, refresh logic, permission
 * decoding. All of that moved server-side. What remains is a client — it calls the DPS, which owns
 * the session and returns only what the browser is allowed to know.
 *
 * <h3>Why the move</h3>
 * <ul>
 *   <li><strong>No token in JavaScript.</strong> The session is an httpOnly cookie, so an XSS
 *       payload has nothing to steal. Previously any script on the page could read the access token
 *       out of storage.</li>
 *   <li><strong>Genuinely one session across origins.</strong> Storage is origin-scoped, so sharing
 *       previously depended on React context reaching every remote. A cookie is sent by the browser
 *       to the DPS from <em>any</em> allowed origin, whether or not React is involved.</li>
 *   <li><strong>Refresh happens where the tokens are.</strong> No coordination between six remotes
 *       racing to rotate one refresh token.</li>
 *   <li><strong>A place for the login-time cache.</strong> The DPS assembles it once at login;
 *       remotes read it from the session instead of each fetching the same reference data.</li>
 * </ul>
 */

const DPS_BASE_URL = import.meta.env?.VITE_DPS_URL || 'http://localhost:8090'

/** Shape the DPS returns for an anonymous caller. Mirrors SessionView.anonymous(). */
const ANONYMOUS = {
  authenticated: false,
  userId: '',
  email: '',
  userName: '',
  accountId: '',
  brandId: '',
  brandName: '',
  role: '',
  permissions: [],
  availableBrands: [],
  warmCache: {},
}

export class PresentationGateway {
  constructor({ dpsBaseUrl = DPS_BASE_URL, onSessionChange = () => {} } = {}) {
    this.dpsBaseUrl = dpsBaseUrl
    this.onSessionChange = onSessionChange
    // Nothing is restored from storage: the session lives in a cookie the browser holds and this
    // code cannot read. `loadSession()` asks the DPS who we are.
    this.session = { ...ANONYMOUS }
    this.subscribers = new Set()
    this.ready = false
  }

  getSession() {
    return Object.freeze({ ...this.session, permissions: [...this.session.permissions] })
  }

  subscribe(listener) {
    this.subscribers.add(listener)
    return () => this.subscribers.delete(listener)
  }

  #emit() {
    this.onSessionChange(this.getSession())
    this.subscribers.forEach((fn) => fn(this.getSession()))
  }

  #apply(view) {
    this.session = {
      ...ANONYMOUS,
      ...view,
      permissions: Array.isArray(view?.permissions) ? view.permissions : [],
      availableBrands: Array.isArray(view?.availableBrands) ? view.availableBrands : [],
      warmCache: view?.warmCache || {},
    }
    this.#emit()
    return this.getSession()
  }

  // ------------------------------------------------------------------ session

  /**
   * Asks the DPS who the caller is.
   *
   * <p>Called on mount. Returns an anonymous session rather than failing when there is no cookie —
   * "not logged in" is a normal first-visit state, not an error.
   */
  async loadSession() {
    try {
      const view = await this.#call('/dps/session')
      this.ready = true
      return this.#apply(view)
    } catch {
      this.ready = true
      return this.#apply(ANONYMOUS)
    }
  }

  async login({ email, password }) {
    return this.#apply(await this.#call('/dps/auth/login', { method: 'POST', body: { email, password } }))
  }

  async signup({ email, password, brandName, accountType }) {
    return this.#apply(
      await this.#call('/dps/auth/signup', {
        method: 'POST',
        body: { email, password, brandName, accountType },
      }),
    )
  }

  async logout() {
    try {
      await this.#call('/dps/auth/logout', { method: 'POST' })
    } catch {
      // The DPS clears the cookie regardless; local state is reset either way.
    }
    return this.#apply(ANONYMOUS)
  }

  async switchBrand(brandId) {
    if (!brandId || brandId === this.session.brandId) return this.getSession()
    return this.#apply(await this.#call('/dps/brands/switch', { method: 'POST', body: { brandId } }))
  }

  async loadBrands() {
    try {
      const brands = await this.#call('/dps/brands')
      this.session = { ...this.session, availableBrands: Array.isArray(brands) ? brands : [] }
      this.#emit()
    } catch {
      // Non-fatal: the workspace still works against the active brand.
    }
    return this.session.availableBrands
  }

  // ------------------------------------------------------------ authorization

  /**
   * Whether the session holds a permission.
   *
   * <p>Read from the session the DPS returned. A UX affordance only — the server re-checks every
   * call, so this decides what to render and nothing more.
   */
  can(permission) {
    if (!permission) return true
    if (!this.session.permissions?.length) return true
    return this.session.permissions.includes(permission)
  }

  /** Data the DPS assembled at login, so remotes need not re-fetch it. */
  warm(key) {
    return key ? this.session.warmCache?.[key] : this.session.warmCache
  }

  // -------------------------------------------------------------------- fetch

  /**
   * The capability handed to remotes.
   *
   * <p>Calls travel to the DPS, which attaches the bearer token and tenancy header server-side.
   * There is no token here to attach, and none to leak.
   *
   * <p>`credentials: 'include'` is what sends the session cookie cross-origin. Without it every
   * call from a remote would be anonymous.
   */
  authorizedFetch = async (path, options = {}) => {
    const headers = { ...(options.headers || {}) }
    if (!options.isFormData) {
      headers['Content-Type'] = headers['Content-Type'] || 'application/json'
    }
    // Double-submit CSRF: cookies are attached automatically, so possession of the token — which
    // only same-origin script can read — is what proves the request was not forged.
    const csrf = readCookie('XSRF-TOKEN')
    if (csrf) headers['X-XSRF-TOKEN'] = csrf

    // A bare path is a platform API call and goes through the DPS proxy; a /dps/ path is a
    // gateway call and is passed through unchanged.
    const url = path.startsWith('/dps/')
      ? this.dpsBaseUrl + path
      : `${this.dpsBaseUrl}/dps/api${path.startsWith('/api') ? path.slice(4) : path}`

    return fetch(url, {
      method: options.method || 'GET',
      headers,
      credentials: 'include',
      body:
        options.body == null ? undefined : options.isFormData ? options.body : JSON.stringify(options.body),
    })
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

/** Reads a non-httpOnly cookie. Only ever used for the CSRF token, which is readable by design. */
function readCookie(name) {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

export default PresentationGateway
