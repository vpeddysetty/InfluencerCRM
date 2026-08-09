# Shopify integration — plan

**Date:** 2026-08-09
**Status:** plan only, nothing built
**Covers:** EXECUTION-ROADMAP M3.2–3.5 (M3.1, credential encryption, shipped 2026-08-08)
**Method:** every claim below was checked against the code on this date.

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
| `POST /api/webhooks/marketplace/{providerKey}` | ⚠️ Exists, **and is unauthenticated — see below** |
| Shopify adapter | ❌ Not started |
| OAuth install flow | ❌ Not started |

---

## Two defects that must be fixed first

These are not Shopify work. They are existing holes that connecting a real store would turn from
theoretical into exploitable, and they are cheap now and expensive later.

### 1. The order webhook is unauthenticated and takes `brandId` from the caller

[`WebhookController.java:35-45`](../InfluencerWebExperience/src/main/java/com/influencer/webe/attribution/api/WebhookController.java#L35-L45):

```java
public JsonNode webhook(@PathVariable String providerKey,
                        @RequestParam(required = false) UUID brandId,   // ← from the caller
                        @RequestBody ObjectNode payload) {
    UUID resolved = brandId != null ? brandId : getUuid(payload, "brandId");
    return attributionService.ingest(resolved, providerKey, payload);   // ← no signature check
}
```

`verifyWebhook` is on the SPI and **is never called**. So anyone who knows the URL can post a
fabricated order naming any brand and any coupon code, and it flows straight into attribution —
which accrues commission, which becomes a payout. This is a path from an anonymous HTTP request to
money owed.

It is survivable today only because the mock is the sole provider and nobody has the URL. It stops
being survivable the moment a real store is connected, because then the endpoint has to be publicly
reachable.

**Fix:** resolve the brand from the *store* (`marketplace_connections.external_account_ref` matched
against Shopify's `X-Shopify-Shop-Domain`), never from the request; call `verifyWebhook` before
`ingest` and reject on failure; take the raw body as a `String` for HMAC, exactly as
`StripeSignature` already does — a body parsed to `JsonNode` and re-serialised will never verify.

### 2. Ingestion is not idempotent

Shopify retries a webhook until it gets a 2xx, and `orders/paid` can fire more than once for one
order. `attribute()` has no check on `externalOrderId`, so a retry attributes the sale twice and
accrues commission twice.

The billing side already solved this: webhook events are recorded by provider event id *before*
being applied, with a unique index so a concurrent duplicate fails at the database rather than
racing through a check-then-act gap. Do the same here, keyed on
`(marketplace_connection_id, external_order_id, external_order_line_id)`.

**Both of these are the same lesson M8.3 taught with payout references: the money path needs an
idempotency key, and the key must come from the provider.**

---

## Build order

| # | Item | Size | Depends on |
|---|---|---|---|
| 0 | Webhook authentication + brand resolution from the store | 2d | — |
| 0b | Order-ingestion idempotency | 1d | — |
| 1 | OAuth install flow + `shopify` connection | 3d | A Partner app, a public URL |
| 2 | `ShopifyMarketplaceProvider` — connect, coupon CRUD | 3d | 1 |
| 3 | `verifyWebhook` + `normalizeOrderEvent`, registered topics | 2d | 0, 2 |
| 4 | `fetchOrders` reconciliation + scheduler | 1d | 3 |
| 5 | Mandatory GDPR webhooks + uninstall handling | 1d | 1 |

**~13 dev-days**, of which **items 0 and 0b (3 days) are buildable today** — they need no Shopify
account at all.

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
| `verifyWebhook` | HMAC-SHA256 of the raw body with the app secret, base64, constant-time compare |
| `normalizeOrderEvent` | `discount_codes[].code` → `couponCode`; `current_total_price` → `saleAmount` |

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

**Build items 0 and 0b now.** They need no Shopify account, they close a live path from an anonymous
request to money owed, and item 3 depends on the brand-resolution work anyway. Doing them first also
means the day the Partner app is approved, the remaining work is an adapter rather than an adapter
plus a security fix under time pressure.
