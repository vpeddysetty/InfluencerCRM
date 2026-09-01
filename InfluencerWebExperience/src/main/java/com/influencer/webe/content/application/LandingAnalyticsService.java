package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the landing-page view log into something a brand can read (roadmap PR-57).
 *
 * <p><b>Why this exists.</b> {@code landing_page_views} has been recording a row per public render
 * since the feature shipped, and {@code GET /api/landing-page-views} has been serving them — to
 * nobody. No {@code .js} or {@code .jsx} file in the repo called that endpoint, so a brand who
 * published a page had no way to learn whether anyone arrived, and the {@code performance_tracking}
 * stage was reachable with nothing behind it.
 *
 * <p><b>Why a new endpoint rather than pointing the UI at the old one.</b> The existing route
 * returns every raw row, unpaginated and unfiltered by date: a page doing well would eventually
 * answer with tens of thousands of records, and the browser would aggregate them. The counting
 * belongs here, next to the coupon lookup it needs, and what crosses the wire should be the
 * summary. The raw route is left alone — it is somebody's debugging tool and costs nothing.
 *
 * <p><b>What is deliberately NOT added.</b> No new capture, no new columns. The table holds a
 * coupon id, a referrer, a user agent and a timestamp, and that restraint is what lets a public
 * page stay anonymous with no consent gate — the same reasoning that rejected fingerprinting in
 * PR-39. Counting what is already counted needs no new promises to a visitor. In particular there
 * is no unique-visitor figure here: deduplicating would require identifying people, and a
 * deliberately anonymous log cannot answer that question honestly. Every number below is a VIEW
 * count and is named so.
 */
