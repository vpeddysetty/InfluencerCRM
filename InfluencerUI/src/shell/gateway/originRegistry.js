/**
 * Which origin serves which micro-frontend.
 *
 * <p>The gateway's job is to make many origins look like one application. This registry is the map
 * it navigates by: each entry says where a remote lives, what it exposes, and which permission
 * gates it.
 *
 * <p>Kept as data rather than scattered through import statements so that moving a remote to a CDN,
 * a preview environment, or a different port is a config change — not a code change in the shell.
 * Every URL is overridable per environment for exactly that reason.
 */

const env = (key, fallback) => {
  const value = import.meta.env?.[key]
  return value && String(value).trim() ? String(value).trim() : fallback
}

/**
 * One entry per bounded context that has a frontend.
 *
 * `scope` must match the `name` in that remote's federation config — a mismatch fails at runtime
 * with an opaque module-resolution error, so the two are worth keeping visibly adjacent.
 */
export const ORIGIN_REGISTRY = [
  {
    context: 'campaign',
    scope: 'mf_campaigns',
    origin: env('VITE_MF_CAMPAIGNS_ORIGIN', 'http://localhost:5175'),
    exposes: { CampaignsPage: './CampaignsPage', ImportPage: './ImportPage' },
  },
  {
    context: 'creator',
    scope: 'mf_creators',
    origin: env('VITE_MF_CREATORS_ORIGIN', 'http://localhost:5176'),
    exposes: { CreatorsPage: './CreatorsPage' },
  },
  {
    context: 'workflow',
    scope: 'mf_workflow',
    origin: env('VITE_MF_WORKFLOW_ORIGIN', 'http://localhost:5174'),
    exposes: { WorkflowPage: './WorkflowPage' },
  },
  {
    context: 'attribution',
    scope: 'mf_commerce',
    origin: env('VITE_MF_COMMERCE_ORIGIN', 'http://localhost:5177'),
    exposes: {
      CouponsPage: './CouponsPage',
      MarketplacePage: './MarketplacePage',
      DashboardPage: './DashboardPage',
    },
  },
  {
    context: 'finance',
    scope: 'mf_finance',
    origin: env('VITE_MF_FINANCE_ORIGIN', 'http://localhost:5178'),
    exposes: { PayoutsPage: './PayoutsPage' },
  },
  {
    context: 'content',
    scope: 'mf_content',
    origin: env('VITE_MF_CONTENT_ORIGIN', 'http://localhost:5179'),
    exposes: { ContentPage: './ContentPage' },
  },
]

/** Remote entry URL for a scope, or undefined if the scope is unknown. */
export function remoteEntry(scope) {
  const entry = ORIGIN_REGISTRY.find((r) => r.scope === scope)
  return entry ? `${entry.origin}/remoteEntry.js` : undefined
}

/** Shape the Vite federation plugin expects, built from the registry so the two cannot drift. */
export function federationRemotes() {
  return ORIGIN_REGISTRY.reduce((acc, entry) => {
    acc[entry.scope] = { type: 'module', name: entry.scope, entry: `${entry.origin}/remoteEntry.js` }
    return acc
  }, {})
}

/**
 * Every distinct origin the gateway federates.
 *
 * Useful for a CSP `connect-src`/`script-src` allowlist: a federated app loads executable code from
 * these origins, so they should be enumerated deliberately rather than left to a wildcard.
 */
export function federatedOrigins() {
  return [...new Set(ORIGIN_REGISTRY.map((entry) => entry.origin))]
}

export default ORIGIN_REGISTRY
