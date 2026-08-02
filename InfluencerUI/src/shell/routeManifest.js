import { lazy } from 'react'

/**
 * The shell's map of routes to the context that owns them.
 *
 * <p>This is the seam a micro-frontend split cuts along. Today each entry lazy-loads a local page;
 * under Module Federation the same entry becomes `lazy(() => import('mf_creators/CreatorsPage'))`
 * and nothing else in the shell changes. Keeping the manifest as data rather than JSX is what makes
 * that a one-line edit per route.
 *
 * <p>`permission` gates the nav entry — a UX affordance only. The server re-checks every action, so
 * hiding a link avoids a dead end; it is not what stops anyone acting.
 */

// Lazy so each context's page is a separate chunk. That split is what proves the boundary is real:
// if two contexts share a chunk, they share code, and extraction would drag one into the other.
const ImportPage = lazy(() => import('../pages/ImportPage'))
const CampaignsPage = lazy(() => import('../pages/CampaignsPage'))
const CreatorsPage = lazy(() => import('../pages/CreatorsPage'))
const ContentPage = lazy(() => import('../pages/ContentPage'))
const WorkflowPage = lazy(() => import('../pages/WorkflowPage'))
const CouponsPage = lazy(() => import('../pages/CouponsPage'))
const MarketplacePage = lazy(() => import('../pages/MarketplacePage'))
const DashboardPage = lazy(() => import('../pages/DashboardPage'))
const PayoutsPage = lazy(() => import('../pages/PayoutsPage'))

export const ROUTE_MANIFEST = [
  {
    context: 'campaign',
    path: '/import',
    label: 'Import',
    permission: 'import:execute',
    component: ImportPage,
    // Which api/ slice this route depends on. Recorded so the extraction runbook can
    // answer "what moves with this page?" without reading every import.
    apiSlice: 'imports',
  },
  {
    context: 'campaign',
    path: '/campaigns',
    label: 'Campaigns',
    permission: 'campaign:read',
    component: CampaignsPage,
    apiSlice: 'campaigns',
  },
  {
    context: 'creator',
    path: '/creators',
    label: 'Creators',
    permission: 'creator:read',
    component: CreatorsPage,
    apiSlice: 'creators',
  },
  {
    context: 'content',
    path: '/content',
    label: 'Content',
    permission: 'content:read',
    component: ContentPage,
    apiSlice: 'content',
  },
  {
    context: 'workflow',
    path: '/workflow',
    label: 'Workflow',
    permission: 'workflow:read',
    component: WorkflowPage,
    apiSlice: 'workflow',
  },
  {
    context: 'attribution',
    path: '/coupons',
    label: 'Coupons',
    permission: 'coupon:read',
    component: CouponsPage,
    apiSlice: 'commerce',
  },
  {
    context: 'attribution',
    path: '/marketplace',
    label: 'Marketplace',
    permission: 'marketplace:connect',
    component: MarketplacePage,
    apiSlice: 'commerce',
  },
  {
    context: 'attribution',
    path: '/dashboard',
    label: 'Dashboard',
    permission: 'attribution:read',
    component: DashboardPage,
    apiSlice: 'commerce',
  },
  {
    context: 'finance',
    path: '/payouts',
    label: 'Payouts',
    permission: 'payout:read',
    component: PayoutsPage,
    apiSlice: 'finance',
  },
]

/** Nav entries the given permission set may see. */
export function visibleRoutes(permissions) {
  // An empty set means the token predates permission claims; show everything, because the
  // server still enforces each action and an empty nav would be worse than a permissive one.
  if (!permissions || permissions.length === 0) {
    return ROUTE_MANIFEST
  }
  return ROUTE_MANIFEST.filter((route) => permissions.includes(route.permission))
}

/** Routes grouped by owning context — the extraction unit. */
export function routesByContext() {
  return ROUTE_MANIFEST.reduce((acc, route) => {
    acc[route.context] = acc[route.context] || []
    acc[route.context].push(route)
    return acc
  }, {})
}
