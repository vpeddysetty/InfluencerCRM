# UI Opportunities Roadmap — InfluenCRM

**Date:** 2026-08-07
**Grounded in:** UI benchmark against HubSpot / Salesforce / Grin / CreatorIQ patterns, code read directly 2026-08-07
**Companion to:** [EXECUTION-ROADMAP.md](EXECUTION-ROADMAP.md) — that document sequences *what makes the product sellable*; this sequences *what makes the UI competitive*
**Does not replace:** anything. Where the two overlap, EXECUTION-ROADMAP wins on sequencing.

---

## What already shipped

The benchmark's eight remediation items are **done and verified** (86 UI tests, 103 Java tests, lint clean, build green, checked in a real browser at 1440px and 390px in both themes):

| # | Item | Where |
|---|---|---|
| 1 | Dashboard migrated to the `components/ui` kit | `pages/DashboardPage.jsx` |
| 2 | Bulk row selection + `BulkActionBar` | `components/ui/DataTable.jsx`, `Primitives.jsx` |
| 3 | Dashboard date-range filter, server-side | `shell/dateRange.js`, `AnalyticsService.java`, `AnalyticsController.java` |
| 4 | Theme toggle (light / dark / system) | `components/ui/ThemeToggle.jsx`, `index.html` |
| 5 | Filter state in the URL | `shell/useUrlFilters.js`, `pages/CreatorsPage.jsx` |
| 8 | Self-hosted Fraunces + Manrope | `src/fonts.css`, `public/fonts/` |

Two **pre-existing bugs** surfaced while verifying, both fixed and regression-tested:

- **Mobile horizontal scroll.** `.workspace-main` / `.workspace-content` were grids with an implicit `auto` track, so a wide table stretched the entire page — 323px of overflow at a 390px viewport. `min-width: 0` on the container does not fix this; the *track* needs `minmax(0, 1fr)`.
- **Dark mode unreadable.** `.mds-theme table` and `.mds-theme strong` in App.css carried hardcoded light-mode literals that beat the tokenised `components/ui` styles on specificity. Every KPI value and table cell rendered near-black on near-black. Measured contrast went from ~1.2:1 to 14.6:1.

**Items 6 (record page) and 7 (pagination) were deliberately not built** — they are the two structural items, and they are what this roadmap sequences.

### Correction to the original benchmark

I sized the date-range filter as a cheap UI control. It was not: `AnalyticsService` returns pre-aggregated rollups, so the per-order rows a client-side filter would need never reach the browser. It required a controller + service change. It was still worth doing at that size, but the estimate was wrong and the same misread applies to anything else that looks like "just a filter" over analytics.

---

## Assumptions

Same as EXECUTION-ROADMAP, restated so this document stands alone:

- **One full-time engineer**, Claude-assisted.
- **Sizes are dev-days**, excluding review queues.
- **Sequence and relative size are the deliverable.** Absolute dates are yours to set.
- **These are opportunities, not obligations.** U1 and U2 are load-bearing; the rest are genuinely optional and ordered by value-per-day.

---

## The sequence in one diagram

```
U1 Creator record page (8d) ──┬─► U3 Rate intelligence (4d)
                              ├─► U4 Trust badges (3d)  ◄── gated on M6
                              └─► U6 Global search (5d)

U2 Pagination (10d) ─────────────► U5 Saved views (4d)

U7 Command palette (4d) ◄── gated on U6
```

**U1 and U2 are independent of each other** and can run in either order. Everything else hangs off one of them.

---

## U1 — Creator record page

**Size: L (8 dev-days)** · **Blocks: U3, U4, U6** · **No external gate**

**Goal:** `/creators/:id` exists and is where a relationship lives.

**Why first:** this is the defining gap. Every route today is a list; there are zero detail routes and no `useParams` anywhere in the codebase. A creator opens in an edit *drawer* — a form, not a record. The consequences compound:

- No shareable URL. Nobody can send a colleague "look at this creator" — daily friction in an agency where talent gets handed off.
- No relationship history. A grep for activity/timeline/notes/tasks/audit across every page returns nothing.
- Data is scattered by page, not gathered by record: campaigns in Campaigns, coupons in Coupons, payouts in Payouts, attributed revenue in Dashboard. The user reassembles it mentally, every time.

