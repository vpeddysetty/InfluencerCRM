# Pending work — verified state and build order

**Date:** 2026-08-07
**Method:** every claim below was checked against the code on this date, not read off the other roadmaps. Where a roadmap statement no longer holds, it is marked **stale**.
**Companions:** [EXECUTION-ROADMAP.md](EXECUTION-ROADMAP.md) (business milestones) · [UI-OPPORTUNITIES-ROADMAP.md](UI-OPPORTUNITIES-ROADMAP.md) (UI depth)

---

## The mocking policy

**Decision (2026-08-07):** where a feature depends on Meta or TikTok API access, build the whole path and mock the provider. Wire the real integration when access lands.

This is already the established pattern in this codebase, and it is honest mocking rather than a stub pretending to be real:

- `MockSocialProfileGateway` derives followers/engagement from a stable hash, with engagement falling as audience grows — the real inverse relationship, so a vetting rule written against mock data behaves the same against a live read.
- It reports `source = "mock"` and **never** `platform_api`. A simulated number cannot be mistaken for a real one.
- `MockDomainRegistrar` and `ManualPayoutProvider` follow the same port shape.

The consequence for planning: **almost nothing is actually blocked**. Only the Instagram and TikTok adapter bodies need real credentials, and behind a dispatcher those become drop-in classes rather than a project.

---

## Build order (this cycle) — ✅ ALL FOUR SHIPPED 2026-08-07

| # | Item | Size | State |
|---|---|---|---|
| 1 | **M5.4 — real DNS verification** | 2d | ✅ Done. `DnsDomainRegistrar` does a real TXT lookup; the mock no longer defaults |
| 2 | **M6 slice — dispatcher, egress, OAuth schema, YouTube real, IG/TikTok mocked** | ~9d | ✅ Done. Registry + `OutboundHttpClient` + token schema + real YouTube adapter |
| 3 | **U4 — metrics provenance in the UI** | 3d | ✅ Done. Badge in the directory, full panel in the drawer and record page |
| 4 | **U1 — creator record page** | 8d | ✅ Done. `/creators/:id` gathers audience, revenue, campaigns, codes, commissions, workflow |

**Verified against the live stack**, not just tests: claiming `google.com` now returns
`verified: false` with a real DNS lookup (it returned `true` before); per-platform routing resolves
each platform independently and reports `metricsSource` honestly; the YouTube adapter makes a real
HTTPS call and redacts its API key from logs; a creator record shows $3,933 attributed revenue
matching the dashboard exactly.

**Totals after:** 132 Java tests, 107 UI tests, all passing.

Everything below the line is recorded, not scheduled.

### Found and fixed while building

- **Badge contrast (light mode).** `--success` and `--warning` measured **4.35:1** and **4.24:1**
  as 11px badge text, both under the 4.5:1 AA floor — and those are exactly the two tones the new
  provenance badges use. Caught by measuring before shipping. Added `--warning-on-tint` and
  `--info-on-tint` alongside the existing pair; all ten theme/tone combinations now pass.

### Correction to this document's own item 2

The roadmap sized 6.4 as "Instagram / TikTok / YouTube adapters, 6d" in one block. Splitting it was
the right call and the reason the slice landed: YouTube needed no review and is now genuinely real,
while Instagram and TikTok are `@Component` classes with simulated bodies. When Meta approves,
`InstagramProfileAdapter` gets a body and a real `isConfigured()`, and nothing else changes.

---

## 1. M5.4 — DNS verification is fake (SECURITY)

**Verified 2026-08-07** at `MockDomainRegistrar.java:53`:

```java
if (name.contains("unverified")) {
```

Any domain **not** containing the literal string `unverified` verifies instantly — including domains the caller does not own. A brand can claim `google.com`.

EXECUTION-ROADMAP already calls this "a security defect, not a missing feature." It is first because it is the only item on this list where waiting has a live-harm path, and it depends on nothing.

**Scope:** a real DNS TXT-record challenge behind the existing `DomainRegistrarPort` — issue a token, require it published at `_influencrm-challenge.<domain>`, resolve and compare. The mock stays for tests; what changes is that the mock stops being the only implementation and stops auto-verifying by default.

---

## 2. M6 slice — real metrics path, mocked where gated

EXECUTION-ROADMAP sizes M6 as 15 dev-days "gated on approvals". **Stale — that is only half true**, and under the mocking policy it is less true still.

