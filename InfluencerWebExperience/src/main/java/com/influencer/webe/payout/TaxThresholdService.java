package com.influencer.webe.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a creator may be paid without a tax form on file (roadmap PR-49).
 *
 * <p><b>The sequencing is the whole point of this row.</b> Chasing paperwork while someone is asking
 * where their money is is the worst possible order — so this is checked at ONBOARDING and before a
 * payout is approved, never at the moment of sending. `PR-47` starts the Connect clock at
 * invitation for the same reason.
 *
 * <p><b>It does not generate 1099s.</b> Stripe Connect has tax reporting as a feature; enabling it
 * is a setting. Building form generation would mean owning IRS filing deadlines, correction
 * workflows and per-state variation for a product with zero subscribers, to reproduce something a
 * platform already does.
 *
 * <p><b>Per creator PER BRAND, per CALENDAR year.</b> The obligation follows the payer, and in this
 * product each brand is its own payer rather than the platform being an aggregator — two brands
 * paying the same person $400 each have each paid under the threshold. Calendar rather than rolling,
 * because the IRS figure is a calendar-year one and a rolling window would withhold money from
 * somebody under the actual limit.
 *
 * <p><b>An unreadable total does NOT block a payout.</b> Failing closed here would stop a brand
 * paying a creator because the DAO hiccuped, which is a worse outcome than a late form: the
 * threshold is a reporting obligation with an annual deadline, not a payment authorisation. The
 * failure is logged and the payout proceeds.
 */
@Service
public class TaxThresholdService {

    private static final Logger log = LoggerFactory.getLogger(TaxThresholdService.class);

    /**
     * The 1099-NEC reporting threshold, in USD.
     *
     * <p>A constant rather than configuration: it is set by the IRS, not by this product, and making
     * it configurable would invite somebody to raise it to avoid the paperwork — which does not
     * change the obligation, only whether the platform noticed.
     */
    static final BigDecimal THRESHOLD_USD = new BigDecimal("600.00");

    /**
     * Ask before the threshold, not at it.
     *
     * <p>A creator asked for a form at $600.01 has already been paid $600, and the payout that
     * crossed it is the one that had to wait. Warning at 80% means the form is usually on file
     * before it is needed, which is the difference between a prompt and a blocker.
     */
    static final BigDecimal WARN_AT = new BigDecimal("480.00");

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public TaxThresholdService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    /**
     * Where this creator stands against the threshold for this brand, this year.
     *
     * @param additionalAmount an about-to-be-approved payout to include in the projection, or null.
     *                         The question a brand actually has is not "have they crossed it" but
     *                         "will this payment cross it", and answering the first while being
     *                         asked the second is how the form gets chased a day late.
     */
    public JsonNode assess(UUID brandId, UUID creatorId, BigDecimal additionalAmount) {
        int year = Instant.now().atZone(ZoneOffset.UTC).getYear();
        BigDecimal paid = paidThisYear(brandId, creatorId, year);

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("year", year);
        out.put("threshold", THRESHOLD_USD);

        if (paid == null) {
            // UNKNOWN, and it must not read as zero: a brand shown $0.00 would conclude there is
            // nothing to chase, which is the opposite of what an unreadable total means.
            out.put("known", false);
            out.put("formRequired", false);
            out.put("detail", "Could not read what has been paid this year.");
            return out;
        }

        BigDecimal projected = additionalAmount == null ? paid : paid.add(additionalAmount);
        out.put("known", true);
        out.put("paidThisYear", paid);
        out.put("projected", projected);
        // `>= 0` means projected is at or above the threshold. The IRS figure is "$600 or more",
        // not "over $600" -- an off-by-one here is a missed filing obligation.
        out.put("formRequired", projected.compareTo(THRESHOLD_USD) >= 0);
        out.put("approaching", projected.compareTo(WARN_AT) >= 0 && projected.compareTo(THRESHOLD_USD) < 0);
        return out;
    }

