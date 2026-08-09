package com.influencer.webe.creator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.creator.application.VettingService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Per-brand creator vetting (roadmap C2).
 *
 * <p>Note which permission guards what. Reading the queue and the audit trail needs
 * {@code creator:read}; changing a creator's vetting status needs {@code creator:write}.
 * Managing the RULES needs {@code creator:write} too — a rule set is a standing decision about
 * who gets rejected, so it is not a read-only convenience.
 */
@RestController
public class VettingController {

    private final VettingService vetting;
    private final RequestUserResolver requestUserResolver;

    public VettingController(VettingService vetting, RequestUserResolver requestUserResolver) {
        this.vetting = vetting;
        this.requestUserResolver = requestUserResolver;
    }

    // ---- rules (C2.2, C2.4) ---------------------------------------------

    @GetMapping("/api/vetting-rules")
    public JsonNode listRules(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return vetting.listRules(brandId);
    }

    @PostMapping("/api/vetting-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode saveRule(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        return vetting.saveRule(brandId, payload);
    }

    @DeleteMapping("/api/vetting-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        vetting.deleteRule(brandId, id);
    }

    /**
     * Dry-run a draft condition against this brand's existing creators (C2.4).
     *
     * <p>Deliberately a separate endpoint from saving. A rule that would silently reject 80% of
     * a brand's roster should be discoverable BEFORE it is switched on — which requires being
     * able to ask the question without committing to the answer.
     */
    @PostMapping("/api/vetting-rules/dry-run")
    public JsonNode dryRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        JsonNode condition = payload.get("condition");
        if (condition == null || !condition.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A condition of the form { attribute, operator, value } is required");
        }
        return vetting.dryRun(brandId, condition);
    }

    // ---- evaluation and decisions (C2.3, C2.6) --------------------------

    /** Re-run this brand's rules against one creator. Never approves. */
    @PostMapping("/api/creators/{id}/vetting/evaluate")
    public JsonNode evaluate(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        return vetting.evaluate(brandId, id, "manual_evaluate");
    }

    /**
     * A human sets a vetting status — the only path to {@code approved}.
     *
     * <p>Requires a resolvable user, so an approval always carries someone's name. That is what
     * a brand will be asked to justify, and it is why this is not the same endpoint as
     * evaluate.
     */
    @PutMapping("/api/creators/{id}/vetting")
    public JsonNode decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CREATOR_WRITE);
        return vetting.decide(context.brandId(), id, context.userId(),
                payload.path("status").asText(null), payload.path("reason").asText(null));
    }

    /** The review queue: everything a rule did not resolve (C2.6). */
    @GetMapping("/api/vetting/queue")
    public JsonNode queue(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return vetting.reviewQueue(brandId);
    }

    /** The audit trail for one creator (C2.5) — why they were rejected, and by what. */
    @GetMapping("/api/creators/{id}/vetting/history")
    public JsonNode history(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return vetting.history(brandId, id);
    }

    // ---- quality reports (C2.8) -----------------------------------------

    /** A brand disputes a creator's audience quality. */
    @PostMapping("/api/creators/{id}/quality-report")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode reportQuality(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @PathVariable UUID id,
                                  @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CREATOR_WRITE);
        return vetting.reportQuality(context.brandId(), id, context.userId(), payload);
    }
}
