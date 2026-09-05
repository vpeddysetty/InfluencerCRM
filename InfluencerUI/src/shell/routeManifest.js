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
/**
 * A page that loads from its remote, falling back to the bundled copy.
 *
 * <p><b>The remote import must be a LITERAL (roadmap OP-43).</b> This took
 * `import(/* @vite-ignore *\/ remoteSpecifier)` — a variable, with a comment explicitly telling the
 * bundler not to touch it — so the federation plugin never rewrote it and the browser was left to
 * resolve a bare specifier it has no import map for. Every call failed with
 * `TypeError: Failed to resolve module specifier`, the catch below quietly served the bundled page,
 * and production ran the shell's own copies while the manifest said otherwise.
 *
 * <p>So the caller passes a THUNK containing a literal import. The plugin can see it, rewrite it,
 * and emit the federation wiring; the fallback stays exactly as it was.
 *
 * <p>The fallback is not only for development: a remote failing at runtime should degrade to the
 * bundled page rather than take the app down, and the warning is what tells an operator that a
 * deploy is serving one.
 */
function contextPage(label, remoteImport, localImport) {
  if (!USE_REMOTES) {
    return lazy(localImport)
  }
  return lazy(() =>
    remoteImport().catch((error) => {
      // Surfaced deliberately: a silently-substituted page hides a broken deploy, and the operator
      // needs to know the gateway is serving a fallback rather than the remote.
      console.warn(`[gateway] remote ${label} unavailable, using bundled page`, error)
      return localImport()
    }),
  )
}

// One entry per page. Each names the remote that owns it and the bundled fallback.
//
// EXPORTED, and that is the point (roadmap OP-43). These are the only components that can load a
// remote: contextPage wraps each in a dynamic import of `mf_<scope>/<Page>` with the bundled copy
// as its fallback. App.jsx used to import the bundled pages DIRECTLY from ../pages and render those
// instead, so no route ever went through federation -- the manifest drove the nav rail and nothing
// else, and "production serves remotes" was false from the day the shell was written. Rendering
// these exact objects is what makes the manifest describe what actually loads.
export const ImportPage = contextPage('mf_campaigns/ImportPage', () => import('mf_campaigns/ImportPage'), () => import('../pages/ImportPage'))
export const CampaignsPage = contextPage('mf_campaigns/CampaignsPage', () => import('mf_campaigns/CampaignsPage'), () => import('../pages/CampaignsPage'))
export const CreatorsPage = contextPage('mf_creators/CreatorsPage', () => import('mf_creators/CreatorsPage'), () => import('../pages/CreatorsPage'))
export const ContentPage = contextPage('mf_content/ContentPage', () => import('mf_content/ContentPage'), () => import('../pages/ContentPage'))
export const WorkflowPage = contextPage('mf_workflow/WorkflowPage', () => import('mf_workflow/WorkflowPage'), () => import('../pages/WorkflowPage'))
export const CouponsPage = contextPage('mf_commerce/CouponsPage', () => import('mf_commerce/CouponsPage'), () => import('../pages/CouponsPage'))
export const MarketplacePage = contextPage('mf_commerce/MarketplacePage', () => import('mf_commerce/MarketplacePage'), () => import('../pages/MarketplacePage'))
export const DashboardPage = contextPage('mf_commerce/DashboardPage', () => import('mf_commerce/DashboardPage'), () => import('../pages/DashboardPage'))
export const PortfolioPage = contextPage('mf_commerce/PortfolioPage', () => import('mf_commerce/PortfolioPage'), () => import('../pages/PortfolioPage'))
export const PayoutsPage = contextPage('mf_finance/PayoutsPage', () => import('mf_finance/PayoutsPage'), () => import('../pages/PayoutsPage'))
// Account administration belongs to the shell, which already owns the session and the account.
// A plain lazy import rather than contextPage(): there is no identity remote to fall back to.
const MembersPage = lazy(() => import('../pages/MembersPage'))
const BillingPage = lazy(() => import('../pages/BillingPage'))
const SettingsPage = lazy(() => import('../pages/SettingsPage'))

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
    // The one ACCOUNT-scoped screen. Every other route here is answered per brand; this one asks
    // what the caller can reach and rolls up across it, which is the question an agency has and
    // nothing else answered. Same permission as Revenue: it shows the same figures, wider.
    context: 'attribution',
    path: '/portfolio',
    label: 'Portfolio',
    group: 'Money',
    permission: 'attribution:read',
    component: PortfolioPage,
    apiSlice: 'core',
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
  {
    // Gated on account:billing:READ, which OWNER and ADMIN hold — not on account:billing, which
    // is OWNER-only. An admin can see the plan and the invoices they administer against; the page
    // itself renders the pause and cancel buttons only when the server says canManage.
    context: 'identity',
    path: '/billing',
    label: 'Billing',
    group: 'Setup',
    permission: 'account:billing:read',
    // Case-study period (2026-09): hidden from everyone but the platform owner, because there is
    // no price to sell yet. An affordance only -- /api/billing/* answers 404 to anyone else, and
    // that server check is the actual gate. Remove this line when pricing exists.
    platformOwnerOnly: true,
    component: BillingPage,
    apiSlice: 'core',
  },
  {
    // No permission: this is the caller's OWN account, not the workspace's. Everyone who can sign
    // in can manage how they sign in, and the server scopes every operation here to the user id in
    // the token — so there is nothing a gate would protect.
    context: 'identity',
    path: '/settings',
    label: 'Settings',
    group: 'Setup',
    component: SettingsPage,
    apiSlice: 'core',
  },
]

/** Where a signed-in user lands: the board they work out of, not a dashboard of zeros. */
export const DEFAULT_ROUTE = '/workflow'

/**
 * Nav entries the given permission set may see.
 *
 * `isPlatformOwner` gates routes marked `platformOwnerOnly`. It is applied OUTSIDE the
 * empty-permissions branch below on purpose: that branch deliberately shows everything to a token
 * with no permission claims, and an owner-only route must not ride in on it. Defaulting the flag
 * to false means a caller who does not pass it gets the hidden behaviour, which is the safe way
 * round for a surface that is hidden because it is not on offer.
 */
export function visibleRoutes(permissions, isPlatformOwner = false) {
  const allowed = ROUTE_MANIFEST.filter((route) => !route.platformOwnerOnly || isPlatformOwner)
  // An empty set means the token predates permission claims; show everything, because the
  // server still enforces each action and an empty nav would be worse than a permissive one.
  if (!permissions || permissions.length === 0) {
    return allowed
  }
  // A route with no permission is visible to everyone who can sign in. That is not an oversight to
  // be defaulted away: /settings manages the caller's OWN account, and every operation behind it is
  // scoped server-side to the user id in the token, so there is nothing for a workspace permission
  // to protect. Filtering on `includes(undefined)` would silently hide such a route from everyone.
  return allowed.filter((route) => !route.permission || permissions.includes(route.permission))
}

/**
 * Visible routes bucketed into nav groups, in NAV_GROUPS order.
 *
 * Empty groups are dropped rather than rendered as a bare heading — a marketer whose
 * permissions exclude every Money route should see no Money section at all.
 */
export function groupedVisibleRoutes(permissions, isPlatformOwner = false) {
  const visible = visibleRoutes(permissions, isPlatformOwner)
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
