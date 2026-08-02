import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import PresentationGateway from './PresentationGateway'

/**
 * Makes the presentation gateway available to every remote.
 *
 * <p>Because React is shared as a federation singleton, a remote loaded from a different origin uses
 * the <em>same</em> context instance as the shell. That is the mechanism that lets one login serve
 * many origins: the remote reads the session from React, not from its own `localStorage` — which it
 * could not see anyway.
 *
 * <p>What crosses the boundary is deliberately narrow: session facts, an `authorizedFetch`
 * capability, and a permission predicate. The access token itself is not exposed, so a remote cannot
 * leak a credential it never held.
 */
const GatewayContext = createContext(null)

export function GatewayProvider({ apiBaseUrl = '', children }) {
  // The gateway is stateful and long-lived; recreating it per render would drop the session.
  const gatewayRef = useRef(null)
  if (gatewayRef.current === null) {
    gatewayRef.current = new PresentationGateway({ apiBaseUrl })
  }
  const gateway = gatewayRef.current

  const [session, setSession] = useState(() => gateway.getSession())

  useEffect(() => gateway.subscribe(setSession), [gateway])

  // Brands drive the switcher, and the set can change when a membership is granted or revoked.
  useEffect(() => {
    if (session.authenticated) {
      gateway.loadBrands()
    }
  }, [gateway, session.authenticated, session.brandId])

  const value = useMemo(
    () => ({
      // ---- session facts (read-only) ----
      authenticated: session.authenticated,
      userId: session.userId,
      email: session.email,
      userName: session.userName,
      accountId: session.accountId,
      brandId: session.brandId,
      brandName: session.brandName,
      role: session.role,
      permissions: session.permissions,
      availableBrands: session.availableBrands,
      isAgency: (session.availableBrands || []).length > 1,

      // ---- capabilities ----
      // The only way a remote reaches the API. No token is handed over.
      fetch: gateway.authorizedFetch,
      can: (permission) => gateway.can(permission),

      // ---- gateway-owned actions ----
      // Brand switching re-mints the token server-side because role and permissions are per-brand,
      // which is why a remote must not attempt it itself.
      switchBrand: (brandId) => gateway.switchBrand(brandId),
      login: (credentials) => gateway.login(credentials),
      signup: (details) => gateway.signup(details),
      logout: () => gateway.logout(),
    }),
    [gateway, session],
  )

  return <GatewayContext.Provider value={value}>{children}</GatewayContext.Provider>
}

/**
 * Reads the gateway.
 *
 * Throws outside a provider rather than returning null: a silent null surfaces later as an
 * unauthenticated API call, which is far harder to trace back to a missing provider.
 */
export function useGateway() {
  const gateway = useContext(GatewayContext)
  if (!gateway) {
    throw new Error(
      'useGateway must be used inside a GatewayProvider. A federated remote sees this when React ' +
        'is not shared as a singleton — check the `shared` config in both vite.config.js files.',
    )
  }
  return gateway
}

/** Convenience for the common "may this user do X?" check. */
export function usePermission(permission) {
  return useGateway().can(permission)
}

export default GatewayContext
