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
        BigDecimal commission = computeCommission(coupon, sale);
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

        JsonNode savedAttribution = dao.post("/influencer-sale-attributions", attribution);

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

    private BigDecimal computeCommission(JsonNode coupon, BigDecimal sale) {
        String type = text(coupon, "commissionType");
        if (type == null || !coupon.hasNonNull("commissionValue")) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = new BigDecimal(coupon.get("commissionValue").asText());
        if (type.equalsIgnoreCase("percent")) {
            return sale.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
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
