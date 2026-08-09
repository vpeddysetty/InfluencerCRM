// Product analytics (M0.2).
//
// Every validation signal in EXECUTION-ROADMAP.md — activation rate, "do brands come back in
// week two and move cards?", "does anyone click connect-your-own-domain?" — is unmeasurable
// without this. M7 (20 dev-days) is explicitly gated on a number that only exists if the
// domain-bind event is being counted.
//
// A PORT, not a vendor. The same shape as AssetStoragePort and PayoutProvider elsewhere in this
// codebase: an interface with a log-only local implementation, so a provider swap is a config
// change rather than a rewrite of every call site. No vendor SDK is imported here and none is in
// package.json — adding PostHog means implementing `deliver`, not touching the ~20 places that
// call `track`.
//
// Deliberately NOT a React hook or context. Analytics is called from event handlers, API
// callbacks, and route changes — a hook would force every one of those into a component.

const CONSOLE = 'console'
const NONE = 'none'

// Read once at module load. Vite inlines import.meta.env at build time, so this cannot be
// changed at runtime — which is correct: switching analytics providers mid-session would split
// one user's funnel across two backends.
const PROVIDER = (import.meta.env?.VITE_ANALYTICS_PROVIDER || CONSOLE).trim().toLowerCase()
const DEBUG = import.meta.env?.VITE_ANALYTICS_DEBUG === 'true'

/**
 * The canonical event list from EXECUTION-ROADMAP.md M0.2, plus the two the roadmap's own
 * validation tables need but did not name (signup, card-moved cover activation; the rest map
 * one-to-one).
 *
 * Frozen and exported so call sites cannot invent a name by typo. A misspelled event does not
 * error — it silently produces a funnel with a hole in it, six weeks before anyone notices.
 */
export const EVENTS = Object.freeze({
  SIGNUP: 'signup',
  IMPORT_COMPLETED: 'import-completed',
  CARD_MOVED: 'card-moved',
  COUPON_CREATED: 'coupon-created',
  ORDER_ATTRIBUTED: 'order-attributed',
  EXPORT_CLICKED: 'export-clicked',
  PUBLISH_CLICKED: 'publish-clicked',
  DOMAIN_BIND_CLICKED: 'domain-bind-clicked',
})

const KNOWN_EVENTS = new Set(Object.values(EVENTS))

// Identity is set once at sign-in and cleared at sign-out. Held here rather than passed to each
// call so no call site can attribute an event to the wrong account.
let identity = { userId: null, accountId: null, brandId: null }

/**
 * Associate subsequent events with a user. Called after sign-in and on session restore.
 *
 * Takes ids, never email or name. Analytics backends are a third party and a different retention
 * policy; sending PII there is a decision nobody made and it is hard to walk back.
 */
export function identify({ userId, accountId, brandId } = {}) {
  identity = {
    userId: userId || null,
    accountId: accountId || null,
    brandId: brandId || null,
  }
}

/** The active brand changes on every brand switch; keep events attributed to the right tenant. */
export function setAnalyticsBrand(brandId) {
  identity = { ...identity, brandId: brandId || null }
}

/** Clear identity at sign-out so a shared browser does not attribute events to the last user. */
export function resetIdentity() {
  identity = { userId: null, accountId: null, brandId: null }
}

/**
 * Record a product event.
 *
 * Never throws and never rejects. An analytics outage must not break a coupon from being created
 * — that inverts the priority between measuring the product and the product working. Every
 * failure path here ends in a swallowed error.
 *
 * @param {string} event one of EVENTS
 * @param {object} [properties] extra context; must not contain PII
 */
export function track(event, properties = {}) {
  try {
    if (!KNOWN_EVENTS.has(event)) {
      // Loud in dev, silent in production. An unknown event is a typo at a call site, and the
      // person who can fix it is the one running the dev server.
      if (DEBUG || import.meta.env?.DEV) {
        console.warn(`[analytics] unknown event "${event}" — add it to EVENTS in api/analytics.js`)
      }
      return
    }

    deliver({
      event,
      properties: properties || {},
      ...identity,
      // ISO-8601 so the payload is readable in a log and unambiguous across timezones.
      timestamp: new Date().toISOString(),
    })
  } catch {
    // Swallowed by design. See the note above.
  }
}

/**
 * The provider seam. Everything above is provider-agnostic; everything vendor-specific goes here.
 *
 * To add PostHog: npm i posthog-js, add a `posthog` case that calls posthog.capture, and set
 * VITE_ANALYTICS_PROVIDER=posthog. No call site changes.
 */
function deliver(payload) {
  switch (PROVIDER) {
    case NONE:
      return

    case CONSOLE:
    default:
      // The log-only implementation, and the current default. It makes the event stream visible
      // during development and in E2E runs without requiring an account, an API key, or egress —
      // the same reason FilesystemAssetStorage and ManualPayoutProvider exist.
      //
      // This is honest instrumentation, not a stub pretending to be a backend: the events fire,
      // carry real payloads, and can be asserted against. What it does not do is aggregate them,
      // which is why VITE_ANALYTICS_PROVIDER must be pointed at a real backend before any
      // roadmap milestone claims its validation signal was measured.
      if (DEBUG || import.meta.env?.DEV) {
        console.info('[analytics]', payload.event, payload)
      }
      return
  }
}

/** Which provider is active. Exposed so a diagnostics screen can state it rather than imply it. */
export function analyticsProvider() {
  return PROVIDER
}
