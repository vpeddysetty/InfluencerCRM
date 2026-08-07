# Analytics date range — design, testing, and the bugs it surfaced

**Date:** 2026-08-07
**Scope:** `GET /api/analytics/influencer-revenue`, `AnalyticsService`, the Revenue dashboard
**Status:** shipped and verified end to end against a live stack (Postgres → DAO → BFF → UI)

---

## What was built

The Revenue dashboard had no way to ask "how did we do *this month*?" — every KPI was all-time, with no statement of the period it covered. A revenue figure with no stated window is the easiest dashboard number to misread.

`influencer-revenue` now accepts two optional parameters:

| Param | Format | Meaning |
|---|---|---|
| `from` | `yyyy-MM-dd` (UTC) | inclusive start; omit for open-ended |
| `to` | `yyyy-MM-dd` (UTC) | inclusive end; omit for open-ended |

Omitting both preserves the previous all-time behaviour, so existing callers are unaffected.

The UI exposes four presets — last 7 days, last 30 days (default), last 90 days, all time — and names the active window beside the numbers ("Showing last 30 days").

### Why the filtering is server-side

`AnalyticsService` returns **pre-aggregated rollups** (KPI tiles, a per-creator leaderboard, a per-channel breakdown). The per-order rows a client-side filter would need never reach the browser. A UI-side date filter would therefore have been silently wrong — it could only have filtered leaderboard *rows*, not the orders inside them.

This was a correction to the original estimate: the range control was scoped as a cheap UI change and was in fact a controller + service change. The same misread applies to anything else that looks like "just a filter" over analytics.

### Design decisions worth knowing

**Filtering happens in memory, not SQL.** The rows are already loaded to compute the rollups, so the window is applied in the aggregation loop. This is correct at the volume the class documents. When `daily_attribution_stats` is materialised, the range belongs in the query.

**`occurredAt`, not `createdAt`.** The window keys on when the sale happened, not when this system heard about it. A Shopify backfill imported today would otherwise land every historical order in "last 7 days" — wrong in the direction that flatters us.

**The end date is inclusive.** `to` is converted to the exclusive start of the *following* day, so an order at 23:59:30 on the end date is inside the window. A naive `isBefore(to)` against midnight would silently drop the last day of every range.

**`days - 1` for presets.** "Last 7 days" spans today plus the six before it. Using `days` directly is the off-by-one that quietly widens every window.

**Undated rows are kept when open, dropped when bounded.** A row with a missing or unparseable timestamp cannot be proven to belong in a narrow window, so a bounded query excludes it; an unbounded query keeps it, so the default view never disagrees with the database.

**All-time sends no bounds at all**, rather than a very old `from`. That keeps undated rows visible in the default view.

---

## Bugs found by end-to-end testing

All three were found by running the real stack, not by reading code. All three are fixed.

### Bug 1 — flat fees were charged to every window (CRITICAL, fixed)

**Symptom.** A creator with a $1,000 flat fee showed:

| Window | Revenue | Total cost | ROI |
|---|---|---|---|
| Last 7 days | $300 | **$1,036** | **0.29×** |
| Last 30 days | $1,000 | $1,120 | 0.89× |
| All time | $3,933 | $1,434 | 2.74× |

The narrower the window, the worse ROI looked — from one unchanged fee. A brand checking "last 7 days" saw a profitable creator as a money-loser, on the single number this product exists to prove.

**Cause.** `flatFees` was summed from every workflow card with no date filtering. Only attributions were windowed.

**Why it was latent.** Before date filtering existed there was exactly one window — all-time — and the fee was always correctly "in" it. Adding ranges made the bug reachable. It was introduced by the feature, so it was fixed as part of it.

**Fix.** Flat fees are now filtered by the card's `createdAt` through the same window test as attributions, via a shared `withinRange(node, field, from, to)`.

**After the fix**, with the fee dated March:

| Window | Total cost | ROI |
|---|---|---|
| Last 7 days | $36 (commission only) | 8.33× |
| Last 90 days | $254 (commission only) | 9.58× |
| March | $1,000 (fee included) | — |
| All time | $1,434 (unchanged) | 2.74× |