    /**
     * Whether this creator is cleared to be paid an amount, given the form state on their record.
     *
     * <p><b>Two rails, two authorities.</b> Where Stripe Connect is in use, `payouts_enabled` is the
     * authoritative answer and Stripe has collected the form itself — checking our own column there
     * would be second-guessing the platform that owns the fact. On the manual rail there is no
     * Stripe account to ask, and the brand's own record of having received a form is the only
     * evidence there is.
     */
    public JsonNode clearance(UUID brandId, UUID creatorId, BigDecimal amount) {
        JsonNode creator = read("/creators/" + creatorId, new LinkedHashMap<>());
        ObjectNode out = (ObjectNode) assess(brandId, creatorId, amount);

        if (creator == null) {
            out.put("clear", true);
            out.put("detail", "Could not read the creator; not blocking the payout.");
            return out;
        }

        boolean formNeeded = out.path("formRequired").asBoolean(false);
        if (!formNeeded) {
            out.put("clear", true);
            return out;
        }

        // Connect in use: Stripe collected the form and payouts_enabled reflects it.
        if (creator.hasNonNull("stripeAccountId")) {
            boolean enabled = creator.path("payoutsEnabled").asBoolean(false);
            out.put("clear", enabled);
            if (!enabled) {
                out.put("detail", "Stripe has not enabled payouts for this creator yet — usually the tax form or identity check.");
            }
            return out;
        }

        // Manual rail: the brand's own assertion is all there is.
        boolean onFile = creator.hasNonNull("taxFormOnFileAt");
        out.put("clear", onFile);
        if (!onFile) {
            out.put("detail", "A W-9 or W-8BEN is needed before paying this creator more this year.");
        }
        return out;
    }

    /**
     * Record that a brand has received a tax form from this creator.
     *
     * <p><b>A brand assertion, not a document store.</b> This deliberately does not accept an upload:
     * holding W-9s would make this application a custodian of signed documents carrying SSNs, with
     * the retention, encryption and breach-notification duties that implies — for a product whose
     * whole strategy here is to let Stripe own that. What is recorded is that the brand has the form,
     * which is what determines whether they may pay.
     *
     * <p>Refused when Connect is in use: there, Stripe collected the form and {@code payouts_enabled}
     * is the answer. Letting a brand tick a box that overrides Stripe would let them pay someone
     * Stripe is holding, which is not a decision this product gets to make.
     */
    public JsonNode recordForm(UUID brandId, UUID creatorId, String kind) {
        JsonNode creator = read("/creators/" + creatorId, new LinkedHashMap<>());
        if (creator == null || !brandId.toString().equals(creator.path("brandId").asText(null))) {
            // Not "forbidden": saying which of the two it was confirms the id exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator not found");
        }
        if (creator.hasNonNull("stripeAccountId")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This creator is onboarded with Stripe, which collects the tax form itself.");
        }

        String normalised = kind == null ? "" : kind.trim().toUpperCase();
        if (!normalised.equals("W-9") && !normalised.equals("W-8BEN")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tax form kind must be W-9 or W-8BEN.");
        }

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("taxFormOnFileAt", Instant.now().toString());
        body.put("taxFormKind", normalised);
        dao.patch("/creators/" + creatorId + "/payout-account", body);

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("taxFormKind", normalised);
        out.put("clear", true);
        return out;
    }

    private BigDecimal paidThisYear(UUID brandId, UUID creatorId, int year) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("creatorId", creatorId.toString());
        query.put("brandId", brandId.toString());
        query.put("year", String.valueOf(year));
        JsonNode result = read("/influencer-payouts/paid-total", query);
        if (result == null || !result.hasNonNull("paidTotal")) {
            return null;
        }
        return new BigDecimal(result.get("paidTotal").asText());
    }

    private JsonNode read(String path, Map<String, String> query) {
        try {
            return dao.get(path, query);
        } catch (RuntimeException e) {
            // Logged, not thrown. Failing closed would stop a brand paying a creator because the
            // DAO hiccuped, and the threshold is a reporting obligation with an annual deadline
            // rather than a payment authorisation.
            log.info("Tax threshold could not read {}: {}", path, e.toString());
            return null;
        }
    }
}
