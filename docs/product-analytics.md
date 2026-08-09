# Product Analytics (M0.2)

**Date:** 2026-08-07
**Implements:** [EXECUTION-ROADMAP.md](../EXECUTION-ROADMAP.md) M0.2
**Code:** [InfluencerUI/src/api/analytics.js](../InfluencerUI/src/api/analytics.js)

---

## Why this exists

Every validation signal in the roadmap is unmeasurable without it. Specifically:

| Milestone | Signal it cannot measure without this | At stake |
|---|---|---|
| M1 | Which question a prospect asks after the demo | Whether the wedge is right |
| M2 | Conversion without a discount or a call | Whether the price clears the "why not free" bar |
| M4 | % of signups importing within 24h; % moving a card within 7 days | The activation baseline every later milestone is judged against |
| M5 | Clicks on "connect your own domain" | **Whether M7 gets built at all — 20 dev-days** |

M4's is the one the original [roadmap.md](roadmap.md) asked in Phase 1 — *"do imported brands come
back in week two and move cards?"* — and which has never been answerable.

---

## Design: a port, not a vendor

Same shape as `AssetStoragePort` and `PayoutProvider` elsewhere in this codebase. `analytics.js`
imports no vendor SDK, and none is in `package.json`. Adding PostHog means implementing one
function, not touching the call sites.

```
track(EVENTS.SIGNUP, {...})   ← ~8 call sites, provider-agnostic
        │
        ▼
    deliver()                  ← the seam; the only vendor-aware code
        │
        ├── console  (default) — logs the event stream, no account, no egress
        └── none               — drops silently
```

### Three decisions worth keeping

**1. `track()` never throws.** Every failure path ends in a swallowed error. An analytics outage
must not stop a coupon being created — that inverts the priority between measuring the product and
the product working.

**2. Identity carries ids, never email or name.** Analytics backends are a third party with a
different retention policy. Sending PII there is a decision nobody made and hard to walk back.

**3. `EVENTS` is frozen.** A misspelled event name does not error — it produces a funnel with a
hole in it, noticed six weeks later when someone tries to read the number. Unknown names warn
loudly in dev and are dropped in production.

---

## Configuration

| Variable | Values | Default | Meaning |
|---|---|---|---|
| `VITE_ANALYTICS_PROVIDER` | `console` · `none` | `console` | Which implementation `deliver` uses |
| `VITE_ANALYTICS_DEBUG` | `true` · unset | unset | Log events outside dev builds |

**The default is log-only, deliberately — not a silent no-op.** `none` would make every event
vanish with nothing on screen to say so. Console logging makes the stream visible in development
and E2E runs without requiring an account or egress, the same reasoning behind
`FilesystemAssetStorage` and `ManualPayoutProvider`.

> **This is instrumentation, not aggregation.** The events fire and carry real payloads, but
> nothing counts them over time. **No milestone may claim its validation signal was measured until
> `VITE_ANALYTICS_PROVIDER` points at a real backend.** Writing that down is the same discipline
> as `metrics_source = 'mock'` — a mock that claims to be a real provider is worse than no mock.

---

## Event catalogue

Six of the eight roadmap events are wired. Two have no UI surface yet.

| Event | Fires when | Where | Status |
|---|---|---|---|
| `signup` | A new account completes registration | `App.jsx` `handleAuthSubmit` | **Wired** |
| `import-completed` | An import batch is **hydrated** — creators actually land in the workspace | `App.jsx` hydrate handler | **Wired** |
| `card-moved` | A workflow card placement is **confirmed by the server** | `App.jsx` `placeCardRecord` | **Wired** |
| `coupon-created` | A coupon is generated, single or bulk | `App.jsx` `generateCouponRecord` / `…BulkRecord` | **Wired** |
| `order-attributed` | An order attributes to a coupon | `App.jsx` `simulateOrderRecord` | **Wired** (simulated only) |
| `export-clicked` | A CSV export is requested | [api/csv.js](../InfluencerUI/src/api/csv.js) `downloadCsv` | **Wired** — all four surfaces |
| `publish-clicked` | A landing page is published | — | **Pending M5** — no publish surface in the UI |
| `domain-bind-clicked` | "Connect your own domain" is clicked | — | **Pending M5** — no domain UI |

### Three judgement calls in the wiring

**`import-completed` fires on hydrate, not on upload.** An uploaded file that is never hydrated has
activated nobody. Hydration is where an import becomes creators in the workspace.

**`card-moved` fires after the server confirms.** `placeCardRecord` is optimistic with rollback.
Emitting on the optimistic update would count moves that never happened — in the exact metric M4
uses to judge week-two retention. There is a test asserting the event sits before the `catch`.

**`order-attributed` carries `simulated: true`.** The only current source is the debug-gated
simulator. Without the flag these would inflate the same metric real Shopify orders land in from
M3, and the number would look best precisely when it was least real. **When M3 lands, real orders
must emit `simulated: false`** — and the M2/M3 dashboards must filter on it.

**`export-clicked` is tracked in the download helper, not at each call site.** Every export routes
through `downloadCsv`, so a new export screen cannot be added without being counted. Tracking at
the four call sites would make the omission silent, and M1's signal depends on knowing whether
anyone exports at all.

### The two unwired events are not oversights

`publish-clicked` and `domain-bind-clicked` are defined in `EVENTS` and asserted by a test, but
have no call site because **those features do not exist yet**. Wiring them is part of M5.

`domain-bind-clicked` deserves particular attention: **M7 is 20 dev-days gated entirely on that
count.** If the domain UI ships in M5 without this event wired, M7's gate cannot be evaluated and
the decision defaults to building it — which is the opposite of what the roadmap intends.

---

## Adding a real provider

1. `npm i posthog-js`
2. In [analytics.js](../InfluencerUI/src/api/analytics.js), add a `posthog` case to `deliver()`
   that calls `posthog.capture(payload.event, payload)`.
3. Set `VITE_ANALYTICS_PROVIDER=posthog` and the project key in the deployed environment.
4. Verify a signup and an import appear in the dashboard — that is M0's own definition of done.

No call site changes. The test asserting `analytics.js` imports no vendor SDK will need updating
when a real provider lands; that is the intended signal that the seam has been crossed deliberately.