**Known limitation.** `workflow_cards` has no effective-date column, so `createdAt` — when the card was raised — is the closest available proxy for "when this fee was agreed". It is an approximation, and the honest one: a fee agreed in January is not a cost incurred in August. If fee attribution needs to be exact, the schema needs an `agreed_at`.

### Bug 2 — inline feedback unreadable in dark mode (fixed)

**Symptom.** A session-expiry error rendered as dark red text on a dark red tint. Measured **1.54:1** (error) and **1.65:1** (success) against the 4.5:1 AA floor.

**Cause.** `.row-save-feedback` used hardcoded light-mode inks (`#7f1d1d`, `#0f5132`) over a translucent tint.

**Why the e2e run missed it.** The assertions checked that the text was in the DOM. It was — it was simply invisible. Only the screenshot caught it.

**Fix.** Two new tokens, `--success-on-tint` / `--danger-on-tint`, with dark-mode overrides. These are deliberately distinct from `--success`/`--danger`, which double as the accent and the button fill: those are tuned to carry a large shape at 3:1, while a 13px sentence on a 92%-opaque tint needs 4.5:1 and lands at 4.35:1 if it reuses them.

Measured after the fix: dark 9.94:1 / 12.44:1, light 6.08:1 / 7.53:1 — all four pass AA.

### Bug 3 — session expiry interrupted work (fixed; see the correction below)

**Symptom.** After ~30 minutes the dashboard shows "A valid Authorization Bearer token is required" and stops loading data.

**Correction to the original write-up.** This was first recorded as "the UI never uses its refresh token". That was wrong. `api/core.js` already retries once through `/api/auth/refresh` on a 401, and `App.jsx` already wires `setAuthHandlers`. The machinery was there and works — verified live: `POST /api/auth/refresh` returns a new access token and rotates the refresh token.

**What was actually wrong**, once the existing code was read properly:

1. **Refresh was reactive only.** It fired *after* a request had already failed, so the user saw an error banner first and the recovery second.
2. **A failed refresh evicted the user.** `onSessionExpired` cleared every session field and returned them to the login screen mid-task, taking any open drawer with unsaved edits.

**Fix.** Two changes, per the chosen behaviour (silent refresh, prompt only on failure):

- `shell/sessionExpiry.js` reads the `exp` claim from the JWT the app already decodes for `perms`, and `App.jsx` schedules a refresh **two minutes before expiry**. The renewal now happens ahead of the failure rather than after it.
- `SessionExpiredDialog` replaces the hard logout. It renders *outside* `<Routes>`, so the workspace stays mounted underneath and the user keeps their place while deciding between "Continue working" and "Sign out".

The dialog is deliberately **not** a `ConfirmDialog`: that component dismisses on Escape and on an overlay click, which is right for "are you sure you want to delete this" and wrong here. Dismissing does not give the session back — it hides the only control that can recover it and leaves a workspace whose every request 401s. Escape is swallowed and there is no overlay click handler.

**Verified in a browser** against the live stack, light and dark: an analytics 401 triggers `POST /api/auth/refresh`; when that refresh is forced to 401, the dialog appears with the workspace intact behind it, focus lands on "Continue working", it survives both Escape and an overlay click, and "Continue working" restores the session in place without bouncing to the login screen.

**Testing note worth keeping.** Tokens are held **in memory only** — never in `localStorage`, deliberately, so a stale snapshot cannot resurrect a session. A full page load therefore sends *no* `Authorization` header, and `request()` skips refresh entirely because it only refreshes a 401 that carried a token. Any test of this path must use SPA navigation (clicking a rail link); `page.goto()` silently tests a different, unauthenticated scenario. Three test iterations failed for this reason before it was diagnosed.

**Adjacent gap, not fixed.** `establishSession` in `App.jsx` is defined and never called — it is the restore path for social sign-in and page-reload session recovery, and it is unfinished work predating this change. It is the reason a full page reload leaves the workspace shell rendered with no token. Fixing it is a separate piece of session work and would need its own decision about what a reload should restore.

