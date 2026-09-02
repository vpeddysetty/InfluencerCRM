package com.influencer.webe.attribution.application;

import com.influencer.webe.shared.application.ResponseShapeService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.marketplace.MarketplaceProvider;
import com.influencer.webe.marketplace.MarketplaceProviderRegistry;
import com.influencer.webe.marketplace.OrderEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Attribution pipeline (Phase 3): normalize an order event → resolve the coupon
 * by code → dedupe on (order_id, order_line_id) → write an
 * {@code influencer_sale_attributions} row → accrue an
 * {@code influencer_commissions} row. Refund/cancel events transition the
 * attribution and claw back (void) the commission.
 *
 * Attribution model: last-touch on the coupon code (simple + defensible).
 *
 * <h2>Why the dedupe is in two places</h2>
 *
 * <p>{@code findExistingAttribution} is the fast path: it answers "already attributed?" with a read
 * and costs nothing. It is not, however, a guarantee — it is a read followed by a write, so two
 * concurrent deliveries of the same order can both see "not found" and both proceed.
 *
 * <p>The guarantee is the unique index added in
 * {@code 2026_08_09_m3_order_attribution_idempotency.sql}. The loser of that race gets a 409 from
 * the DAO and reports {@code duplicate}, exactly as though the check had caught it. This matters
 * specifically because Shopify retries until it receives a 2xx: a duplicate must be a success, or
 * the guard that prevents double-counting becomes an endpoint that never stops being retried.
 */
@Service
public class AttributionService {
    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final MarketplaceProviderRegistry registry;

    public AttributionService(DaoGatewayClient dao,
                              ResponseShapeService shape,
                              MarketplaceProviderRegistry registry) {
        this.dao = dao;
        this.shape = shape;
        this.registry = registry;
    }

    /**
     * Ingest a raw order payload from a provider (webhook or simulation). Resolves
     * the provider adapter, normalizes, and runs the attribution pipeline.
     * Returns a small JSON summary of what happened.
     */
    public JsonNode ingest(UUID brandId, String providerKey, JsonNode rawPayload) {
        MarketplaceProvider provider = registry.find(providerKey).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown marketplace provider: " + providerKey));
        OrderEvent event = provider.normalizeOrderEvent(rawPayload);
        return attribute(brandId, providerKey, event);
    }

    /**
     * Core pipeline over a normalized event. Public so a poller/webhook can call
     * it directly with an already-normalized event.
     */
    public JsonNode attribute(UUID brandId, String platform, OrderEvent event) {
        ObjectNode result = shape.objectMapper().createObjectNode();
        result.put("orderId", event.getExternalOrderId());

        if (event.getCouponCode() == null || event.getCouponCode().isBlank()) {
            result.put("outcome", "unattributed");
            result.put("reason", "no coupon code on order");
            return result;
        }

        JsonNode coupon = resolveCouponByCode(brandId, event.getCouponCode());
        if (coupon == null) {
            result.put("outcome", "unattributed");
            result.put("reason", "no coupon matches code " + event.getCouponCode());
            return result;
        }

        String status = event.getStatus() == null ? "purchase" : event.getStatus().toLowerCase(Locale.ROOT);
        boolean isReversal = status.equals("refunded") || status.equals("cancelled") || status.equals("canceled");

        JsonNode existing = findExistingAttribution(brandId, coupon.get("id").asText(),
                event.getExternalOrderId(), event.getExternalOrderLineId());

        if (isReversal) {
            return handleReversal(brandId, coupon, event, existing, result);
        }

        if (existing != null) {
            result.put("outcome", "duplicate");
            result.put("attributionId", existing.get("id").asText());
            return result;
        }

        return handlePurchase(brandId, coupon, platform, event, result);
    }

    // ---- purchase ------------------------------------------------------