@Service
public class LandingAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(LandingAnalyticsService.class);

    /** Views older than this are not fetched. A brand asking "how is it doing" means recently. */
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public LandingAnalyticsService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    /**
     * View counts for one campaign's landing page, broken down by creator and by day.
     *
     * <p>Scoped by {@code brandId} on every read: the coupon lookup is filtered by brand, and a
     * view whose coupon does not belong to this brand is dropped rather than counted. Tenancy here
     * is not incidental — the view log is one shared table across every brand.
     */
    public JsonNode forCampaign(UUID brandId, UUID campaignId, Integer days) {
        int window = clampDays(days);
        Instant since = Instant.now().minus(window, ChronoUnit.DAYS);

        // The coupons ARE the join key: a view records a campaign_code_id, never a template id, so
        // "views for this campaign" is "views for any coupon on this campaign". Resolved first so a
        // view belonging to another campaign is never counted.
        Map<String, JsonNode> couponsById = couponsForCampaign(brandId, campaignId);

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("campaignId", campaignId.toString());
        out.put("windowDays", window);
        out.put("since", since.toString());

        if (couponsById.isEmpty()) {
            // Not an error. A page with no coupon still renders and is still visited — it simply
            // has no per-creator breakdown, and PR-39's publish-readiness advisory already warns
            // about the attribution this costs. An empty result here says "nothing to show", which
            // is a different claim from "no views".
            out.put("totalViews", 0);
            out.set("byCreator", out.arrayNode());
            out.set("byDay", out.arrayNode());
            out.put("note", "This page has no creator codes, so views cannot be attributed.");
            return out;
        }

        List<JsonNode> views = viewsFor(brandId, couponsById.keySet(), since);

        Map<String, long[]> perCreator = new LinkedHashMap<>();
        Map<String, long[]> perDay = new LinkedHashMap<>();
        long total = 0;

        for (JsonNode view : views) {
            JsonNode coupon = couponsById.get(text(view, "campaignCodeId"));
            if (coupon == null) {
                continue;   // another campaign's coupon, or one this brand cannot see
            }
            total++;

            String creatorId = text(coupon, "creatorId");
            perCreator.computeIfAbsent(creatorId == null ? "unknown" : creatorId, k -> new long[1])[0]++;

            String day = dayOf(view);
            if (day != null) {
                perDay.computeIfAbsent(day, k -> new long[1])[0]++;
            }
        }

        out.put("totalViews", total);
        out.set("byCreator", byCreator(perCreator, couponsById));
        out.set("byDay", byDay(perDay));
        return out;
    }

    // ---- reads ---------------------------------------------------------

    private Map<String, JsonNode> couponsForCampaign(UUID brandId, UUID campaignId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", brandId.toString());
        query.put("campaignId", campaignId.toString());
        JsonNode coupons = read("/influencer-campaign-codes", query);

        Map<String, JsonNode> byId = new LinkedHashMap<>();
        if (coupons != null && coupons.isArray()) {
            for (JsonNode coupon : coupons) {
                String id = text(coupon, "id");
                if (id != null) {
                    byId.put(id, coupon);
                }
            }
        }
        return byId;
    }

    /**
     * The brand's views since {@code since}, restricted to the coupons that matter.
     *
     * <p>Filtered in this process rather than by the DAO because the view route takes one coupon id
     * or a brand id and no date range. Fetching the brand's views once and filtering is one call;
     * asking per coupon would be one call per creator on a campaign.
     */
    private List<JsonNode> viewsFor(UUID brandId, java.util.Set<String> couponIds, Instant since) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", brandId.toString());
        JsonNode views = read("/landing-page-views", query);

        List<JsonNode> kept = new ArrayList<>();
        if (views == null || !views.isArray()) {
            return kept;
        }
        for (JsonNode view : views) {
            if (!couponIds.contains(text(view, "campaignCodeId"))) {
                continue;
            }
            Instant at = occurredAt(view);
            if (at != null && at.isBefore(since)) {
                continue;
            }
            kept.add(view);
        }
        return kept;
    }

    // ---- shaping -------------------------------------------------------

    private ArrayNode byCreator(Map<String, long[]> counts, Map<String, JsonNode> couponsById) {
        // One coupon per creator per campaign in practice, but the code is what a brand recognises
        // on the page, so it is carried through alongside the id.
        Map<String, String> codeByCreator = new LinkedHashMap<>();
        for (JsonNode coupon : couponsById.values()) {
            String creatorId = text(coupon, "creatorId");
            if (creatorId != null) {
                codeByCreator.putIfAbsent(creatorId, text(coupon, "code"));
            }
        }

        List<Map.Entry<String, long[]>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());

        ArrayNode rows = shape.objectMapper().createArrayNode();
        for (Map.Entry<String, long[]> entry : ordered) {
            ObjectNode row = rows.addObject();
            row.put("creatorId", entry.getKey());
            row.put("code", codeByCreator.get(entry.getKey()));
            row.put("views", entry.getValue()[0]);
        }
        return rows;
    }

    /** Ascending by date, so a chart can render it without sorting. */
    private ArrayNode byDay(Map<String, long[]> counts) {
        List<String> days = new ArrayList<>(counts.keySet());
        days.sort(Comparator.naturalOrder());

        ArrayNode rows = shape.objectMapper().createArrayNode();
        for (String day : days) {
            ObjectNode row = rows.addObject();
            row.put("date", day);
            row.put("views", counts.get(day)[0]);
        }
        return rows;
    }

    // ---- helpers -------------------------------------------------------

    private int clampDays(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    /**
     * The UTC date a view happened on.
     *
     * <p>UTC rather than the brand's local zone, and the field name says {@code date} rather than
     * anything implying local time. A brand in Los Angeles reading a UTC day boundary sees an
     * evening's traffic land on tomorrow — worth fixing when there is a timezone on the account to
     * fix it with, and worth NOT pretending to have solved before then.
     */
    private String dayOf(JsonNode view) {
        Instant at = occurredAt(view);
        return at == null ? null : at.toString().substring(0, 10);
    }

    private Instant occurredAt(JsonNode view) {
        String raw = text(view, "occurredAt");
        if (raw == null) {
            raw = text(view, "createdAt");
        }
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }

    /** Best-effort, like every other read in this context: analytics must not break a page. */
    private JsonNode read(String path, Map<String, String> query) {
        try {
            return dao.get(path, query);
        } catch (RuntimeException e) {
            log.info("Landing analytics could not read {}: {}", path, e.toString());
            return null;
        }
    }
}
