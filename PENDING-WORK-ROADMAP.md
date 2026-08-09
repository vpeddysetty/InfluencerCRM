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
| **M2.1 / M2.2** Subscriptions + billing | 6d | ✅ Path built behind a port — ⏳ **needs Stripe credentials** (see below) |
| **M3.1** Envelope-encrypt credentials | 2d | ✅ Done 2026-08-08. See below |
| **M3.2–3.5** Shopify OAuth + adapter | 10d | ⏳ **Needs a Shopify Partner app and a public callback URL.** 3.1 no longer blocks it |

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

### The UI for it (same cycle)

**121 UI tests, up from 107.**

- **`shell/plan.js`** — all the arithmetic, in plain `.js` so the repo's bare `node --test` runner
  can import it (same reason `provenance.js` exists). Warns at 80% of a limit: a notice at 95% of
  a 25-creator plan arrives with one slot left, which is narration rather than warning.
- **`PlanUsage`** in the UI kit, shown on the Members page. Unlimited renders as `4210 · unlimited`
  and stays **neutral** — a page of green ticks devalues the one tone that should mean something.
- **The invite form disables at the seat limit** rather than letting the request fail. The server
  returns 402 either way, but a form that accepts an email, sends it, and *then* reports failure
  spends the user's attention on something that could never have worked. The copy names the
  remedy: pending invitations hold seats, so revoking one frees a seat immediately.
- **Landing page tier table** — free / pro / agency, signed out. **No prices**, because none has
  been decided and a UI file is not where that commitment should get made; the paid tiers say what
  they *lift*, not what they cost, and a test fails if a `$` appears. The free tier is described by
  its ceiling rather than a countdown, because it is capped by size and not by a clock.

**The numbers on the landing page are duplicated from `PlanPolicy`** — the page is signed out, so
there is no token and `/api/brands/plan` is unreachable. `shell/plan.js` carries a comment saying
they must track the server, and a test asserts the shape. Advertising a limit the server does not
enforce is the failure that guards against.

**Caught by rendering the real payload, not by a test:** the at-limit message read *"You have used
all 1 brands"* — and the free brand limit is exactly 1, so that was the common case, not an edge
case. It also ended *"cannot add more. Upgrade to Pro to add more."* Both fixed, with a regression
test that also covers the over-limit wording the two already-exceeding accounts will see.

---

## Shipped 2026-08-07 (fourth cycle) — subscriptions and billing

**204 BFF tests (was 178), 22 DAO, 131 UI (was 121).**

### The pricing recommendation

Grounded in [MARKET-ANALYSIS.md](MARKET-ANALYSIS.md), not invented:

| | Free | **Pro — $79/mo** | **Agency — $199/mo** |
|---|---|---|---|
| Brands | 1 | 1 | Unlimited |
| Creators | 25 | 250 | Unlimited |
| Members | 3 | 10 | Unlimited |

The contested SMB band is $49–798/mo. **Grin gates its actual CRM behind $500/mo and caps creators
at 100 there**, so $79 for 250 creators is a wedge rather than a race to the bottom. Agency at $199
undercuts "three separate contracts" at any competitor, none of which do multi-brand tenancy at all.

**The strategic point:** Grin's most-cited complaint is 12-month lock-in "impossible to stop". A
working cancel button is therefore the product feature, not a checkbox — which is why cancel is
prominent, confirms, and states exactly what is kept.

**These prices are NOT in the code.** No price appears anywhere in the repo, and a test fails if a
`$` shows up in the tier table. Prices belong in the payment provider's catalogue, which is the
only place they can be right.

### What is built, and what is not

**Built and tested:** schema (subscriptions, invoices, webhook events), entities, DAO endpoints,
the lifecycle state machine, subscribe/pause/resume/cancel, webhook handling with replay
protection, and the full UI.