    private JsonNode handlePurchase(UUID brandId, JsonNode coupon, String platform,
                                    OrderEvent event, ObjectNode result) {
        BigDecimal sale = event.getSaleAmount() == null ? BigDecimal.ZERO : event.getSaleAmount();
        BigDecimal discount = event.getDiscountAmount() == null ? BigDecimal.ZERO : event.getDiscountAmount();
        BigDecimal commission = computeCommission(coupon, sale, discount);
        String currency = event.getCurrency() == null ? "USD" : event.getCurrency();

        ObjectNode attribution = shape.objectMapper().createObjectNode();
        attribution.put("brandId", brandId.toString());
        attribution.put("campaignCodeId", coupon.get("id").asText());
        attribution.put("campaignId", coupon.get("campaignId").asText());
        attribution.put("creatorId", coupon.get("creatorId").asText());
        if (coupon.hasNonNull("campaignCreatorId")) {
            attribution.put("campaignCreatorId", coupon.get("campaignCreatorId").asText());
        }
        attribution.put("platform", normalizePlatform(coupon, platform));
        attribution.put("status", "attributed");
        attribution.put("orderId", event.getExternalOrderId());
        if (event.getExternalOrderLineId() != null) {
            attribution.put("orderLineId", event.getExternalOrderLineId());
        }
        if (event.getCustomerExternalId() != null) {
            attribution.put("customerExternalId", event.getCustomerExternalId());
        }
        attribution.put("saleAmount", sale.toPlainString());
        attribution.put("discountAmount", discount.toPlainString());
        attribution.put("netAmount", sale.subtract(discount).toPlainString());
        attribution.put("commissionAmount", commission.toPlainString());
        attribution.put("currency", currency);
        if (event.getOccurredAt() != null) {
            attribution.put("occurredAt", event.getOccurredAt().toString());
        }

        JsonNode savedAttribution;
        try {
            savedAttribution = dao.post("/influencer-sale-attributions", attribution);
        } catch (ResponseStatusException rejected) {
            if (rejected.getStatusCode() != HttpStatus.CONFLICT) {
                throw rejected;
            }
            // Lost a race with a concurrent delivery of this same order. The unique index
            // (uq_isa_brand_order_line / uq_isa_brand_order_no_line) refused the second insert,
            // which is the index doing its job rather than an error.
            //
            // Reporting the winner's row as a duplicate — not rethrowing — is the whole point.
            // A 5xx here would tell Shopify the delivery failed, and it retries for 48 hours; the
            // retry would hit the same constraint and fail identically, so a working guard would
            // masquerade as a broken endpoint forever.
            return duplicateOf(brandId, coupon, event, result);
        }

        ObjectNode commissionRow = shape.objectMapper().createObjectNode();
        commissionRow.put("brandId", brandId.toString());
        commissionRow.put("attributionId", savedAttribution.get("id").asText());
        commissionRow.put("creatorId", coupon.get("creatorId").asText());
        commissionRow.put("campaignId", coupon.get("campaignId").asText());
        commissionRow.put("grossSale", sale.toPlainString());
        commissionRow.put("commissionAmount", commission.toPlainString());
        commissionRow.put("currency", currency);
        commissionRow.put("status", "pending");
        JsonNode savedCommission = dao.post("/influencer-commissions", commissionRow);

        result.put("outcome", "attributed");
        result.put("attributionId", savedAttribution.get("id").asText());
        result.put("commissionId", savedCommission.get("id").asText());
        result.put("commissionAmount", commission.toPlainString());
        return result;
    }

    /**
     * The outcome when a concurrent delivery won the race to insert this order line.
     *
     * <p>Re-reads the row the winner wrote so the caller gets the same {@code duplicate} answer it
     * would have got had the check at the top of {@link #attribute} seen it. From the provider's
     * side the two paths are indistinguishable, which is what makes the endpoint idempotent rather
     * than merely guarded.
     *
     * <p><b>Still reports success if the re-read comes back empty.</b> The constraint has already
     * proven a row exists — the only reasons not to see it are a read that has not caught up or a
     * commission accrued under a different coupon code, and neither is a reason to tell the
     * provider its delivery failed. Retrying is the one thing that would definitely not help,
     * because the constraint that refused this insert will refuse it identically next time.
     */
    private JsonNode duplicateOf(UUID brandId, JsonNode coupon, OrderEvent event, ObjectNode result) {
        JsonNode winner = findExistingAttribution(brandId, coupon.get("id").asText(),
                event.getExternalOrderId(), event.getExternalOrderLineId());

        result.put("outcome", "duplicate");
        if (winner != null && winner.hasNonNull("id")) {
            result.put("attributionId", winner.get("id").asText());
        } else {
            result.put("reason", "a concurrent delivery of this order was recorded first");
        }
        return result;
    }

    // ---- refund / cancel ----------------------------------------------