| Sub-item | Gated on Meta/TikTok? | Verified state |
|---|---|---|
| 6.1 Outbound HTTP client | No | **None exists.** `DaoHttpClientFactory` is mTLS-to-DAO only |
| 6.2 Per-platform dispatcher | No | `SocialProfileGateway.fetch(platform, handle)` is one bean with **no routing**; the mock ignores `platform` except to echo it |
| 6.3 Creator OAuth token storage | No | **No schema.** The OAuth config covers Google/Facebook *login* only, not metrics scopes |
| 6.4 YouTube adapter | **No** — key already obtained | Buildable for real today |
| 6.4 Instagram / TikTok adapters | **Yes** | **Mock behind the dispatcher** |
| 6.5 Rate limiting, caching, quota | No | |
| 6.6 Tiered refresh scheduler | No | No `@Scheduled` refresh exists (five services have relay schedulers; none refresh metrics) |

**Outcome:** at least one genuinely real follower count on screen, `metrics_source` reading `platform_api` for YouTube, and a registry mirroring `MarketplaceProviderRegistry` — which is already proven drop-in via `List<T>` injection.

---

## 3. U4 — metrics provenance in the UI

**Correction to UI-OPPORTUNITIES-ROADMAP.** That document hard-gates U4 on M6 with the reasoning that surfacing "source: mock" before real metrics exist would advertise that none of it is real.

That holds for a **customer demo**. It is backwards for **building**: the badge, the plumbing, and the last-refreshed timestamp can all be built against the mock, and only the values change when Meta lands. After item 2, YouTube rows will genuinely read `platform_api`, so the badge has something true to say on day one.

**Scope:** provenance badge (`platform_api` / `mock` / `manual`), last-refreshed timestamp, and vetting status on the record page. `metricsSource` is already tracked and already in the CSV export — this makes it visible in-app.

---

## 4. U1 — creator record page

**Verified:** zero `useParams` in the codebase, zero detail routes. Every route is a list.

Unchanged from UI-OPPORTUNITIES-ROADMAP §U1. It is the defining CRM gap — the relationship is the asset, and today a creator's campaigns, coupons, payouts, and attributed revenue are scattered across four pages the user reassembles mentally.

---

## Recorded, not scheduled

### Not blocked, not started

| Item | Size | Verified state 2026-08-07 |
|---|---|---|
| **M2.3** `accounts.plan` enforcement | 3d | Zero repo-wide hits for `entitlement`, `quota`, or `PlanPolicy`. Still completely inert |
| **M5.1** Real hosting target | 2d | Still `pages.influencrm.example` — an RFC 2606 reserved name that **cannot resolve**. A brand following the on-screen instructions points their domain at nothing |
| **M5.6** Expiry-warning scheduler | 1d | Endpoints built and tested; **nothing calls them**. `@Scheduled` exists in five services, none for expiry |
| **M8.3** Payout idempotency | — | `ManualPayoutProvider:29` builds `"manual-" + creatorId.substring(0,8)` — **not unique per payout**. Two payouts to the same creator collide |
| **M2.1 / M2.2** Stripe checkout + webhooks | 6d | Not started |
| **M3.x** Shopify | 12d | Not started. **3.1 (envelope-encrypt credentials) must land before 3.2**, not after |

### UI depth

| Item | Size | Gate |
|---|---|---|
| **U2** Server-side pagination | 10d | None. Held deliberately — but its failure mode is silent until severe. Do not let it slip past the first customer with a real roster |
| **U3** Rate intelligence | 4d | U1 |
| **U5** Saved views | 4d | U2 (a saved filter that silently means "of page 1" is a bug factory) |
| **U6** Global search | 5d | U1 |
| **U7** Command palette | 4d | U6 |

### Pages still off the UI kit

`ContentPage` (641 lines — the significant one), `ImportPage`, `PayoutsPage`, `LandingPage`, `AcceptInvitationPage`. Landing is intentionally bespoke; AcceptInvitation is small and signed-out. **ContentPage is the one worth migrating.**

### Known issue carried forward

`establishSession` in `App.jsx` is defined and never called — the restore path for social sign-in and page-reload recovery. It is why a hard browser reload leaves the workspace shell rendered with no token. Needs its own decision about what a reload should restore. See [docs/analytics-date-range.md](docs/analytics-date-range.md).
