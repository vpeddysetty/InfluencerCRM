package com.influencer.webe.finance.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.finance.application.PayoutService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BFF pass-through for influencer commissions, payouts, and the dashboard rollup
 * table (Phase 0/1 substrate). Full accrual/approval/payout workflow lands in
 * Phases 3 and 5; this exposes tenant-scoped CRUD in the meantime.
 */
@RestController
@RequestMapping("/api")
public class CommissionsPayoutsController {
    private final DaoGatewayClient dao;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService shape;
    private final PayoutService payoutService;

    public CommissionsPayoutsController(DaoGatewayClient dao,
                                        RequestUserResolver requestUserResolver,
                                        ResponseShapeService shape,
                                        PayoutService payoutService) {
        this.dao = dao;
        this.requestUserResolver = requestUserResolver;
        this.shape = shape;
        this.payoutService = payoutService;
    }

    // ---- commissions ---------------------------------------------------
    @GetMapping("/influencer-commissions")
    public JsonNode listCommissions(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(required = false) UUID brandId,
                                    @RequestParam(required = false) UUID creatorId,
                                    @RequestParam(required = false) UUID payoutId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.COMMISSION_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolved.toString());
        query.put("creatorId", creatorId == null ? null : creatorId.toString());
        query.put("payoutId", payoutId == null ? null : payoutId.toString());
        query.put("status", status);
        return shape.commissionsList(dao.get("/influencer-commissions", query), page, size);
    }

    @GetMapping("/influencer-commissions/{id}")
    public JsonNode commissionById(@PathVariable UUID id) {
        return shape.commission(dao.get("/influencer-commissions/" + id, null));
    }

    @PostMapping("/influencer-commissions")
    public JsonNode createCommission(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.COMMISSION_APPROVE).toString());
        return shape.commission(dao.post("/influencer-commissions", payload));
    }

    @PutMapping("/influencer-commissions/{id}")
    public JsonNode updateCommission(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable UUID id,
                                     @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.COMMISSION_APPROVE).toString());
        return shape.commission(dao.put("/influencer-commissions/" + id, payload));
    }

    @DeleteMapping("/influencer-commissions/{id}")
    public void deleteCommission(@PathVariable UUID id) {
        dao.delete("/influencer-commissions/" + id);
    }

    // ---- payouts -------------------------------------------------------
    @GetMapping("/influencer-payouts")
    public JsonNode listPayouts(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestParam(required = false) UUID brandId,
                                @RequestParam(required = false) UUID creatorId,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.PAYOUT_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolved.toString());
        query.put("creatorId", creatorId == null ? null : creatorId.toString());
        query.put("status", status);
        return shape.payoutsList(dao.get("/influencer-payouts", query), page, size);
    }

    @GetMapping("/influencer-payouts/{id}")
    public JsonNode payoutById(@PathVariable UUID id) {
        return shape.payout(dao.get("/influencer-payouts/" + id, null));
    }

    @PostMapping("/influencer-payouts")
    public JsonNode createPayout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.PAYOUT_CREATE).toString());
        return shape.payout(dao.post("/influencer-payouts", payload));
    }

    @PutMapping("/influencer-payouts/{id}")
    public JsonNode updatePayout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @PathVariable UUID id,
                                 @RequestBody ObjectNode payload) {
        payload.put("brandId", requestUserResolver.requirePermissionForBrand(authorization, Permission.PAYOUT_APPROVE).toString());
        return shape.payout(dao.put("/influencer-payouts/" + id, payload));
    }

    @DeleteMapping("/influencer-payouts/{id}")
    public void deletePayout(@PathVariable UUID id) {
        dao.delete("/influencer-payouts/" + id);
    }

    // ---- daily attribution stats (dashboard rollup) --------------------
    @GetMapping("/daily-attribution-stats")
    public JsonNode listDailyStats(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestParam(required = false) UUID brandId,
                                   @RequestParam(required = false) UUID creatorId,
                                   @RequestParam(required = false) UUID campaignId,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        UUID resolved = requestUserResolver.requirePermissionForBrand(authorization, Permission.ATTRIBUTION_READ);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", resolved.toString());
        query.put("creatorId", creatorId == null ? null : creatorId.toString());
        query.put("campaignId", campaignId == null ? null : campaignId.toString());
        query.put("from", from);
        query.put("to", to);
        return shape.dailyStatsList(dao.get("/daily-attribution-stats", query), page, size);
    }

    // ---- payout workflow (Phase 5) -------------------------------------
    @GetMapping("/payout-providers")
    public JsonNode payoutProviders() {
        return payoutService.listProviders();
    }

    @PostMapping("/influencer-commissions/{id}/approve")
    public JsonNode approveCommission(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable UUID id,
                                      @RequestBody(required = false) ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.COMMISSION_APPROVE);
        return payoutService.approveCommission(brandId, id);
    }

    @PostMapping("/influencer-payouts/create")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode createPayoutBatch(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.PAYOUT_CREATE);
        UUID creatorId = getUuid(payload, "creatorId");
        if (creatorId == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "creatorId is required");
        }
        String providerKey = payload.hasNonNull("providerKey") ? payload.get("providerKey").asText() : "manual";
        return payoutService.createPayout(brandId, creatorId, providerKey);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
