package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.BrandPageTemplateService;
import com.influencer.webe.identity.application.EntitlementService;
import com.influencer.webe.identity.application.PlanPolicy;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Brand-saved page templates (roadmap PR-39, piece D).
 *
 * <p><b>Brand scope comes from the verified token, never from the body.</b> Same rule as every
 * other content endpoint: a brandId a caller can set is a brandId a caller can change.
 */
@RestController
public class BrandPageTemplateController {

    private final BrandPageTemplateService templates;
    private final RequestUserResolver requestUserResolver;
    private final EntitlementService entitlements;

    public BrandPageTemplateController(BrandPageTemplateService templates,
                                       RequestUserResolver requestUserResolver,
                                       EntitlementService entitlements) {
        this.templates = templates;
        this.requestUserResolver = requestUserResolver;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/brand-page-templates")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        return templates.list(brandId);
    }

    /**
     * Save the current page as a template.
     *
     * <p>The plan check runs before the write and only for a NEW name: saving over a template that
     * already exists is a replacement, and blocking it at the limit would mean an account at its
     * cap could not update the templates it already has — the same mistake the landing-page upsert
     * check exists to avoid.
     */
    @PostMapping("/api/brand-page-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode save(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requireTenantContext(authorization);
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE);
        if (!templates.existsByName(brandId, payload.path("name").asText(""))) {
            entitlements.requireCapacity(context.accountId(), PlanPolicy.Resource.SAVED_TEMPLATE);
        }
        return templates.save(brandId, context.userId(), payload);
    }

    @DeleteMapping("/api/brand-page-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE);
        templates.delete(brandId, id);
    }
}