**✅ Stripe adapter — added 2026-08-07** once a sandbox account existed. `StripeBillingProvider`
uses hosted Checkout and the hosted billing portal (the roadmap's instruction for 2.1: build no
billing UI), which is also what keeps card data out of this system entirely — no PAN, CVV or expiry
ever reaches it. The REST API directly rather than the SDK, matching the YouTube adapter: three
form-encoded POSTs against one dependency-free client.

`ManualBillingProvider` remains the default and still reports `chargesMoney=false` everywhere.

**✅ Webhook signature verification — implemented.** HMAC-SHA256 over the **raw** body, constant-time
comparison, and a five-minute freshness window. The endpoint still returns **503 while
`web-experience.billing.webhook-secret` is unset**, because that endpoint must be unauthenticated —
Stripe holds no user token — so the signature *is* the authentication. Without it, anyone who knew
the URL could POST `subscription.updated` and move their account onto the agency plan for nothing.

### Decisions worth keeping

**Who can cancel — the request was adjusted.** You asked for "brand owner or agency owner/admin",
but `ACCOUNT_BILLING` was already **OWNER-only** with a test asserting ADMIN does not hold it.
Rather than weaken that, the permission was **split**: new `ACCOUNT_BILLING_READ` (OWNER + ADMIN)
sees the plan and invoices; `ACCOUNT_BILLING` (OWNER only) pauses and cancels. An invited admin
cannot stop the company's service. Same separation-of-duties instinct as MANAGER approving
commissions without settling them.

**Two plan columns, on purpose.** `subscriptions.plan` is what is *billed*; `accounts.plan` is what
is *enforced*. They diverge during a pause — the subscription keeps `pro` (what resumes) while the
account drops to `free` (what `PlanPolicy` applies). Collapsing them would make pause either lose
the plan or keep granting paid limits for free. **Verified against the live database**, not only in
tests.

**`past_due` keeps paid limits.** A failed charge is usually an expired card and the provider
retries for days; breaking someone's workspace the moment a renewal fails punishes them for a
problem they are about to fix. But `past_due` **cannot be paused** — that would look like a way to
stop the retries, and the charge is still owed.

**Cancel does not confiscate paid time.** Default is cancel-at-period-end: the subscription stays
active until `currentPeriodEnd` and the UI names that date. `immediate` is opt-in.

**Replay safety is the roadmap's stated 2.2 requirement.** Every event is recorded by provider
event id *before* being applied, with a unique index so a concurrent duplicate fails at the
database rather than racing through a check-then-act gap. Out-of-order events cannot resurrect a
cancelled subscription — verified: `cancelled → active` is refused.

---

## Stripe sandbox wiring (2026-08-07)

**223 BFF tests (was 204), 22 DAO, 135 UI (was 131).**

### Paid plans are hidden on the landing page until billing is live

`VITE_BILLING_LIVE` is off by default, so a signed-out visitor sees **only the free tier**.
Advertising a plan nobody can buy is worse than advertising nothing: someone who wants to pay finds
no way to, and someone who signs up expecting those limits gets the free ones. Set it in the same
deploy that sets `WEBE_BILLING_PROVIDER=stripe`.

Build-time rather than a server read — the landing page is signed out and has no token, so it
cannot ask which provider is configured. **It hides marketing copy only**; enforcement is
`PlanPolicy` and `ACCOUNT_BILLING` on the server, which do not consult it.

### To test with your sandbox

Full instructions are in `application-local.properties.example` (git-ignored sibling holds the real
values — **never commit keys**). In short:

1. Stripe Dashboard → API keys → copy the **test** secret (`sk_test_…`)
2. Products → create Pro and Agency recurring monthly prices → copy each **price** id (`price_…`)
3. `stripe listen --forward-to http://localhost:8081/api/billing/webhooks/stripe` → copy the
   `whsec_…` it prints (this differs from the dashboard endpoint secret)
4. Set the four values, restart with the `local` profile, subscribe from `/billing`, card
   `4242 4242 4242 4242`

The BFF logs **"running in TEST MODE"** at startup for an `sk_test_` key, so a sandbox deployment
can never be mistaken for one taking real money.

### Decisions worth keeping

