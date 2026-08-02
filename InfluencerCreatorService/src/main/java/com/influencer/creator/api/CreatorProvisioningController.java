package com.influencer.creator.api;

import com.influencer.creator.application.CreatorProvisioningPort;
import com.influencer.creator.application.CreatorProvisioningService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * The wire form of {@link CreatorProvisioningPort}.
 *
 * <p>Spreadsheet import lives in the Campaign context but legitimately creates creators. While both
 * ran in one process that was an in-process port call; now it crosses a network boundary, and this
 * controller is the far side of it.
 *
 * <p>Deliberately separate from {@code CreatorController}: that is CRUD over a resource, this is a
 * <em>capability</em> another context is permitted to invoke. Keeping them apart means the published
 * contract can evolve without dragging the resource API with it — and makes it obvious in the route
 * table which endpoints are cross-context surface.
 */
@RestController
@RequestMapping("/creator-provisioning")
public class CreatorProvisioningController {

    private final CreatorProvisioningService provisioningService;

    public CreatorProvisioningController(CreatorProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    /** Finds a creator by (brand, platform, handle), or creates one. */
    @PostMapping("/creators")
    @ResponseStatus(HttpStatus.OK)
    public CreatorProvisioningPort.ProvisionResult findOrCreate(@RequestBody FindOrCreateRequest request) {
        return provisioningService.findOrCreateCreator(
                request.brandId(),
                request.importBatchId(),
                request.defaultSource(),
                request.attributes());
    }

    @GetMapping("/creators/{creatorId}/exists")
    public ExistsResponse exists(@PathVariable UUID creatorId) {
        return new ExistsResponse(provisioningService.creatorExists(creatorId));
    }

    @GetMapping("/creators/lookup")
    public LookupResponse lookup(@RequestParam UUID brandId,
                                 @RequestParam String platform,
                                 @RequestParam String handle) {
        return new LookupResponse(
                provisioningService.findCreatorId(brandId, platform, handle).orElse(null));
    }

    @PostMapping("/campaign-creators")
    @ResponseStatus(HttpStatus.OK)
    public CreatorProvisioningPort.ProvisionResult link(@RequestBody LinkRequest request) {
        return provisioningService.linkCreatorToCampaign(
                request.brandId(),
                request.importBatchId(),
                request.campaignId(),
                request.creatorId(),
                request.attributes());
    }

    @GetMapping("/campaign-creators/exists")
    public ExistsResponse isLinked(@RequestParam UUID campaignId, @RequestParam UUID creatorId) {
        return new ExistsResponse(provisioningService.isLinkedToCampaign(campaignId, creatorId));
    }

    public record FindOrCreateRequest(
            UUID brandId,
            UUID importBatchId,
            String defaultSource,
            Map<String, Object> attributes) {
    }

    public record LinkRequest(
            UUID brandId,
            UUID importBatchId,
            UUID campaignId,
            UUID creatorId,
            Map<String, Object> attributes) {
    }

    public record ExistsResponse(boolean exists) {
    }

    public record LookupResponse(UUID creatorId) {
    }
}
