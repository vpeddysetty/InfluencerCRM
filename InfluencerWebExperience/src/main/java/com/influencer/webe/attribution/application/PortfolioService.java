package com.influencer.webe.attribution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.application.BrandAccessPort;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One view across every client an agency manages (roadmap PR-64).
 *
 * <p><b>The gap this closes.</b> The product is architecturally multi-brand and experientially
 * single-brand: an account can hold many brands, but every read endpoint is
 * {@code requirePermissionForBrand}, so every screen answers "how is THIS brand doing?" and nothing
 * answers "how is my agency doing?". An agency with eight clients sees eight workspaces and switches
 * between them one at a time.
 *
 * <p><b>Scope comes from {@link BrandAccessPort#findAccessibleBrandsForPort}, never from a second rule.</b>
 * §5 records what happens when the definitions of who-sees-what diverge: a user logs in seeing one
 * set of brands and is refused on another. This asks the same question the brand switcher asks, so
 * a portfolio can never show a brand its owner cannot open — and a revoked membership takes effect
 * here on the next request, because that client deliberately does not cache.
 *
 * <p><b>Per-brand failure does not fail the page.</b> One unreachable brand yields a row marked
 * {@code unavailable} rather than a 500 across the whole portfolio, and rather than a zero. A zero
 * is a claim — it says this client sold nothing — and an agency acting on it would draw exactly the
 * wrong conclusion. This is the same rule {@code PR-49} applies to an unreadable payout total and
 * {@code PR-47} to an unknown Connect status: absent is not zero.
 *
 * <p><b>Reads the transactional store, not a CQRS read model</b> — §12.2b records the decision and
 * when to revisit it. `OP-39` narrowed the underlying fetch to the requested window, which is what
 * makes fanning out across brands affordable at all: before it, each brand pulled every row it had
 * ever had.
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final BrandAccessPort brandAccess;
    private final AnalyticsService analytics;
    private final ResponseShapeService shape;

    public PortfolioService(BrandAccessPort brandAccess, AnalyticsService analytics,
                            ResponseShapeService shape) {
        this.brandAccess = brandAccess;
        this.analytics = analytics;
        this.shape = shape;
    }

    /**
     * Every reachable brand's headline figures for one window, plus the totals across them.
     *
     * @param userId the CALLER, from the verified token — not a parameter a client may choose.
     */
    public JsonNode portfolio(UUID userId, LocalDate from, LocalDate to) {
        List<BrandAccessPort.BrandAccess> brands = brandAccess.findAccessibleBrandsForPort(userId);

        ArrayNode rows = shape.objectMapper().createArrayNode();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        int totalOrders = 0;
        int unavailable = 0;

        for (BrandAccessPort.BrandAccess brand : brands) {
            ObjectNode row = rows.addObject();
            row.put("brandId", brand.brandId().toString());
            row.put("brandName", brand.brandName());
            row.put("role", brand.role().name());

            JsonNode result;
            try {
                result = analytics.influencerRevenue(brand.brandId(), from, to);
            } catch (RuntimeException e) {
                // Logged and marked, not thrown. One brand's outage must not blank the other seven.
                log.info("Portfolio: no figures for brand {}: {}", brand.brandId(), e.toString());
                result = null;
            }

            JsonNode kpis = result == null ? null : result.get("kpis");
            if (kpis == null) {
                // UNAVAILABLE, and deliberately not zero. "We could not ask" and "this client sold
                // nothing" are different facts, and a zero is the one an agency would act on.
                row.put("available", false);
                unavailable++;
                continue;
            }

            row.put("available", true);
            BigDecimal revenue = decimal(kpis, "revenue");
            BigDecimal commission = decimal(kpis, "commission");
            BigDecimal cost = decimal(kpis, "totalInfluencerCost");
            int orders = kpis.path("orders").asInt(0);

            row.put("revenue", revenue.toPlainString());
            row.put("orders", orders);
            row.put("commission", commission.toPlainString());
            row.put("influencerCost", cost.toPlainString());
            // RECOMPUTED, not copied. AnalyticsService answers "0.00" or "∞" when nothing was
            // spent, which is right for a single-brand dashboard where the reader is looking at
            // one campaign's economics. On a portfolio it is wrong twice over: the row would read
            // 0.00x for a client that simply has no recorded cost, while the totals row -- which
            // uses this class's rule -- shows a dash for the same situation. Two ROI figures
            // disagreeing on one screen is worse than either convention alone, so the per-brand
            // figure follows the same rule as the total it sits under.
            String brandRoi = roi(revenue, cost);
            if (brandRoi == null) {
                row.putNull("roi");
            } else {
                row.put("roi", brandRoi);
            }
            // Creator count comes from the per-creator breakdown the same call already computed,
            // rather than a second round trip for a number that is sitting right there.
            row.put("creators", result.path("byCreator").isArray() ? result.get("byCreator").size() : 0);

            totalRevenue = totalRevenue.add(revenue);
            totalCommission = totalCommission.add(commission);
            totalCost = totalCost.add(cost);
            totalOrders += orders;
        }

        ObjectNode out = shape.objectMapper().createObjectNode();
        ObjectNode totals = out.putObject("totals");
        totals.put("brands", brands.size());
        // Said out loud so a total can be read honestly. Summing seven brands and presenting it as
        // eight brands' worth is the quiet version of the zero this class refuses to write.
        totals.put("brandsUnavailable", unavailable);
        totals.put("revenue", totalRevenue.toPlainString());
        totals.put("orders", totalOrders);
        totals.put("commission", totalCommission.toPlainString());
        totals.put("influencerCost", totalCost.toPlainString());
        totals.put("roi", roi(totalRevenue, totalCost));
        out.set("brands", rows);
        return out;
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

    /**
     * Return on influencer spend, as a multiple.
     *
     * <p>Null rather than infinity when nothing was spent: a brand with revenue and no recorded cost
     * has an ROI nobody can compute, and printing "∞" or a large number would read as a result. The
     * per-brand figures come from {@code AnalyticsService}; this recomputes the total from the sums
     * rather than averaging the ratios, because the mean of ratios is not the ratio of the sums and
     * an agency comparing a portfolio figure to its parts would find they disagreed.
     */
    private String roi(BigDecimal revenue, BigDecimal cost) {
        if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return revenue.divide(cost, 2, RoundingMode.HALF_UP).toPlainString();
    }
}