**A key alone is not "configured".** `isConfigured()` needs a secret key *and* at least one price
id. A key without prices would produce checkouts that always fail while the product reported the
account as subscribed, so in that state the adapter reports `chargesMoney=false` and the registry
falls back to `manual`.

**Checkout never activates a subscription.** It returns a URL; the user has not paid yet.
Activation happens on `checkout.session.completed`. Activating at click time would grant a paid
plan to anyone who opened the tab and closed it.

**The provider reference is re-pointed after checkout.** We store the Checkout *session* id (the
subscription does not exist yet), and the completion event carries the real subscription id — so
the row is found by what we stored and its reference replaced. Without that, every later event
would find nothing.

**Return URLs are built server-side, not taken from the caller.** A client-supplied redirect is an
open redirect, and a payment provider bouncing a user to an attacker-named site right after they
entered card details is about the most credible phishing hand-off there is. Only a relative path is
accepted; `//evil.example` is rejected too.

**Verified against compiled code, not only tests:** genuine signature accepted; tampered body,
wrong secret, 10-minute-old replay, missing header, and empty secret all rejected; typo in the
provider name falls back to `manual`.

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

### Known issue carried forward — ✅ resolved 2026-08-08

`establishSession` is now the single session-establishing path and the reload no longer renders a
tokenless workspace. See the fifth cycle below. The remaining piece is genuine social sign-in
*return*, which needs the DPS decision recorded there.

---

## Shipped 2026-08-08 (fifth cycle) — M3.1 and the reload bug

**246 BFF tests (was 223), 22 DAO, 149 UI (was 135).**

Both items here were picked for the same reason: they are the only things left on this document
that are not waiting on someone else's approval. Everything else outstanding needs a Shopify
Partner app, a Meta review, or your AWS console.

### M3.1 — the column named `credentials_encrypted` now is

`MarketplaceService` wrote credentials with `serializeCredentials(credentials)` and a
`// TODO Phase 6: envelope-encrypt` beside it. Verified against the compiled class before the
change: a connection stored `{"apiKey":"shpat_LIVE_SECRET_TOKEN","shop":"acme-store"}` verbatim.

A column whose *name* asserts a property it does not have is worse than an honestly-named one.
Every downstream reader — a DBA granting read access, a backup policy, an incident responder
triaging a dump — reasonably treats `credentials_encrypted` as already safe. The name was doing
active harm.

**Why envelope and not just a cipher call.** A single key over every row cannot be rotated:
rotation means decrypting and rewriting the whole table in one transaction, which is the migration
nobody runs, so the key never rotates. Each row now gets its own random data key; only that key is
wrapped with the long-lived KEK. Rotating rewrites a few hundred bytes per row, and a leaked data
key exposes one connection instead of the table.

The stored form is self-describing — `v1:<keyId>:<wrappedDek>:<dekIv>:<payloadIv>:<ciphertext>`.
The version prefix is what lets the algorithm change later without guessing at old rows; the key id
is what lets a rotation find rows it has not rewritten yet. Leaving either out is how a format
becomes permanent by accident.

**AES-256-GCM at both layers**, because GCM is authenticated. Under CBC or CTR, anyone who can
write to that column can flip bits in the ciphertext and silently change the credentials the
adapter then uses. The tag turns that into a loud failure. Every encryption draws a fresh IV from
`SecureRandom` — a repeated nonce under GCM is not a weakening but a break, leaking the XOR of two
messages and, worse, allowing the authentication key itself to be recovered.

#### Three decisions worth keeping

**It fails closed, and that was the whole design argument.** The tempting version encrypts when a
key is present and writes plaintext when it is not, so development just works. That fallback *is*
the incident: it is silent, it looks configured, and the one deployment where the variable was
missed is the one holding real store tokens. A provider that handles real credentials now refuses
to connect at all without a key.

**The check runs before the vendor handshake, not after.** Connecting first would send the
operator's real credentials to Shopify, establish a session, and only then discover there is
nowhere safe to put it — leaving a live grant this system cannot record and the operator has to go
revoke by hand. Refusing up front costs nothing.