For an influencer CRM the relationship *is* the asset. Grin and CreatorIQ both center a creator profile with communication history.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 1.1 | Route + manifest entry `/creators/:id` | 1d | `routeManifest.js` is already the seam; add a nested route. Keep the drawer for quick edits — a record page and a fast edit are different jobs |
| 1.2 | Identity header | 1d | Avatar, name, handle, platform, **preferred rate**, vetting status. Reuse `PageHeader` |
| 1.3 | Related records panels | 3d | Campaigns, coupons, payouts, attributed revenue for this creator. **The data already exists** — this is aggregation and layout, not new endpoints |
| 1.4 | Activity timeline | 2d | Start with what is already recorded: created, edited, imported, coupon issued, order attributed. **Do not build a notes system yet** — see below |
| 1.5 | Row → record navigation | 1d | Clicking a row opens the record; the drawer becomes an explicit "Edit" action |

### Decide during U1, not later

**Does the timeline need a new table?** 1.4 as scoped reads existing rows and derives events. A general activity log (notes, tasks, mentions, assignment) is a schema change and a permissions question, and it is how this milestone becomes 20 days instead of 8. Ship the derived timeline, watch whether anyone asks to *write* to it, then decide.

### Definition of done

A creator's URL can be pasted into Slack and opens that creator, with their campaigns, coupons, payouts, and attributed revenue on one screen. **If the page merely repeats what the row already showed, this is not done** — the test is whether it answers a question the list cannot.

### Validation signal

Record-page views per active brand per week. If brands open the list and never a record, the relationship model is wrong and U3/U4/U6 should not be built on it.

---

## U2 — Server-side pagination

**Size: L (10 dev-days)** · **Blocks: U5** · **No external gate**

**Goal:** a 5,000-creator roster loads as fast as a 50-creator one.

**Why it is expensive and why it cannot wait forever:** I grepped the entire `api/` layer for `limit|offset|page=|cursor` — the only hit is an unrelated CSV comment. Every list fetches in full and filters client-side. At 200 creators this is fine. At 5,000 the page loads every record, sorts in JS, and renders every row unvirtualized. Salesforce paginates at 50, HubSpot at 100.

This is a **backend contract change** through controller → service → DAO → SQL, which is why it is 10 days and why it is the most expensive item to defer. Deferring costs nothing until it costs everything — the failure mode is a customer with a large roster whose account is unusable on day one.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 2.1 | Paged DAO queries | 3d | `LIMIT`/`OFFSET` or keyset. **Prefer keyset** for stable paging under concurrent writes |
| 2.2 | BFF paged endpoints | 2d | Envelope with `items` + `total` + `cursor`. Must survive the `ResponseShapeService` allow-list — see [BFF Projection Allow-List](memory) |
| 2.3 | Server-side sort + filter | 3d | **The real cost.** Client-side sort/filter must move server-side or page 2 disagrees with page 1 |
| 2.4 | `DataTable` pagination UI | 1d | Page controls, count, page size |
| 2.5 | Reconcile select-all with paging | 1d | **Do not skip.** "Select all" currently means all *visible* rows; with paging that becomes ambiguous, and the honest answer is an explicit "select all N matching" affordance |

### Resolve before starting

**Does select-all-across-pages act on rows the user never saw?** Yes, and that is what users expect — but it must say so. HubSpot's pattern (a banner reading "All 47 on this page selected — select all 1,204 matching") is the one to copy. Getting this wrong makes bulk delete dangerous.

### Definition of done

A brand with 5,000 creators sees first paint in the same time as a brand with 50, sorting is correct across page boundaries, and CSV export still exports what the filter says it does.

---

## U3 — Rate intelligence

**Size: M (4 dev-days)** · **Gated on: U1**

**Goal:** "what does this creator cost us, and is that a good price?" is answerable on screen.

**Why:** the code itself notes `preferredRate` has no documented competitor equivalent, and EXECUTION-ROADMAP M1.1 already spent 2 days surfacing it end to end. It is currently the 5th field in a drawer. A differentiator nobody can sort by is not yet a differentiator.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 3.1 | Rate as a sortable column + filter | 1d | Already a `DataTable` column; add range filtering |
| 3.2 | Rate vs. attributed revenue on the record page | 2d | The actual insight: cost beside what it returned. Requires U1's panels |
| 3.3 | Roster rate distribution | 1d | Median / range across the roster, so one rate has context |

**Explicitly not here:** rate *recommendations*. That is a modelling problem with a cold-start issue on a single brand's data, and it needs U2's volume before it means anything.

### Validation signal

Sorts and filters on the rate column. If nobody sorts by rate, the differentiator is real but not wanted, which is worth knowing before 3.2.

---

## U4 — Metrics provenance and trust

