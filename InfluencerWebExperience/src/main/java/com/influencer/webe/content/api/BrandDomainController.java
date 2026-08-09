package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.BrandDomainService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Brand-owned domains and the hosting window (roadmap Phase E).
 *
 * <p>Note what is missing: any endpoint that buys a domain. Decision #9 — the brand purchases on
 * their own registrar account and the platform never resells, which removes a reseller
 * agreement, markup logic and the question of who owns a domain when a brand leaves. The
 * roadmap's "connect existing" and "provision new" paths therefore collapse into one flow with
 * a different starting point.
 */
@RestController
public class BrandDomainController {

    private final BrandDomainService domains;
    private final RequestUserResolver requestUserResolver;

    public BrandDomainController(BrandDomainService domains, RequestUserResolver requestUserResolver) {
        this.domains = domains;
        this.requestUserResolver = requestUserResolver;
    }

    @GetMapping("/api/brand-domains")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        return domains.list(brandId);
    }

    /** Connect a domain the brand already owns; returns the DNS records they must create. */
    @PostMapping("/api/brand-domains")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode connect(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE);
        UUID templateId = payload.hasNonNull("landingTemplateId")
                ? UUID.fromString(payload.get("landingTemplateId").asText()) : null;
        return domains.connect(brandId, payload.path("domainName").asText(null), templateId);
    }

    /**
     * Check DNS and issue a certificate once verified.
     *
     * <p>Returns 200 with {@code verified: false} when the record is not there yet. Not-yet is a
     * normal state a brand will poll, not an error — DNS changes can take up to 48 hours, and
     * showing an error for something in progress teaches people to ignore errors.
     */
    @PostMapping("/api/brand-domains/{id}/verify")
    public JsonNode verify(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE);
        return domains.verify(brandId, id);
    }

    @DeleteMapping("/api/brand-domains/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_WRITE);
        domains.disconnect(brandId, id);
    }

    /**
     * Extend a page's hosting window — a promotion, or payment.
     *
     * <p>Requires {@code content:publish} rather than {@code content:write}: extending hosting is
     * a commercial decision about keeping a page live, closer to publishing than to editing.
     */
    @PostMapping("/api/landing-pages/{id}/hosting/extend")
    public JsonNode extend(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_PUBLISH);
        if (!payload.hasNonNull("days")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days is required");
        }
        return domains.extendHosting(brandId, id, payload.get("days").asInt());
    }
}