**The safe default is the silent one.** `usesRealCredentials()` defaults to `true` on the SPI, so
an adapter author who never reads that method gets encryption; only an explicit `return false`,
visible in review, gives it up. Inverting it would make plaintext what you get by forgetting —
which is precisely how the column came to be misnamed. Only the mock overrides it, and requiring a
key for a fake store is what would have produced a shared dummy key committed to the repo.

**Legacy rows stay readable.** Existing rows hold bare JSON and no migration can help — they were
never encrypted. Reads accept both shapes and log a warning naming the row; writes only ever emit
an envelope, so rows re-encrypt as they are saved. Refusing them instead would have broken every
existing connection on deploy.

#### Caught by a test, and worth recording

`"0123456789abcdef0123456789abcdef"` is 32 characters — and *also* valid base64 decoding to 24
bytes. Deciding the format on syntax rejected it as "24 bytes" while the operator counts 32
characters and concludes the check is broken. Length decides now, not syntax, and the error message
reports both readings. A short key is still rejected outright rather than padded or hashed up to
size: silently stretching a weak key encrypts fine while carrying far less entropy than "AES-256"
implies, and nobody is told.

**Verified against the compiled class, not only tests:** the stored blob contains no fragment of
the token, round-trips, differs on every call for identical input, and the unconfigured case
refuses with a message naming the exact setting.

### The reload bug — a workspace with no token

`isLoggedIn` was persisted to `localStorage`; the access and refresh tokens deliberately were not
(writing them would hand an XSS payload the one thing that design exists to keep away). That flag
alone gates the entire workspace branch of the router. So a hard reload rebuilt the shell in its
signed-in state with nothing to authenticate with: every request went out bare and the user got a
workspace full of error banners rather than a login screen.

Restoring "you are logged in" from a source that cannot also restore "here is your proof" is what
made the two disagree. `isLoggedIn` is no longer restored — and no longer written either, since a
value nothing reads back would leave a stale `true` in storage that still reads as authoritative to
anyone inspecting it. The snapshot's other fields stay: brand, role and cached rows are display
state, harmless without a token, and keeping them is what lets a re-login land back in place.

**`establishSession` is now the single path a session comes into existence through.** It was
defined, correct, and never called, while `handleAuthSubmit` carried a near-identical copy — which
is exactly how the two drifted until only the copy was reachable. Collapsing them leaves one answer
to "what does being logged in consist of". The token is set before `isLoggedIn`, because the
workspace-loading effect keys on both and the other order gives it one render with a stale token.

**What this does not fix, deliberately.** Social sign-in still has no return handler in `App.jsx` —
the flow navigates to the DPS and nothing brings it back. That was already true; the difference is
that it now lands on a login page instead of a broken workspace. A real fix needs a decision this
change should not make quietly: `App.jsx` is a bearer-token client, while `UiSession.java:15` says
the DPS token is *"never serialised to the browser"*. Restoring a session into this SPA means
either handing it a token (negating that property) or migrating it to the cookie-backed
`PresentationGateway`. That is an architecture call, not a bug fix.

### Still outstanding, and what each is waiting on

| Item | Waiting on |
|---|---|
| **M5.1** hosting deploy | AWS console: `*.pages.tejdux.com` CNAME + ACM wildcard in **us-east-1**, then `WEBE_HOSTING_TARGET` |
| **M3.2–3.5** Shopify | A Shopify Partner app and a public callback URL |
| **M6** Instagram / TikTok bodies | Meta and TikTok approval |
| Email delivery | `web-experience.email.provider` is still `log` — the expiry sweep marks pages warned and sends to nobody |
| **U2** pagination | Nothing. Still the one whose failure mode is silent until severe |
| ~~Social sign-in return~~ | ✅ Resolved 2026-08-08 — see the sixth cycle below |

---

## Shipped 2026-08-08 (sixth cycle) — session restore, as a cookie session

