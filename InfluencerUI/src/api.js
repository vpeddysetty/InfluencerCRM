// Barrel for the per-context API slices.
//
// Phase 6 split this module into api/core.js plus one file per bounded context. This barrel
// re-exports them so every existing `import { listCreators } from './api'` keeps working — a
// micro-frontend remote imports its own slice directly and pulls in nothing else.

export * from './api/core'
export * from './api/campaigns'
export * from './api/creators'
export * from './api/workflow'
export * from './api/commerce'
export * from './api/finance'
export * from './api/content'
export * from './api/imports'