---

## Not bugs (investigated and dismissed)

**Duplicate analytics request on mount.** The dev server issues two identical requests before any interaction. This is React `StrictMode` double-invoking effects, which is dev-only by design. Verified against a production build: exactly one request per range change.

**"2 table rows" when one creator was seeded.** A test-selector artifact — the count included both the leaderboard and the channel-breakdown tables.

---

## How it was tested

Against a live stack: Postgres (Docker) → DAO (8443, mTLS) → BFF (8081) → UI, with a real signed-up account and a real login through the form.

**Dataset.** Nine attributions seeded at deliberate offsets — 0, 6, 7, 29, 30, 89, 90, 200 days ago, plus a refund at 45 days — so each preset boundary has a row on both sides of it. Two more at `00:00:30Z` and `23:59:30Z` on one UTC day for the midnight edges.

**Windows verified against hand-computed totals:**

| Window | Expected | Actual |
|---|---|---|
| 7d | 300 | 300 ✅ |
| 30d | 1000 | 1000 ✅ |
| 90d | 2100 | 2100 ✅ |
| all | 3600 | 3600 ✅ |

**Edge cases:** single-day range; inclusive end boundary; `from` only; `to` only; inverted range (`from > to` → empty, no error); future window; ancient window; leap day. All correct.

**UTC boundaries:** an order at `00:00:30Z` and one at `23:59:30Z` on the same day both land in that day, and neither leaks into the adjacent one.

**Malformed input:** `notadate`, `2026-13-45`, `2026-02-30`, a datetime where a date is expected, `08/07/2026`, and a SQL-ish string all return **400**. Empty `from=` binds to null (open-ended, 200). A negative year parses and degrades correctly.

**Tenancy:** a second account passing the victim's `brandId` — with and without a date range — receives its own empty data, not the victim's. The `brandId` parameter is ignored in favour of the token's resolved brand. The date parameters open no bypass.

**Refunds:** a refund dated 45 days ago appears in `refundedRevenue` for a 90-day window and not for a 30-day one.

### Automated coverage

`AnalyticsWindowTest` (10 tests) covers the windowing logic directly: revenue windowing, both inclusive boundaries, undated-row behaviour in open vs. bounded ranges, flat fees in/out/all-time, inverted ranges, and unparseable timestamps.

These were confirmed to actually catch the bug: reverting only the flat-fee fix produces `expected: <36.00> but was: <1036.00>`.

The suite uses a hand-written `DaoGatewayClient` subclass rather than Mockito — Mockito's bundled bytecode engine cannot mock that class under Java 26, and a stub this small does not need a framework.

**Totals:** 113 Java tests, 97 UI tests, all passing.

---

## Files changed

| File | Change |
|---|---|
| `AnalyticsController.java` | `from`/`to` params with `@DateTimeFormat` |
| `AnalyticsService.java` | ranged overload, shared `withinRange`, flat-fee windowing |
| `AnalyticsWindowTest.java` | new — 10 tests |
| `api/commerce.js` | `getInfluencerRevenue(token, {from, to})` |
| `shell/dateRange.js` | new — presets and range arithmetic |
| `pages/DashboardPage.jsx` | range control, window caption, kit migration |
| `App.css` | tokenised `.row-save-feedback` |
| `index.css` | new `--success-on-tint` / `--danger-on-tint` |
| `ux-changes.test.mjs` | range arithmetic + contrast regressions |
| `shell/sessionExpiry.js` | new — `exp` parsing and refresh scheduling |
| `components/ui/SessionExpiredDialog.jsx` | new — the re-prompt |

## Operational note

The BFF requires a persistent JWT signing key. For a single-process local run:

```
mvn spring-boot:run -Dspring-boot.run.arguments="--web-experience.allow-ephemeral-jwt-key=true"
```

The key is regenerated on each boot, so tokens issued before a restart stop working — re-authenticate after restarting the BFF.
