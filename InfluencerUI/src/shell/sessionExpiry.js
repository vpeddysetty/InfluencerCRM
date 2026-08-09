/**
 * When an access token expires, and how long before that we should renew it.
 *
 * <p>The app already decodes the JWT to read `perms`, so the `exp` claim is free. Preferring the
 * token's own claim over the `expiresAt` field in the auth response avoids trusting a second,
 * separately-computed value — and `exp` is what the server actually enforces.
 */

/** Renew this far ahead of expiry. */
export const REFRESH_LEAD_MS = 2 * 60 * 1000

/**
 * Never schedule a timer shorter than this.
 *
 * <p>A token that is already inside the lead window would otherwise schedule at a negative delay
 * and fire immediately, and a refresh that itself returns a near-expired token would spin. The
 * floor turns that into a slow retry instead of a hot loop.
 */
const MIN_DELAY_MS = 5 * 1000

/**
 * Expiry of an access token, in epoch milliseconds, or null if it cannot be determined.
 *
 * <p>Returns null rather than throwing on a malformed token: an unreadable token is a problem for
 * the request layer to surface as a 401, not a reason for the shell to crash on render.
 */
export function accessTokenExpiryMs(accessToken) {
  if (!accessToken) {
    return null
  }
  try {
    const payload = accessToken.split('.')[1]
    if (!payload) {
      return null
    }
    // base64url → base64. atob rejects the URL-safe alphabet the JWT spec uses.
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
    const claims = JSON.parse(atob(normalized))
    // `exp` is in SECONDS per RFC 7519. Treating it as milliseconds puts expiry in 1970 and
    // makes every token look expired.
    return typeof claims.exp === 'number' ? claims.exp * 1000 : null
  } catch {
    return null
  }
}

/**
 * How long to wait before proactively refreshing, or null if there is nothing to schedule.
 *
 * @param {string} accessToken the current access token
 * @param {number} [now] epoch ms; injectable so the arithmetic is testable
 */
export function msUntilRefresh(accessToken, now = Date.now()) {
  const expiry = accessTokenExpiryMs(accessToken)
  if (expiry === null) {
    return null
  }
  return Math.max(MIN_DELAY_MS, expiry - REFRESH_LEAD_MS - now)
}

/** Whether a token is already past its expiry — used to decide against a doomed silent refresh. */
export function isExpired(accessToken, now = Date.now()) {
  const expiry = accessTokenExpiryMs(accessToken)
  return expiry !== null && expiry <= now
}