**Size: S (3 dev-days)** · **Gated on: U1 and EXECUTION-ROADMAP M6**

**Goal:** a real follower count is visually distinguishable from a derived one.

**Why gated:** `metricsSource` is already tracked and already exported — the CSV carries it so a real count cannot be confused with a simulated one outside the company. But today every metric is FNV-1a hash-derived from the handle string. **Surfacing provenance before M6 lands would advertise that none of it is real.** After M6, this becomes a direct answer to a top-three buyer objection: influencer fraud.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 4.1 | Provenance badge in-app | 1d | `platform_api` vs `mock` vs `manual`. Reuse `Badge` — it already encodes state in colour *and* text |
| 4.2 | Last-refreshed timestamp | 1d | A real number with an unknown age is its own trust problem |
| 4.3 | Vetting status on the record page | 1d | Already computed by `VettingRuleEngine` (13 tests) |

### Definition of done

A brand can tell at a glance which numbers came from a platform API. **Ship nothing here while `metrics_source` still reads `mock` for every row.**

---

## U5 — Saved views

**Size: M (4 dev-days)** · **Gated on: U2**

**Goal:** "Instagram creators over $2k, sorted by revenue" is a saved thing, not something you rebuild each morning.

**Why the gate:** filters now live in the URL (shipped, item 5), which is exactly the groundwork — a saved view is a stored string. But saving a *client-side* filter set that silently means "of the first page" is a bug factory. Server-side filtering (U2.3) must land first.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 5.1 | Persist named views per brand | 2d | Small table; the URL query string is the payload |
| 5.2 | View switcher in the page header | 1d | |
| 5.3 | Default view per user | 1d | Salesforce list views, scoped down |

**Explicitly not here:** sharing views across users, and view-level permissions. Both are real asks and neither is worth building before anyone has saved a second view.

---

## U6 — Global search

**Size: M (5 dev-days)** · **Gated on: U1**

**Goal:** find anything without first knowing which page it lives on.

**Why gated on U1:** search results need somewhere to land. Without record pages, a cross-entity result set can only deep-link to a list — which is the problem it was meant to solve.

### Scope

| # | Item | Size | Notes |
|---|---|---|---|
| 6.1 | Cross-entity search endpoint | 3d | Creators, campaigns, coupons. **Must respect tenancy** — see the isolation tests in `CrossTenantIsolationTest` |
| 6.2 | Search UI in the rail | 1d | |
| 6.3 | Grouped results by entity type | 1d | |

---

## U7 — Command palette (⌘K)

**Size: M (4 dev-days)** · **Gated on: U6**

**Goal:** keyboard-first navigation for daily drivers.

**Why last:** it is the highest-polish, lowest-necessity item on this list. It is a thin layer over U6's search plus the existing route manifest, and it is genuinely delightful — but nobody has ever churned for lack of a command palette. Build it when the things above are done, or when a specific customer asks.

---

## What I would not build

Stated explicitly, because a roadmap that only says yes is not advice:

- **Configurable dashboard widgets.** HubSpot's dashboard is user-composed and yours is hardcoded. That is a real gap, but the date range (shipped) closes most of the practical pain, and widget configuration is a large surface for a benefit that shows up only after brands have opinions about their metrics. Revisit after M6 makes the metrics real.
- **Mobile-first redesign.** Mobile now fits correctly at 390px with no horizontal scroll, and tables scroll within their wrapper. That is *usable*, not *optimised*. Card-per-row mobile layouts are worth it only if analytics show real mobile usage — which M0.2's instrumentation can now answer. Do not guess.
- **A notes/tasks system.** The single most requested CRM feature in the abstract, and the one most likely to be built and unused here. U1.4's derived timeline is the cheap test: if brands ask to write to it, build it then.

---

## Relationship to EXECUTION-ROADMAP

| This document | Interacts with |
|---|---|
| U1 record page | Independent. Uses M1.1's `preferredRate` work |
| U2 pagination | Independent. Should land before any customer with a large roster |
| U3 rate intelligence | Extends M1.1 |
| U4 trust badges | **Hard gate on M6** — do not ship while metrics are mock |
| U5 saved views | Independent |
| U6/U7 search | Independent |

**Nothing here competes with M0–M3 for sequencing.** Those milestones decide whether the product can be sold; these decide whether it feels like a CRM once it is. If forced to choose, EXECUTION-ROADMAP wins — with one exception: **U2 should not slip past the first customer with a real roster**, because that failure is silent until it is severe.
