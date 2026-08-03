package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.identity.application.MemberInvitationService;
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
    private final MemberInvitationService invitationService;

    public BrandsController(DaoTenancyClient tenancyClient,
                            RequestUserResolver requestUserResolver,
                            SessionService sessionService,
                            MemberInvitationService invitationService) {
        this.tenancyClient = tenancyClient;
        this.requestUserResolver = requestUserResolver;
        this.sessionService = sessionService;
        this.invitationService = invitationService;
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

    // ------------------------------------------------------------------ invitations

    /**
     * Invites someone onto the caller's account.
     *
     * <p>The account is taken from the verified token, never from the request: an accountId a
     * caller could supply would let anyone with {@code member:invite} on their own account add
     * members to someone else's.
     *
     * <p>The token comes back exactly once, in this response. It is stored only as a hash.
     */
    @PostMapping("/members/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse invite(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @Valid @RequestBody InviteRequest request) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.MEMBER_INVITE);
        var created = invitationService.invite(
                context.accountId(), context.userId(), request.email(), request.role(), request.brandId());
        return new InviteResponse(created.invitation(), created.token());
    }

    @GetMapping("/members/invitations")
    public JsonNode invitations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.MEMBER_INVITE);
        return invitationService.list(context.accountId());
    }

    @PostMapping("/members/invitations/{id}/revoke")
    public JsonNode revokeInvitation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable UUID id) {
        requestUserResolver.requirePermission(authorization, Permission.MEMBER_REMOVE);
        return invitationService.revoke(id);
    }

    /**
     * Accepts an invitation as the signed-in user.
     *
     * <p>Requires no permission on the target account by design — the whole point is that the
     * caller is not yet a member. The token is the authorization, and the service additionally
     * checks it was issued to this user's email.
     */
    @PostMapping("/members/invitations/accept")
    public JsonNode acceptInvitation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @Valid @RequestBody AcceptInviteRequest request) {
        TenantContext context = requestUserResolver.requireTenantContext(authorization);
        return invitationService.accept(request.token(), context.userId(), context.email());
    }

    @PutMapping("/members/{userId}")
    public JsonNode updateMemberRole(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable UUID userId,
                                     @Valid @RequestBody UpdateMemberRequest request) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.MEMBER_UPDATE);
        if (userId.equals(context.userId())) {
            // Otherwise the last owner can demote themselves and lock the account out of its own
            // member administration, with no one able to undo it.
            throw new IllegalArgumentException("You cannot change your own role");
        }
        return tenancyClient.updateMemberRole(context.accountId(), userId, request.role());
    }

    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable UUID userId) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.MEMBER_REMOVE);
        if (userId.equals(context.userId())) {
            throw new IllegalArgumentException("You cannot remove yourself from the account");
        }
        tenancyClient.removeMember(context.accountId(), userId);
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

    public record InviteRequest(@NotBlank String email, @NotBlank String role, UUID brandId) {
    }

    /** {@code token} appears here and nowhere else — only its hash is persisted. */
    public record InviteResponse(JsonNode invitation, String token) {
    }

    public record AcceptInviteRequest(@NotBlank String token) {
    }

    public record UpdateMemberRequest(@NotBlank String role) {
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
