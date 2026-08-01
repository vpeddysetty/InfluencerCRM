package com.influencer.webe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.client.DaoGatewayClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Influencer-revenue analytics (Phase 4). Aggregates attributions + commissions
 * into KPI tiles, a per-influencer leaderboard, and a per-channel breakdown.
 *
 * For the current dataset size this computes live from the attribution rows; the
 * {@code daily_attribution_stats} rollup table exists for when volume grows
 * (materialize via a scheduled job later). ROI folds in flat fees from workflow
 * cards (agreedFee) plus accrued commission as total influencer cost.
 */
@Service
public class AnalyticsService {
    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public AnalyticsService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    public JsonNode influencerRevenue(UUID userId) {
        JsonNode attributions = listForUser(userId, "/influencer-sale-attributions");
        JsonNode creators = listForUser(userId, "/creators");
        JsonNode cards = listForUser(userId, "/workflow-cards");

        Map<String, String> creatorNames = new LinkedHashMap<>();
        if (creators != null && creators.isArray()) {
            for (JsonNode c : creators) {
                creatorNames.put(c.get("id").asText(),
                        c.hasNonNull("name") ? c.get("name").asText()
                                : (c.hasNonNull("handle") ? c.get("handle").asText() : "Creator"));
            }
        }

        // Flat fees per creator from workflow cards (agreedFee).
        Map<String, BigDecimal> flatFees = new LinkedHashMap<>();
        if (cards != null && cards.isArray()) {
            for (JsonNode card : cards) {
                if (card.hasNonNull("creatorId") && card.hasNonNull("agreedFee")) {
                    String cid = card.get("creatorId").asText();
                    flatFees.merge(cid, decimal(card, "agreedFee"), BigDecimal::add);
                }
            }
        }

        // Accumulators.
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalRefunded = BigDecimal.ZERO;
        int totalOrders = 0;
        int refundedOrders = 0;

        Map<String, Agg> byCreator = new LinkedHashMap<>();
        Map<String, Agg> byChannel = new LinkedHashMap<>();

        if (attributions != null && attributions.isArray()) {
            for (JsonNode a : attributions) {
                String status = a.hasNonNull("status") ? a.get("status").asText() : "attributed";
                BigDecimal sale = decimal(a, "saleAmount");
                BigDecimal discount = decimal(a, "discountAmount");
                BigDecimal commission = decimal(a, "commissionAmount");
                String creatorId = a.hasNonNull("creatorId") ? a.get("creatorId").asText() : "unknown";
                String channel = a.hasNonNull("platform") ? a.get("platform").asText() : "other";

                boolean refunded = status.equalsIgnoreCase("refunded") || status.equalsIgnoreCase("cancelled");

                Agg cAgg = byCreator.computeIfAbsent(creatorId, k -> new Agg());
                Agg chAgg = byChannel.computeIfAbsent(channel, k -> new Agg());

                if (refunded) {
                    refundedOrders++;
                    totalRefunded = totalRefunded.add(sale);
                    cAgg.refunds = cAgg.refunds.add(sale);
                    chAgg.refunds = chAgg.refunds.add(sale);
                } else {
                    totalOrders++;
                    totalRevenue = totalRevenue.add(sale);
                    totalDiscount = totalDiscount.add(discount);
                    totalCommission = totalCommission.add(commission);
                    cAgg.orders++;
                    cAgg.revenue = cAgg.revenue.add(sale);
                    cAgg.commission = cAgg.commission.add(commission);
                    chAgg.orders++;
                    chAgg.revenue = chAgg.revenue.add(sale);
                    chAgg.commission = chAgg.commission.add(commission);
                }
            }
        }

        ObjectNode out = shape.objectMapper().createObjectNode();

        // KPI tiles.
        ObjectNode kpis = out.putObject("kpis");
        kpis.put("revenue", totalRevenue.toPlainString());
        kpis.put("orders", totalOrders);
        kpis.put("avgOrderValue", avg(totalRevenue, totalOrders));
        kpis.put("commission", totalCommission.toPlainString());
        kpis.put("refundedRevenue", totalRefunded.toPlainString());
        kpis.put("refundedOrders", refundedOrders);
        BigDecimal totalCost = totalCommission;
        for (BigDecimal fee : flatFees.values()) {
            totalCost = totalCost.add(fee);
        }
        kpis.put("totalInfluencerCost", totalCost.toPlainString());
        kpis.put("roi", roi(totalRevenue, totalCost));

        // Leaderboard.
        ArrayNode leaderboard = out.putArray("leaderboard");
        for (Map.Entry<String, Agg> e : byCreator.entrySet()) {
            Agg agg = e.getValue();
            BigDecimal fee = flatFees.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal cost = agg.commission.add(fee);
            ObjectNode row = leaderboard.addObject();
            row.put("creatorId", e.getKey());
            row.put("creatorName", creatorNames.getOrDefault(e.getKey(), "Creator"));
            row.put("orders", agg.orders);
            row.put("revenue", agg.revenue.toPlainString());
            row.put("avgOrderValue", avg(agg.revenue, agg.orders));
            row.put("commission", agg.commission.toPlainString());
            row.put("flatFee", fee.toPlainString());
            row.put("cost", cost.toPlainString());
            row.put("roi", roi(agg.revenue, cost));
            row.put("refunds", agg.refunds.toPlainString());
        }

        // Channel breakdown.
        ArrayNode channels = out.putArray("channels");
        for (Map.Entry<String, Agg> e : byChannel.entrySet()) {
            Agg agg = e.getValue();
            ObjectNode row = channels.addObject();
            row.put("channel", e.getKey());
            row.put("orders", agg.orders);
            row.put("revenue", agg.revenue.toPlainString());
            row.put("commission", agg.commission.toPlainString());
        }

        return out;
    }

    private static class Agg {
        int orders = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
    }

    private JsonNode listForUser(UUID userId, String path) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("userId", userId.toString());
        return dao.get(path, q);
    }

    private BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.get(field).asText());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String avg(BigDecimal total, int count) {
        if (count == 0) {
            return "0.00";
        }
        return total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private String roi(BigDecimal revenue, BigDecimal cost) {
        if (cost.compareTo(BigDecimal.ZERO) == 0) {
            return revenue.compareTo(BigDecimal.ZERO) > 0 ? "∞" : "0.00";
        }
        return revenue.divide(cost, 2, RoundingMode.HALF_UP).toPlainString();
    }
}