**161 UI tests (was 149).** No server change: everything this needed already existed.

The previous cycle left the reload landing honestly on a login page and recorded the architecture
question. Investigating it changed the answer.

### The bearer restore was the obvious fix and the wrong one

`UiSession.java:15` says the access token is *"never serialised to the browser"*, and
`AuthController:82` says the handoff endpoint is *"called server-to-server by the DPS, **never** by
a browser"*. Both would have had to be reinterpreted to hand the SPA a token. Two independent
comments stating the same invariant is a decision already made, not an omission.

### It was cheap because the DPS proxy already does the work

`/dps/api/**` forwards to `/api/**` attaching the bearer *and* the `X-Brand-Id` header
server-side — the same two things `buildHeaders` does in `api/core.js`. And all ~90 `authToken`
references funnel into a single `request()` function. So cookie mode is a transport switch in one
place, not a 90-site refactor. That is the finding that made this a one-cycle change rather than
the migration it looked like.

Bearer mode is untouched and remains the default: a password sign-in still gets a token and still
uses it. Cookie mode carries only the sessions the browser was never handed a token for.

**`/dps/auth/oauth/complete` already redirects to `/` for this**, with a comment saying the cookie
is set "so the session exists before the UI loads and its first /dps/session call already sees an
authenticated user". The DPS was built expecting the call. The SPA simply never made it.

### Decisions worth keeping

**Permissions come from `SessionView` when there is no token.** Both paths trace to the same
token — the DPS reads perms out of it rather than recomputing, deliberately, because two
permission matrices disagreeing is what once locked FINANCE users out entirely.

**Cookie mode is set before `establishSession` flips `isLoggedIn`.** That flag releases the
workspace-loading effect; the other order fires one round of requests down the bearer path with
nothing to send.

**The first paint waits for the answer**, or the login page renders and is replaced by the
workspace — a sign-in flashing past on every reload. `/accept-invitation` is exempt: an invitee has
no session and came for that one page, so they should not wait on a lookup that will say anonymous.

**An unreachable DPS resolves to anonymous rather than throwing.** On a first visit "no session" is
the normal answer; treating it as an error would log a failure on every anonymous page load.

### Caught by running it, not by a test

Logout omitted the CSRF header, so the DPS answered **403 and the session survived**. The user is
told they signed out while the cookie stays valid — and the next boot silently restores them. This
is the failure mode the whole cycle was meant to remove, reintroduced at the exit. Verified live:
403 without the header, **204** with it, `authenticated: false` after.

**Verified end-to-end against the running DPS**, not only in tests: `/dps/session` restores a real
session with 33 permissions; `/dps/api/creators` returns **200** with the cookie and **401**
without; after logout both are anonymous.

### Steps 2 and 3 — the recommendation

Step 1 turned out to *be* most of step 2. What remains is smaller than planned:

| | What | Worth doing? |
|---|---|---|
| **2** | Make `token` optional throughout `api/core.js` and drop it from the ~90 call sites | **Not yet.** The parameter is already ignored in cookie mode. Removing it is churn across every context slice for no behaviour change, and it forecloses bearer mode while password login still uses it |
| **3** | Drop `authToken` from `SessionProvider` and handler signatures | **Only after** password sign-in also goes through the DPS (`POST /dps/auth/login`), which is what would make bearer mode genuinely dead code |

**The real next step is neither.** It is to move password sign-in onto `/dps/auth/login`, which
already exists and already sets the cookie. That collapses the two session architectures into one —
at which point steps 2 and 3 become mechanical deletions rather than judgement calls, and
`readPermissionsFromToken`, `refreshAccessToken` and the refresh timer all become removable.

Doing 2 and 3 *before* that would mean carefully preserving a bearer path that is about to be
deleted.

---

## Shipped 2026-08-08 (seventh cycle) — identity across the chain, logging, pricing, recorded journeys

**261 BFF tests (was 246), 27 DAO (was 22), 12 DPS (new), 162 UI (was 149), 9 e2e journeys (new).**

