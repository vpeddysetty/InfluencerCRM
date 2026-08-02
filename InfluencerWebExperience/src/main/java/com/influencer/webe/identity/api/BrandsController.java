package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.identity.application.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Brand listing, creation, and switching — the surface an agency uses to move between clients.
 *
 * <p>Solo accounts see exactly one brand here, which is why the UI can hide the switcher without
 * needing a separate code path.
 */
@RestController
@RequestMapping("/api/brands")
public class BrandsController {

    private final DaoTenancyClient tenancyClient;
    private final RequestUserResolver requestUserResolver;
    private final SessionService sessionService;

    public BrandsController(DaoTenancyClient tenancyClient,
                            RequestUserResolver requestUserResolver,
                            SessionService sessionService) {
        this.tenancyClient = tenancyClient;
        this.requestUserResolver = requestUserResolver;
        this.sessionService = sessionService;
    }

    /** Every brand the caller may reach, with the role they hold on each. */
    @GetMapping
    public List<BrandSummary> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        TenantContext context = requestUserResolver.requireTenantContext(authorization);
        return tenancyClient.findAccessibleBrands(context.userId()).stream()
                .map(brand -> new BrandSummary(
                        brand.brandId(),
                        brand.brandName(),
                        brand.accountId(),
                        brand.accountType(),
                        brand.role().name(),
                        brand.brandId().equals(context.brandId())))
                .toList();
    }

    /**
     * Re-mints the caller's token against another brand.
     *
     * <p>A new token is required rather than just a header because role and permissions are
     * per-brand — carrying the old brand's role across would over-grant.
     */
    @PostMapping("/switch")
    public SwitchBrandResponse switchBrand(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody SwitchBrandRequest request) {

        TenantContext context = requestUserResolver.requireTenantContext(authorization);
        SessionService.SessionInfo session =
                sessionService.switchBrand(context, request.brandId(), "switch");

        return new SwitchBrandResponse(
                session.token(),
                "Bearer",
                session.brandId(),
                session.brandName(),
                session.accountId(),
                session.role(),
                session.expiresAt());
    }

    /** Creates a brand under the caller's account. Agency-only in practice: solo accounts lack the permission. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @Valid @RequestBody CreateBrandRequest request) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.BRAND_CREATE);
        return tenancyClient.createBrand(context.accountId(), request.name());
    }

    /** Members of the caller's account. */
    @GetMapping("/members")
    public JsonNode members(@RequestHeader(value = "Authorization", required = false) String authorization) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.MEMBER_INVITE);
        return tenancyClient.members(context.accountId());
    }

    public record BrandSummary(
            UUID brandId,
            String brandName,
            UUID accountId,
            String accountType,
            String role,
            boolean active) {
    }

    public record SwitchBrandRequest(UUID brandId) {
    }

    public record SwitchBrandResponse(
            String accessToken,
            String tokenType,
            UUID brandId,
            String brandName,
            UUID accountId,
            String role,
            java.time.Instant expiresAt) {
    }

    public record CreateBrandRequest(@NotBlank String name) {
    }
}
