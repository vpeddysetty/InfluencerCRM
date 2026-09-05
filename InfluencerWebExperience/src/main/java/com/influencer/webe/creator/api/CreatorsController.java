package com.influencer.webe.creator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.application.EntitlementService;
import com.influencer.webe.identity.application.PlanPolicy;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/creators")
public class CreatorsController {
    private final DaoGatewayClient daoGatewayClient;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService responseShapeService;
    private final EntitlementService entitlements;

    public CreatorsController(DaoGatewayClient daoGatewayClient,
                              RequestUserResolver requestUserResolver,
                              ResponseShapeService responseShapeService,
                              EntitlementService entitlements) {
        this.daoGatewayClient = daoGatewayClient;
        this.requestUserResolver = requestUserResolver;
        this.responseShapeService = responseShapeService;
        this.entitlements = entitlements;
    }

    /**
     * The roster, optionally searched and filtered (roadmap PR-67).
     *
     * <p><b>The brand scope is never taken from the request.</b> {@code brandId} is accepted for
     * shape compatibility and then discarded — {@code requirePermissionForBrand} resolves the real
     * one from the verified token, as everywhere else here. A filter parameter is a narrowing
     * within that scope and can never widen it.
     *
     * <p>Filters are forwarded verbatim rather than applied here: the DAO can answer them with an
     * indexed query, and filtering a fully-fetched list in the BFF is the shape `OP-39` had to
     * undo on the analytics path.
     */
    @GetMapping
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID brandId,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size,
                         @RequestParam(required = false) String q,
                         @RequestParam(required = false) String niche,
                         @RequestParam(required = false) String platform,
                         @RequestParam(required = false) String vettingStatus,
                         @RequestParam(required = false) Integer minFollowers,
                         @RequestParam(required = false) Integer maxFollowers) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolvedBrandId.toString());
        query.put("q", q);
        query.put("niche", niche);
        query.put("platform", platform);
        query.put("vettingStatus", vettingStatus);
        query.put("minFollowers", minFollowers == null ? null : minFollowers.toString());
        query.put("maxFollowers", maxFollowers == null ? null : maxFollowers.toString());
        return responseShapeService.creatorsList(daoGatewayClient.get("/creators", query), page, size);
    }

    @GetMapping("/{id}")
    public JsonNode findById(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable UUID id) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return responseShapeService.creator(requireBrandOwned(id, resolvedBrandId));
    }

    @PostMapping
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CREATOR_WRITE);
        // M2.3. After the permission check, not before: "you may not do this" and "your plan does
        // not include this" are different answers, and a caller without the permission should get
        // the authorization one rather than being told to upgrade.
        entitlements.requireCapacity(context.accountId(), PlanPolicy.Resource.CREATOR);
        payload.put("brandId", context.brandId().toString());
        // Who created the row is taken from the verified token, never from the request body:
        // an audit trail a caller can set is not an audit trail.
        payload.put("createdByUserId", context.userId().toString());
        return responseShapeService.creator(daoGatewayClient.post("/creators", payload));
    }

    @PutMapping("/{id}")
    public JsonNode update(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        payload.put("brandId", resolvedBrandId.toString());
        return responseShapeService.creator(daoGatewayClient.put("/creators/" + id, payload));
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable UUID id) {
        UUID resolvedBrandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_DELETE);
        requireBrandOwned(id, resolvedBrandId);
        daoGatewayClient.delete("/creators/" + id);
    }

    /**
     * Fetches a creator and asserts it belongs to the caller's brand.
     *
     * <p>The by-id routes previously trusted the path variable alone, so knowing a UUID was enough
     * to read — or delete — another tenant's creator. The list route was never affected because it
     * filters by the resolved brand; a lookup by id has no such filter and must check explicitly.
     *
     * <p>A record owned by another brand is reported as 404, not 403: confirming that an id exists
     * is itself a disclosure when the caller is not entitled to the row.
     */
    private JsonNode requireBrandOwned(UUID id, UUID resolvedBrandId) {
        JsonNode existing = daoGatewayClient.get("/creators/" + id, null);
        String ownerBrandId = existing != null && existing.hasNonNull("brandId")
                ? existing.get("brandId").asText()
                : null;
        if (ownerBrandId == null || !resolvedBrandId.toString().equals(ownerBrandId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator not found");
        }
        return existing;
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
