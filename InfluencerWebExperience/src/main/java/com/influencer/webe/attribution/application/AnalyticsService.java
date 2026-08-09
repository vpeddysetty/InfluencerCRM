package com.influencer.webe.attribution.application;

import com.influencer.webe.shared.application.ResponseShapeService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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

    /** All-time revenue. Retained so existing callers keep compiling; delegates to the ranged form. */
    public JsonNode influencerRevenue(UUID brandId) {
        return influencerRevenue(brandId, null, null);
    }

    /**
     * Influencer revenue over a date window.
     *
     * <p>{@code from} and {@code to} are inclusive dates interpreted in UTC, and either may be
     * null for an open-ended range. The window is applied to {@code occurredAt} — when the sale
     * happened — rather than {@code createdAt}, which is when this system heard about it. A
     * Shopify backfill imported today would otherwise land every historical order in "last 7
     * days" and make the number wrong in the direction that flatters us.
     *
     * <p>Filtering happens here rather than in SQL because the rows are already loaded to compute
     * the rollups. That is fine at the volume this method is documented for; when the
     * {@code daily_attribution_stats} rollup is materialised, the range belongs in the query.
     */
    public JsonNode influencerRevenue(UUID brandId, LocalDate from, LocalDate to) {
        Instant fromInstant = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        // Exclusive upper bound built from the day *after* `to`, so an inclusive end date includes
        // everything that happened during that day rather than only the instant at midnight.
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        JsonNode attributions = listForUser(brandId, "/influencer-sale-attributions");
        JsonNode creators = listForUser(brandId, "/creators");
        JsonNode cards = listForUser(brandId, "/workflow-cards");

        Map<String, String> creatorNames = new LinkedHashMap<>();
        if (creators != null && creators.isArray()) {
            for (JsonNode c : creators) {
                creatorNames.put(c.get("id").asText(),
                        c.hasNonNull("name") ? c.get("name").asText()
                                : (c.hasNonNull("handle") ? c.get("handle").asText() : "Creator"));
            }
        }

        // Flat fees per creator from workflow cards (agreedFee), restricted to the same window as
        // the revenue they are being compared against.
        //
        // Without this the full fee is charged to EVERY window: a $1000 flat fee against $300 of
        // revenue in "last 7 days" reported ROI 0.29x while all-time reported 2.74x, from one
        // unchanged fee. The narrower the window the worse ROI looked — so a brand checking a
        // recent period saw a profitable creator as a money-loser, on the one number this product
        // exists to prove. The bug was latent before date filtering existed (one window, so the
        // fee was always correctly "in" it) and became reachable the moment ranges were added.
        //
        // `createdAt` is when the card was raised, which is the closest thing to "when this fee
        // was agreed" that the row carries — workflow_cards has no effective-date column. It is an
        // approximation, and the honest one: a fee agreed in January is not a cost incurred in
        // August. See the note on parseInstant for what happens to an undated card.
        Map<String, BigDecimal> flatFees = new LinkedHashMap<>();
        if (cards != null && cards.isArray()) {
            for (JsonNode card : cards) {
                if (card.hasNonNull("creatorId") && card.hasNonNull("agreedFee")
                        && cardWithinRange(card, fromInstant, toInstant)) {
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
                if (!withinRange(a, fromInstant, toInstant)) {
                    continue;
                }
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

    /**
     * Whether an attribution falls inside the requested window.
     *
     * <p>Keyed on {@code occurredAt} — when the sale happened — rather than {@code createdAt},
     * which is when this system heard about it. A Shopify backfill imported today would otherwise
     * land every historical order in "last 7 days".
     */
    private boolean withinRange(JsonNode attribution, Instant from, Instant to) {
        return withinRange(attribution, "occurredAt", from, to);
    }

    /**
     * Whether a workflow card's flat fee belongs in the requested window.
     *
     * <p>Keyed on {@code createdAt} — see the note at the flat-fee loop for why that is the best
     * available approximation of when a fee was agreed.
     */
    private boolean cardWithinRange(JsonNode card, Instant from, Instant to) {
        return withinRange(card, "createdAt", from, to);
    }

    /**
     * Shared window test.
     *
     * <p>A row whose timestamp is missing or unparseable is KEPT when the range is open on both
     * sides and DROPPED once a bound exists. Silently keeping undated rows inside a narrow window
     * would report revenue — or charge a fee — that provably did not occur in it; dropping them
     * when no filter is asked for would make the default view disagree with the database.
     */
    private boolean withinRange(JsonNode node, String field, Instant from, Instant to) {
        if (from == null && to == null) {
            return true;
        }
        if (node == null || !node.hasNonNull(field)) {
            return false;
        }
        Instant at;
        try {
            at = Instant.parse(node.get(field).asText());
        } catch (DateTimeParseException e) {
            return false;
        }
        if (from != null && at.isBefore(from)) {
            return false;
        }
        // `to` is already the exclusive start of the day after the inclusive end date.
        return to == null || at.isBefore(to);
    }

    private JsonNode listForUser(UUID brandId, String path) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
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
