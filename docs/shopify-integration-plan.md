# Shopify integration — plan

**Date:** 2026-08-09, revised after item 0 shipped
**Status:** items 0 and 0b done; adapter work blocked on a Partner app
**Covers:** EXECUTION-ROADMAP M3.2–3.5 (M3.1, credential encryption, shipped 2026-08-08)
**Method:** every claim below was re-checked against the code on 2026-08-09 after the webhook fix landed.

> **What changed since the first draft.** Both prerequisite defects are now **fixed and shipped**.
> Defect 1: `WebhookController` takes the raw body as a `String`, resolves the brand from the store
> via `MarketplaceService.verifyWebhook`, and no longer accepts `brandId` from the caller. Defect 2
> was narrower than the first draft claimed — a dedupe check existed, but no unique index sat behind
> it; two partial indexes now do. **No remaining item can be built without a Shopify Partner app.**

---

## What already exists, verified

The SPI is genuinely drop-in. `MarketplaceProviderRegistry` discovers implementations via
`List<MarketplaceProvider>` injection, so a new adapter is a `@Component` and nothing else changes.
`MockMarketplaceProvider` already implements the full interface, and the coupon-push, attribution,
commission and payout chain runs end to end against it.

So the work is **one adapter class, one OAuth flow, and fixing the two things below** — not a
platform project.

| Piece | State |
|---|---|
| `MarketplaceProvider` SPI | ✅ Complete: connect, coupon CRUD, `fetchOrders`, `verifyWebhook`, `normalizeOrderEvent` |
| Registry auto-discovery | ✅ Proven |
| Credential encryption (M3.1) | ✅ Envelope-encrypted; a real provider cannot connect without a KEK |
| Attribution pipeline | ✅ Coupon → sale → commission → payout, live against the mock |
| `POST /api/webhooks/marketplace/{providerKey}` | ✅ Authenticated: signature-verified, brand resolved from the store |
| Unique index behind order dedupe | ✅ Two partial indexes; 409 handled as `duplicate` |
| Shopify adapter | ❌ Not started |
| OAuth install flow | ❌ Not started |

---

## Prerequisites that are not Shopify work

Existing holes that connecting a real store would turn from theoretical into exploitable. One is
now closed; one remains.

### 1. ✅ FIXED — the order webhook is authenticated

