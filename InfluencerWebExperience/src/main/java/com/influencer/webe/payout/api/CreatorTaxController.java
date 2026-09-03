package com.influencer.webe.payout.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.payout.TaxThresholdService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Where a creator stands against the 1099-NEC threshold, and recording their form (roadmap PR-49).
 *
 * <p><b>Readable before it is needed.</b> The whole point of the row is that a form is chased while
 * a campaign is running rather than on the day someone is owed money, and that requires the brand to
 * be able to see the number ahead of time — a gate that only speaks at payout time is the ordering
 * the roadmap says is the worst one.
 *
 * <p><b>PAYOUT_READ to see, CREATOR_WRITE to record.</b> The total paid is ledger information and
 * belongs behind the finance permission. Recording that a form arrived is creator administration,
 * done by whoever chased it — and §5 is explicit that FINANCE builds on READ_ONLY and cannot edit a
 * creator at all, so requiring a finance role for the second would put it beyond the person doing it.
 */
@RestController
public class CreatorTaxController {

    private final TaxThresholdService taxThreshold;
    private final RequestUserResolver requestUserResolver;

    public CreatorTaxController(TaxThresholdService taxThreshold, RequestUserResolver requestUserResolver) {
        this.taxThreshold = taxThreshold;
        this.requestUserResolver = requestUserResolver;
    }

    /** What this brand has paid this creator this calendar year, and whether a form is needed. */
    @GetMapping("/api/creators/{creatorId}/tax-status")
    public JsonNode status(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID creatorId) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.PAYOUT_READ);
        return taxThreshold.assess(brandId, creatorId, null);
    }

    /**
     * Record that the brand has the form.
     *
     * <p>Takes a kind, not a file. See {@link TaxThresholdService#recordForm} for why this product
     * deliberately does not store the document.
     */
    @PostMapping("/api/creators/{creatorId}/tax-form")
    public JsonNode recordForm(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable UUID creatorId,
                               @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        return taxThreshold.recordForm(brandId, creatorId, payload.path("kind").asText(null));
    }
}