    private JsonNode handleReversal(UUID brandId, JsonNode coupon, OrderEvent event,
                                    JsonNode existing, ObjectNode result) {
        if (existing == null) {
            result.put("outcome", "reversal_no_match");
            result.put("reason", "no prior attribution for order " + event.getExternalOrderId());
            return result;
        }

        // Flip the attribution to refunded.
        ObjectNode attrUpdate = existing.deepCopy();
        attrUpdate.put("status", "refunded");
        dao.put("/influencer-sale-attributions/" + existing.get("id").asText(), attrUpdate);

        // Void the related commission(s).
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        JsonNode commissions = dao.get("/influencer-commissions", q);
        String clawedId = null;
        if (commissions != null && commissions.isArray()) {
            for (JsonNode c : commissions) {
                if (c.hasNonNull("attributionId")
                        && c.get("attributionId").asText().equals(existing.get("id").asText())
                        && !"paid".equalsIgnoreCase(text(c, "status"))) {
                    ObjectNode cUpdate = c.deepCopy();
                    cUpdate.put("status", "clawed_back");
                    dao.put("/influencer-commissions/" + c.get("id").asText(), cUpdate);
                    clawedId = c.get("id").asText();
                }
            }
        }

        result.put("outcome", "refunded");
        result.put("attributionId", existing.get("id").asText());
        if (clawedId != null) {
            result.put("clawedBackCommissionId", clawedId);
        }
        return result;
    }

    // ---- helpers -------------------------------------------------------

    /**
     * THE COMMISSION BASE, written down once (roadmap OP-21).
     *
     * <p><b>A percentage commission is calculated on NET REVENUE AFTER DISCOUNT.</b> Net is
     * {@code saleAmount - discountAmount}, and it deliberately excludes tax and shipping — neither
     * is revenue the brand keeps, and paying commission on a shipping charge or on VAT means paying
     * a creator a share of money that was never the brand's.
     *
     * <p><b>This was gross until OP-21, and the row beside it disagreed.</b> The commission was
     * computed from {@code sale} while {@code netAmount} was stored as {@code sale - discount} on
     * the very same attribution — so a 20%-off order paid the creator 20% more than the ledger's own
     * net figure implied. Nobody had complained yet because there are no paying brands; the first
     * dispute would have been unanswerable, because the product asserted both numbers at once.
     *
     * <p>The same sentence now appears in three places, which is the whole point of OP-21: here, in
     * the coupon form's help text (`CouponsPage.jsx`), and in the campaign agreement copy. If one
     * changes, the other two are wrong and someone is owed money on a basis nobody agreed to.
     *
     * <p>A FIXED commission is a flat amount per attributed order and is unaffected by any of this.
     */
    private BigDecimal computeCommission(JsonNode coupon, BigDecimal sale, BigDecimal discount) {
        String type = text(coupon, "commissionType");
        if (type == null || !coupon.hasNonNull("commissionValue")) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = new BigDecimal(coupon.get("commissionValue").asText());
        if (type.equalsIgnoreCase("percent")) {
            // max(0, ...) because a discount larger than the sale is data, not an impossibility --
            // a fully comped order should pay no commission rather than a negative one.
            BigDecimal net = sale.subtract(discount == null ? BigDecimal.ZERO : discount).max(BigDecimal.ZERO);
            return net.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        if (type.equalsIgnoreCase("fixed")) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private String normalizePlatform(JsonNode coupon, String platform) {
        // Prefer the coupon's channel if it maps to a known platform; else the provider key.
        String channel = text(coupon, "channel");
        if (channel != null) {
            switch (channel) {
                case "instagram":
                case "tiktok":
                case "youtube":
                    return channel;
                default:
                    break;
            }
        }
        if (platform == null) {
            return "other";
        }
        switch (platform.toLowerCase(Locale.ROOT)) {
            case "shopify":
            case "amazon":
            case "woocommerce":
                return platform.toLowerCase(Locale.ROOT);
            default:
                return "other";
        }
    }

    private JsonNode resolveCouponByCode(UUID brandId, String code) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        JsonNode codes = dao.get("/influencer-campaign-codes", q);
        if (codes == null || !codes.isArray()) {
            return null;
        }
        for (JsonNode c : codes) {
            if (c.hasNonNull("code") && c.get("code").asText().equalsIgnoreCase(code)) {
                return c;
            }
        }
        return null;
    }

    private JsonNode findExistingAttribution(UUID brandId, String campaignCodeId, String orderId, String orderLineId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("campaignCodeId", campaignCodeId);
        JsonNode rows = dao.get("/influencer-sale-attributions", q);
        if (rows == null || !rows.isArray()) {
            return null;
        }
        for (JsonNode r : rows) {
            boolean orderMatch = r.hasNonNull("orderId") && r.get("orderId").asText().equals(orderId);
            boolean lineMatch = orderLineId == null
                    ? !r.hasNonNull("orderLineId")
                    : r.hasNonNull("orderLineId") && r.get("orderLineId").asText().equals(orderLineId);
            if (orderMatch && lineMatch) {
                return r;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
