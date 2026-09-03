package com.influencer.webe.payout.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.payout.CreatorPayoutOnboardingService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Payout onboarding for one creator (roadmap PR-47).
 *
 * <p><b>CREATOR_WRITE, not a finance permission.</b> Starting onboarding sends a creator to Stripe;
 * it moves no money and reads no ledger. The person who invites creators is the one who should be
 * able to do it, and requiring FINANCE would put a step in the invitation flow behind a role the
 * marketer doing the inviting does not hold — see §5, where FINANCE builds on READ_ONLY rather than
 * on MARKETER and so cannot edit a creator at all.
 */
@RestController
public class CreatorPayoutOnboardingController {

    private final CreatorPayoutOnboardingService onboarding;
    private final RequestUserResolver requestUserResolver;

    public CreatorPayoutOnboardingController(CreatorPayoutOnboardingService onboarding,
                                             RequestUserResolver requestUserResolver) {
        this.onboarding = onboarding;
        this.requestUserResolver = requestUserResolver;
    }

    /**
     * Start or resume onboarding, returning a single-use URL.
     *
     * <p>POST rather than GET because it creates a Stripe account on the first call — and because a
     * GET would be prefetched by a browser, silently creating one for every creator whose row was
     * hovered over.
     */
    @PostMapping("/api/creators/{creatorId}/payout-onboarding")
    public JsonNode start(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @PathVariable UUID creatorId) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        return onboarding.start(brandId, creatorId);
    }

    /** Re-read whether this creator can be paid, and store the answer with the time it was read. */
    @PostMapping("/api/creators/{creatorId}/payout-onboarding/refresh")
    public JsonNode refresh(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable UUID creatorId) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        return onboarding.refreshStatus(brandId, creatorId);
    }
}
