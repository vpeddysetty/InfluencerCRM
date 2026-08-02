import { createContext, useContext, useMemo } from 'react'

/**
 * The only state a micro-frontend remote needs from the shell.
 *
 * <p>Phase 6's hard prerequisite is untangling App.jsx: its ~40 useState hooks are shared by all ten
 * pages, and federating before splitting them would distribute the coupling across repos where it is
 * harder to fix rather than easier. This context is the deliberate boundary — auth, active brand and
 * permissions are genuinely shell-owned; everything else (campaigns, creators, boards…) belongs to
 * whichever remote renders it.
 *
 * <p>A remote depending only on this can be extracted without touching the shell.
 */
const SessionContext = createContext(null)

export function SessionProvider({ value, children }) {
  // Memoised on the fields that actually matter: without this every shell re-render would hand
  // remotes a new object and re-render all of them.
  const session = useMemo(
    () => ({
      userId: value.userId,
      userName: value.userName,
      email: value.email,
      authToken: value.authToken,

      accountId: value.accountId,
      brandId: value.brandId,
      brandName: value.brandName,
      availableBrands: value.availableBrands,
      onSwitchBrand: value.onSwitchBrand,

      role: value.role,
      permissions: value.permissions,

      /**
       * UX affordance only — the server re-checks every action. Hiding a control the caller
       * cannot use avoids a dead end; it is not what stops them acting.
       */
      can: (permission) =>
        !value.permissions || value.permissions.length === 0
          ? true
          : value.permissions.includes(permission),

      isAgency: (value.availableBrands || []).length > 1,
    }),
    [
      value.userId,
      value.userName,
      value.email,
      value.authToken,
      value.accountId,
      value.brandId,
      value.brandName,
      value.availableBrands,
      value.onSwitchBrand,
      value.role,
      value.permissions,
    ],
  )

  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>
}

/**
 * Reads the session. Throws outside a provider rather than returning null, because a silent null
 * here surfaces later as an unauthenticated API call that is far harder to trace.
 */
export function useSession() {
  const session = useContext(SessionContext)
  if (!session) {
    throw new Error('useSession must be used within a SessionProvider')
  }
  return session
}

/** Convenience for the common "may this user do X?" check. */
export function usePermission(permission) {
  return useSession().can(permission)
}

export default SessionContext