Shipped 2026-08-09. [`WebhookController.java:73-95`](../InfluencerWebExperience/src/main/java/com/influencer/webe/attribution/api/WebhookController.java#L73-L95)
now takes `@RequestBody String rawBody`, calls `marketplaceService.verifyWebhook(providerKey,
headers, rawBody)` **before** ingestion, and derives the brand from the returned `VerifiedWebhook`.
The `brandId` request parameter is gone, so a caller cannot name a brand at all.

Two details worth keeping in view because the Shopify adapter depends on them:

- **The raw body is a `String`, never a re-serialised `JsonNode`.** The payload is parsed only
  *after* the signature verifies. An HMAC covers exact bytes; re-serialising changes key order and
  spacing and would never verify.
- **Resolution and authentication are one step, deliberately.** The connection is found by the
  provider's own store identifier and the signature is checked against *that row's* credentials. A
  brand resolved separately from the signature check could be a brand the signature does not cover.

`/api/attribution/simulate` remains the auth-scoped path the e2e journeys use, which is why the
public webhook did not need to stay permissive for tests.

### 2. ⏳ OPEN — ingestion dedupes, but only check-then-act

**Correction to an earlier draft of this document,** which said there was no dedupe at all. There
is: `findExistingAttribution(brandId, campaignCodeId, orderId, orderLineId)` runs before an
attribution is written.

The remaining weakness is narrower and still real. It is a read followed by a write with no unique
constraint underneath, so two concurrent deliveries of the same order — which Shopify produces by
retrying until it gets a 2xx — can both read "not found" and both insert. The window is small and
the consequence is double-counted revenue and double-accrued commission.

The billing side already solved exactly this: webhook events are recorded by provider event id
*before* being applied, with a **unique index** so a concurrent duplicate fails at the database
rather than racing through the gap. Add the same index here on
`(brand_id, external_order_id, external_order_line_id)` — the existing check then becomes the fast
path rather than the only defence.

**Both of these are the same lesson M8.3 taught with payout references: the money path needs an
idempotency key, and the key must come from the provider.**

---

## Build order

| # | Item | Size | Depends on | State |
|---|---|---|---|---|
| 0 | Webhook authentication + brand resolution from the store | 2d | — | ✅ Done 2026-08-09 |
| 0b | Unique index behind the existing dedupe check | 0.5d | — | ✅ Done 2026-08-09 |
| 1 | OAuth install flow + `shopify` connection | 3d | A Partner app, a public URL | Blocked |
| 2 | `ShopifyMarketplaceProvider` — connect, coupon CRUD | 3d | 1 | Blocked |
| 3 | `verifyWebhook` + `normalizeOrderEvent`, registered topics | 2d | 2 | Blocked |
| 4 | `fetchOrders` reconciliation + scheduler | 1d | 3 | Blocked |
| 5 | Mandatory GDPR webhooks + uninstall handling | 1d | 1 | Blocked |

**~10 dev-days remaining, and every one of them is blocked on a Shopify Partner app and a public
callback URL.** Both prerequisites are now shipped, so there is no remaining engineering that can
proceed without that account. Item 3 no longer depends on item 0 — the brand-resolution machinery it
needed now exists, so the adapter supplies a `verifyWebhook` body against a caller that already works.

---

## Item 0b — the unique index ✅ SHIPPED 2026-08-09

Migration: [`schema/migrations/2026_08_09_m3_order_attribution_idempotency.sql`](../schema/migrations/2026_08_09_m3_order_attribution_idempotency.sql).

`AttributionService.findExistingAttribution` runs before every write, but it was a read followed by
a write with nothing underneath it. Shopify retries `orders/paid` until it gets a 2xx, so two
concurrent deliveries could both read "not found" and both insert — double-counted revenue and
double-accrued commission.

**The nullable-column trap, and why there are two indexes.** `order_line_id` is nullable, and in
Postgres `null` is never equal to `null`, so the obvious single
`unique (brand_id, order_id, order_line_id)` would **not** stop duplicates on order-level events —
exactly the `orders/paid` case that retries most. It would have looked correct and caught nothing.
Shipped as two partial indexes:

- `uq_isa_brand_order_line` — `where order_line_id is not null`
- `uq_isa_brand_order_no_line` — `(brand_id, order_id) where order_line_id is null`

**`campaign_code_id` is deliberately not in the key.** The in-code check includes it, but the rule
being enforced is "this order line is attributed at most once", not "at most once per coupon".
Including it would let a second delivery of the same real order naming a different code accrue
commission twice.

**A constraint violation is handled as success.** `handlePurchase` catches the DAO's 409 and returns
`outcome: duplicate` with the winner's attribution id. A 5xx would tell Shopify the delivery failed;
it would retry for 48 hours, hit the same constraint, and fail identically — so a guard working
correctly would present as a permanently broken endpoint.

**Verified against the live database**, not just unit-tested. Applying the migration and replaying
Shopify's retry behaviour: a repeated order-level delivery is rejected by `uq_isa_brand_order_no_line`;
the same order claiming a different coupon code is also rejected; a genuinely different line on the
same order is still accepted. Covered by `OrderIdempotencyTest` (5 tests).

---

## Item 1 — OAuth install

Shopify's flow, and the three places it is easy to get wrong:

```
Merchant clicks Connect
  → GET /api/marketplace/shopify/install?shop=<store>.myshopify.com
      validate the shop domain, mint `state`, redirect to Shopify's authorize URL
  → merchant approves
  → GET /api/marketplace/shopify/callback?code=&hmac=&shop=&state=
      verify hmac, verify state, exchange code for a permanent access token
      → MarketplaceService.connect("shopify", credentials)   ← encrypts (M3.1)
```

**Validate the `shop` parameter against `^[a-z0-9][a-z0-9-]*\.myshopify\.com$`.** It lands in a
redirect URL; an unvalidated value is an open redirect on an OAuth entry point, which is the same
hazard the Stripe return URLs were hardened against.

**`state` must be single-use and bound to the session**, not just random. `OAuthHandoffService`
already implements exactly this shape — single-use, short TTL, consumed on read — and should be
reused rather than reimplemented.

**Verify the callback HMAC.** Shopify signs the query string; skipping it means accepting an
attacker-chosen `code`.

**Scopes:** `read_orders`, `write_discounts`, `read_products`. Not `write_orders` — the product
never modifies an order, and an unnecessary write scope is both an approval risk and a larger blast
radius if the token leaks. Note `read_orders` covers only the last 60 days unless the app is granted
`read_all_orders`, which needs review; that constrains backfill and should be stated to the customer
rather than discovered.

---

## Item 2 — the adapter

One class, `@Component`, implementing the seven SPI methods. Follow `StripeBillingProvider`: the
REST API directly via the existing `OutboundHttpClient`, no vendor SDK, so there is one dependency-
free client and no transitive surface.

| SPI method | Shopify |
|---|---|
| `connect` | `GET /admin/api/2024-10/shop.json` — validates the token and yields the shop domain for `externalAccountRef` |
| `createCoupon` | `priceRule` then `discountCode` — **two calls**, see below |
| `updateCoupon` | `PUT` the price rule |
| `deactivateCoupon` | `DELETE` the discount code; keep the price rule |
| `fetchOrders` | `GET /admin/api/2024-10/orders.json?updated_at_min=&status=any` |
| `verifyWebhook` | HMAC-SHA256 of the raw body with the app secret, base64, constant-time compare — signature is `verifyWebhook(byte[] body, ...)`, see below |
| `normalizeOrderEvent` | `discount_codes[].code` → `couponCode`; `current_total_price` → `saleAmount` |

**One encoding seam to get right.** The SPI declares `verifyWebhook(byte[] body, ...)` while the
controller now holds the body as a `String`. Whoever bridges those must convert with an explicit
`StandardCharsets.UTF_8` and no intermediate re-parse — a platform-default charset would verify on
one machine and fail on another, and that failure looks like a wrong secret rather than an encoding
bug. Constant-time compare (`MessageDigest.isEqual`), not `String.equals`.

**Coupon creation is two API calls, and that is the adapter's one real trap.** A price rule can be
created and the discount code then fail, leaving an orphaned rule that is invisible in the product
and confusing in the merchant's admin. The adapter must delete the price rule if the second call
fails. `ExternalCoupon.getExternalId()` should carry the **discount code id**, since that is what
`deactivateCoupon` needs.

**Normalisation details that decide whether the numbers are right:**

- `current_total_price` not `total_price` — the former reflects refunds and edits, the latter is the
  value at creation. Using `total_price` overstates revenue on every partially refunded order,
  which inflates commission.
- Refunds arrive as a separate `refunds/create` topic, not as a mutated order. Map them to
  `status = "refunded"`, which the pipeline already treats as a reversal.
- Shopify money fields are **strings**. Parse to `BigDecimal` directly; going via `double` loses
  cents at scale.
- Multi-currency stores report both shop and presentment currency. Record which one is being used,
  or a store selling in EUR will have its revenue silently summed as USD.

---

## Item 3 — webhooks

Register on connect: `orders/paid`, `orders/updated`, `orders/cancelled`, `refunds/create`.

Shopify sends `X-Shopify-Shop-Domain`, which is how the brand is resolved — look it up against
`marketplace_connections.external_account_ref`. **That lookup is the fix for defect 1**, and it is
why the two pieces belong in the same design even though defect 1 ships first against the mock.

Return 2xx quickly. Shopify times out at 5 seconds and retries for 48 hours; if attribution is slow,
acknowledge first and process asynchronously — but only after the event is durably recorded, or an
acknowledged event can be lost.

---

## Item 5 — what App Store review requires

Easy to discover late, so recorded now:

- **Three mandatory GDPR webhooks** — `customers/data_request`, `customers/redact`,
  `shop/redact`. They must exist and be HMAC-verified even if the app stores no customer PII.
- **`app/uninstalled`** — mark the connection `disconnected`. Without it the product keeps showing a
  connected store whose token has already been revoked, and every sync silently fails.
- The access token is revoked on uninstall; treat a 401 from Shopify as "disconnected", not as a
  transient error to retry forever.

---

## Definition of done

A brand connects a real Shopify store; a coupon created in the product appears in that store's
admin; a real order using that code is attributed with commission accrued; a refund reverses it; and
a forged webhook naming another brand is rejected.

The last clause is the one that distinguishes this from a demo.

---

## Recommendation

**Both prerequisites are shipped. The critical path is now procurement, not engineering.**
Item 0 closed the anonymous-request-to-money-owed path; item 0b closed the narrower race that
Shopify's retry behaviour actively provokes. Nothing further can be built without an account.

Everything from item 1 onward is blocked
on a Shopify Partner app and a public callback URL. That is the thing to start today, because it has
a lead time and the ~10 remaining dev-days do not begin until it lands. Registering the Partner app
is free and does not require the code to exist.

Worth deciding before item 1 starts, since it changes the shape of the work:

- **Public callback URL.** OAuth and webhooks both need one reachable from the internet. This is the
  same infrastructure gap `WEBE_HOSTING_TARGET` (M5.1) is waiting on — worth solving once.
- **`read_all_orders` or not.** Without it, `read_orders` sees only 60 days, which caps backfill.
  It needs Shopify review, so ask early or accept the limit and state it to customers.
- **App Store listing or custom app.** A custom app for a single merchant skips App Store review
  entirely, and with it item 5's GDPR webhooks. If the near-term goal is one design-partner store
  rather than public distribution, that removes ~1 day and a review cycle.
