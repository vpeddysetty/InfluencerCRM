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

/**
 * Reads a build-time variable, from an explicitly supplied source when there is one.
 *
 * <p><b>Why the parameter exists (roadmap OP-43).</b> `import.meta.env` is populated for
 * APPLICATION code and empty inside `vite.config.js`. This module is imported by both, so called
 * from the config every origin silently became its localhost fallback — and production was built
 * pointing at `http://localhost:5176`. The config now passes what `loadEnv` read; application code
 * calls this with nothing and gets `import.meta.env` as before.
 */
const readEnv = (key, fallback, source) => {
  const value = (source ?? import.meta.env)?.[key]
  return value && String(value).trim() ? String(value).trim() : fallback
}

/** Builds the registry against a given env source. See {@link readEnv} for why that is a parameter. */
function registryFor(source) {
  const env = (key, fallback) => readEnv(key, fallback, source)
  return ORIGIN_REGISTRY_FOR(env)
}

/**
 * One entry per bounded context that has a frontend.
 *
 * `scope` must match the `name` in that remote's federation config — a mismatch fails at runtime
 * with an opaque module-resolution error, so the two are worth keeping visibly adjacent.
 */
const ORIGIN_REGISTRY_FOR = (env) => [
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

/** The registry as application code sees it — `import.meta.env`, populated at build time. */
export const ORIGIN_REGISTRY = ORIGIN_REGISTRY_FOR((key, fallback) => readEnv(key, fallback))

/** Remote entry URL for a scope, or undefined if the scope is unknown. */
export function remoteEntry(scope) {
  const entry = ORIGIN_REGISTRY.find((r) => r.scope === scope)
  return entry ? `${entry.origin}/remoteEntry.js` : undefined
}

/** Shape the Vite federation plugin expects, built from the registry so the two cannot drift. */
export function federationRemotes(envSource) {
  return (envSource ? registryFor(envSource) : ORIGIN_REGISTRY).reduce((acc, entry) => {
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
