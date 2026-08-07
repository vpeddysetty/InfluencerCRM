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

// Remotes are opt-in. With VITE_USE_REMOTES unset the gateway renders its bundled pages, so a
// remote that is down or mid-deploy cannot take the whole app with it — which is what makes
// federation safe to adopt one context at a time rather than as a big-bang cutover.
const USE_REMOTES = import.meta.env?.VITE_USE_REMOTES === 'true'

/**
 * Loads a page from its context's remote, falling back to the bundled copy.
 *
 * The fallback is not only for development: a remote failing to load at runtime should degrade to
 * the bundled page rather than render a blank route. The specifier is a variable, not a literal, so
 * the bundler leaves it alone and the federation runtime resolves it in the browser — a literal
 * would be statically analysed and fail the build whenever remotes are disabled.
 */
function contextPage(remoteSpecifier, localImport) {
  if (!USE_REMOTES) {
    return lazy(localImport)
  }
  return lazy(() =>
    import(/* @vite-ignore */ remoteSpecifier).catch((error) => {
      // Surfaced deliberately: a silently-substituted page hides a broken deploy, and the operator
      // needs to know the gateway is serving a fallback rather than the remote.
      console.warn(`[gateway] remote ${remoteSpecifier} unavailable, using bundled page`, error)
      return localImport()
    }),
  )
}

// One entry per page. Each names the remote that owns it and the bundled fallback.
const ImportPage = contextPage('mf_campaigns/ImportPage', () => import('../pages/ImportPage'))
const CampaignsPage = contextPage('mf_campaigns/CampaignsPage', () => import('../pages/CampaignsPage'))
const CreatorsPage = contextPage('mf_creators/CreatorsPage', () => import('../pages/CreatorsPage'))
const ContentPage = contextPage('mf_content/ContentPage', () => import('../pages/ContentPage'))
const WorkflowPage = contextPage('mf_workflow/WorkflowPage', () => import('../pages/WorkflowPage'))
const CouponsPage = contextPage('mf_commerce/CouponsPage', () => import('../pages/CouponsPage'))
const MarketplacePage = contextPage('mf_commerce/MarketplacePage', () => import('../pages/MarketplacePage'))
const DashboardPage = contextPage('mf_commerce/DashboardPage', () => import('../pages/DashboardPage'))
const PayoutsPage = contextPage('mf_finance/PayoutsPage', () => import('../pages/PayoutsPage'))
// Account administration belongs to the shell, which already owns the session and the account.
// A plain lazy import rather than contextPage(): there is no identity remote to fall back to.
const MembersPage = lazy(() => import('../pages/MembersPage'))

/**
 * Nav groups, in display order.
 *
 * Ten flat links gave equal weight to the board someone opens every morning and the import
 * they run once. Grouping restores that hierarchy: WORK is the daily loop, MONEY is the
 * attribution story, SETUP is what you touch rarely. Paths are unchanged, so existing links
 * and bookmarks still resolve.
 */
export const NAV_GROUPS = ['Work', 'Money', 'Setup']

export const ROUTE_MANIFEST = [
  {
    context: 'workflow',
    path: '/workflow',
    label: 'Board',
    group: 'Work',
    permission: 'workflow:read',
    component: WorkflowPage,
    apiSlice: 'workflow',
  },
  {
    context: 'campaign',
    path: '/campaigns',
    label: 'Campaigns',
    group: 'Work',
    permission: 'campaign:read',
    component: CampaignsPage,
    apiSlice: 'campaigns',
  },
  {
    context: 'creator',
    path: '/creators',
    label: 'Creators',
    group: 'Work',
    permission: 'creator:read',
    component: CreatorsPage,
    apiSlice: 'creators',
  },
  {
    context: 'content',
    path: '/content',
    label: 'Content',
    group: 'Work',
    permission: 'content:read',
    component: ContentPage,
    apiSlice: 'content',
  },
  {
    context: 'attribution',
    path: '/dashboard',
    label: 'Revenue',
    group: 'Money',
    permission: 'attribution:read',
    component: DashboardPage,
    apiSlice: 'commerce',
  },
  {
    context: 'attribution',
    path: '/coupons',
    label: 'Coupons',
    group: 'Money',
    permission: 'coupon:read',
    component: CouponsPage,
    apiSlice: 'commerce',
  },
  {
    context: 'finance',
    path: '/payouts',
    label: 'Payouts',
    group: 'Money',
    permission: 'payout:read',
    component: PayoutsPage,
    apiSlice: 'finance',
  },
  {
    context: 'attribution',
    path: '/marketplace',
    label: 'Marketplace',
    group: 'Money',
    permission: 'marketplace:connect',
    component: MarketplacePage,
    apiSlice: 'commerce',
  },
  {
    context: 'campaign',
    path: '/import',
    label: 'Import',
    group: 'Setup',
    permission: 'import:execute',
    component: ImportPage,
    // Which api/ slice this route depends on. Recorded so the extraction runbook can
    // answer "what moves with this page?" without reading every import.
    apiSlice: 'imports',
  },
  {
    // Account administration rather than a bounded context, so it stays in the shell and is
    // not a federation candidate. Gated on member:invite, which only OWNER and ADMIN hold —
    // that is what keeps it out of a marketer's nav without a second rule.
    context: 'identity',
    path: '/members',
    label: 'Members',
    group: 'Setup',
    permission: 'member:invite',
    component: MembersPage,
    apiSlice: 'core',
  },
]

/** Where a signed-in user lands: the board they work out of, not a dashboard of zeros. */
export const DEFAULT_ROUTE = '/workflow'

/** Nav entries the given permission set may see. */
export function visibleRoutes(permissions) {
  // An empty set means the token predates permission claims; show everything, because the
  // server still enforces each action and an empty nav would be worse than a permissive one.
  if (!permissions || permissions.length === 0) {
    return ROUTE_MANIFEST
  }
  return ROUTE_MANIFEST.filter((route) => permissions.includes(route.permission))
}

/**
 * Visible routes bucketed into nav groups, in NAV_GROUPS order.
 *
 * Empty groups are dropped rather than rendered as a bare heading — a marketer whose
 * permissions exclude every Money route should see no Money section at all.
 */
export function groupedVisibleRoutes(permissions) {
  const visible = visibleRoutes(permissions)
  return NAV_GROUPS.map((group) => ({
    group,
    routes: visible.filter((route) => route.group === group),
  })).filter((bucket) => bucket.routes.length > 0)
}

/** Routes grouped by owning context — the extraction unit. */
export function routesByContext() {
  return ROUTE_MANIFEST.reduce((acc, route) => {
    acc[route.context] = acc[route.context] || []
    acc[route.context].push(route)
    return acc
  }, {})
}
