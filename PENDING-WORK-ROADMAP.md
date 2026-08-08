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
| **M2.3** `accounts.plan` enforcement | 3d | ✅ Done. `PlanPolicy` + `EntitlementService` enforce at four creation points |
| **M5.1** Real hosting target | 2d | ✅ Code done — ⏳ **deployment step outstanding** (see below) |
| **M5.6** Expiry-warning scheduler | 1d | ✅ Done. `HostingExpiryScheduler` warns at 30/7/1 days |
| **M8.3** Payout idempotency | — | ✅ Done. The payout id is now the idempotency key |
| **M2.1 / M2.2** Stripe checkout + webhooks | 6d | Not started |
| **M3.x** Shopify | 12d | Not started. **3.1 (envelope-encrypt credentials) must land before 3.2**, not after |

---

## Shipped 2026-08-07 (second cycle) — the three small defects

**Totals after:** 158 Java tests in the BFF (was 132), 22 in the DAO, all passing.

### M8.3 — payout references collided

`ManualPayoutProvider` built `"manual-" + creatorId.substring(0,8)`, so **every** payout to a given
creator carried the identical reference. There is no unique constraint on `provider_ref` — which is
why this was silent rather than a database error. An operator reconciling a bank statement could
not tell two payments apart.

The fix is at the SPI, not the string: `pay()` now takes the payout id as its first argument. That
id already existed — `PayoutService` creates the payout row *before* calling the provider — it was
simply never passed. It is the correct idempotency key precisely because it survives a retry, so a
real Stripe/PayPal adapter passes it as `Idempotency-Key` and a timeout retry settles once.

**Also fixed while there:** a throw from `pay()` left the row stranded in `processing` with its
commissions still `approved` — invisible to the payouts list *and* to the next payout attempt.

### M5.1 — a real hosting target, and why the default did not change

The decision doc names `pages.tejdux.com`. **Checked with a live DNS lookup: that name does not
resolve.** `www.tejdux.com` resolves to CloudFront; `pages.tejdux.com` returns NXDOMAIN — the
record has not been created.

So the default was deliberately **left** as the RFC 2606 placeholder. Both names fail, but a
plausible one fails *silently and looks configured*, while `.example` is recognisable as a
placeholder. Swapping it would have closed the roadmap item while making the failure harder to see.

What changed instead is that an unconfigured target can no longer masquerade as a working one:
`DnsDomainRegistrar` logs a WARN at startup and **omits the CNAME line entirely**, replacing it with
"custom-domain hosting is not yet available on this deployment". Ownership verification is
unaffected — the TXT step still works, since proving ownership does not depend on where pages are
served. Verified against the real bean, not only in tests.

**To finish M5.1 (deployment, not code):**
1. Create `*.pages.tejdux.com` pointing at the CloudFront distribution
2. Issue the ACM wildcard for `*.pages.tejdux.com` (**us-east-1** — CloudFront requires it)
3. Set `WEBE_HOSTING_TARGET=pages.tejdux.com`

Step 3 alone restores the CNAME instructions; nothing else changes.

### M5.6 — expiry warnings now actually fire

`HostingExpiryScheduler` sweeps daily and warns the account owner at 30, 7 and 1 days. The BFF had
no `@EnableScheduling` at all, so this is its first scheduled job; it is off by default
(`WEBE_EXPIRY_WARNINGS`), like the event relay.

**The part worth knowing:** it does *not* ask "is this page exactly 7 days out today?". That reads
naturally and is wrong — one missed run (a deploy, an outage, a DST boundary) and the threshold is
skipped *permanently*, because tomorrow the answer is 6. It asks instead for the **smallest
threshold now passed and not yet sent**, recorded in a new `hosting_warning_sent_at_days` column.
That is idempotent within a day and self-healing across missed days.

Two bugs the tests caught before this shipped, both under-warning near the deadline — the worst
possible direction:
- A page at day 7 already warned at day 30 returned "nothing due", because 30 was passed *and* sent.
- A page with 20 hours left returned 30 rather than 1.

`extendHosting` clears the marker, or a page extended at day 1 would keep `1` forever and go dark
unannounced at the end of its extension.

**Still required to actually deliver mail:** `web-experience.email.provider` is `log`. With the
default the sweep runs, marks pages warned, and sends to nobody.

---

## Shipped 2026-08-07 (third cycle) — M2.3 plan enforcement

**Totals after:** 178 BFF tests (was 158), 22 DAO, all passing.

`accounts.plan` has existed since the Phase 2 tenancy migration, defaults to `'free'`, is stored,
and is returned by the API — and **nothing had ever read it**. Every account had unlimited
everything. The column did not merely do nothing; it reported a plan that meant nothing.

**Tiers** (`PlanPolicy`, an enum — changing what a plan includes is a pricing decision and should
appear in a diff):

| | brands | creators | members | landing pages |
|---|---|---|---|---|
| `free` | 1 | 25 | 3 | 3 |
| `pro` | 1 | 250 | 10 | 25 |
| `agency` | ∞ | ∞ | ∞ | ∞ |

Creator caps are in the range competitors meter at (MARKET-ANALYSIS.md §2). Multi-brand is what
actually separates `agency` — the tiers differ in capability, not only in size.

### Three decisions worth keeping

**The plan is read live, never put in the JWT.** The token already carries `acc`, `role` and
`perms`, so adding `plan` was the obvious move — and wrong. A plan in a token is frozen at issue
time, so a customer who upgrades stays blocked until it expires. That is the single worst moment in
the product to serve a stale answer. Creation is not a hot path; one extra read is cheap.

**Unknown plans fail closed to `free`, never to unlimited.** The column is free text with no check
constraint, so a typo or an unmigrated value is reachable — and must not become a silent free
upgrade. A DAO outage falls back to `free` too: an outage must not be a way past the limits.

**402, not 403.** The caller is authorized; their plan simply does not include this. 403 tells a UI
to hide the action, 402 tells it to offer the upgrade. The message names the limit, the plan, the
next tier, and says existing data is untouched.

### Measured against the live database before choosing numbers

Per-account maxima on 2026-08-07: **2 brands, 5 creators, 6 members, 2 landing pages.** Creator and
page limits were set clear of that, so no existing account was frozen on release day. Two
deliberately do bite — brands (1 account) and members (1 account). Both freeze at current size;
nothing is deleted. Recorded as a decision in `PlanPolicyTest`, not left to be rediscovered.

**Enforcement points:** creators, brands, invitations, landing pages. Two subtleties:
- **Invitations count against the member limit.** Counting members alone would let an at-capacity
  account send invitations that all fail on acceptance — the invitee hits the wall having done
  nothing wrong, and the admin never sees an error.
- **The landing-page endpoint is an upsert**, so the check fires only when it would actually
  create. Checking unconditionally would turn a cap on how many pages you may *have* into a cap on
  whether you may *edit* the ones you already own.

**Also added:** `GET /api/brands/plan` returns the plan and current usage, so a limit is visible
before it is hit rather than only as a 402; `GET /tenancy/accounts/{id}` in the DAO; and
`PATCH /tenancy/accounts/{id}` now accepts `plan`, which is where a billing integration writes an
upgrade. **Nothing sets a plan to anything but `free` yet** — that is M2.1/M2.2.

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