### The chain had one honest hop and three assumed ones

The session cookie was already right. Nothing after it was:

| Hop | Before | Now |
|---|---|---|
| Browser → DPS | httpOnly cookie ✅ | Plus `X-App-Id` entitlement |
| DPS → BFF | user's bearer only | Plus a DPS workload token |
| BFF → DAO | **one static shared string** | Workload token, dual-accept |

**Apps had no identity.** Every remote presenting a valid cookie got the whole session — all 33
permissions and every path. The content app, a captions screen, held `payout:approve`. `AppRegistry`
scopes both. Permissions are an intersection in *both* directions, so this never becomes a second
permission model competing with the role matrix — two of those disagreeing is what once locked
FINANCE users out entirely.

Scoping the permission list alone would be theatre; it only decides what the UI draws. The
enforcement is on the proxy, before the token is attached.

**The DAO trusted one static string** that never expired, identified nobody (the principal was the
hard-coded literal `"web-experience"`), authorized everything, and could only be rotated everywhere
at once. `WorkloadToken` gives it expiry, an issuer, an audience, a scope, and a **signed tenant** —
that last one is what will let the DAO stop trusting `brandId`, an *optional* query parameter whose
omission currently returns every tenant's rows.

**Dual-accept, deliberately.** This filter is on every request; a hard cutover means both services
deploy in the same instant with no single-service rollback. Legacy acceptances log at WARN naming
the caller, so deleting the old branch becomes evidence-based rather than a guess.

The subtle part, and it is tested: an *invalid* workload token is refused outright rather than
falling through to the legacy check. Otherwise an attacker who also knew the shared secret would
have their forgery accepted, and the audit line would name whoever the forgery claimed to be.

### Logging: async JSON lines, one directory

Hand-written encoder — `logstash-logback-encoder` is not in this offline build, and adding a
dependency to four services to format a string is a poor trade. One line per event **always**: a
stack trace is folded into one escaped field, because an embedded newline reads as several
malformed records and gets dropped — losing precisely the line that explains the outage.

`discardingThreshold=0` is non-default on purpose. The default drops INFO first when the queue
fills, which discards the context leading up to a failure exactly during the incident that filled
the queue.

### Free is now single-user, and roles are the paid feature

Free was 3 seats — a small team, not a free tier, and it gave away the thing worth charging for.
Now 1. The line is easy to explain (free is for you, paid is for your team) and it charges when a
team has actually adopted the tool rather than metering the evaluation.

Creators and brands are untouched: the wedge is spreadsheet replacement, and a roster too small to
hold a real creator list makes the product unevaluable.

`allowsRoleBasedAccess()` is a separate question from the seat count, not an inference from it.
It gates **assignment, never enforcement** — an account that downgrades keeps the roles its members
hold and the server keeps honouring them. Ignoring stored roles on a downgrade would silently
*widen* access, which is the worst possible way to express "this is paid".

### Recorded journeys, and what watching them found

Nine journeys, four personas, against the real stack. `tests/e2e`, stitched to
`influencrm-e2e-journeys.mp4` at the repo root (gitignored — a build output).

**Playing the video back caught a defect no unit test would have.** The landing hero still read
*"Free for 25 creators — no card, no time limit"* after the tier became single-user: the page's most
prominent sentence omitted the one term that had just changed. The tier table had been updated; the
hero had not. It is a sentence, not a value, and it was only visible because someone looked.

### Still outstanding

| Item | Waiting on |
|---|---|
| Delete the DAO's legacy token branch | The WARN lines going quiet in a real deployment |
| Close the optional-`brandId` IDOR | Nothing — the signed `tid` now exists to do it |
| Make `X-App-Id` mandatory | Every remote sending it; one line in `callingApp` |
| **M5.1** hosting deploy | AWS console |
| **M3.2–3.5** Shopify | A Partner app and a public callback URL |
| **M6** IG/TikTok bodies | Meta and TikTok approval |
| Email delivery | `web-experience.email.provider` is still `log` |
