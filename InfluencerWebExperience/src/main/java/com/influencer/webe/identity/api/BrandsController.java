package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.identity.application.EntitlementService;
import com.influencer.webe.identity.application.MemberInvitationService;
import com.influencer.webe.identity.application.PlanPolicy;
import com.influencer.webe.identity.application.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
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
    private final EntitlementService entitlements;

    public BrandsController(DaoTenancyClient tenancyClient,
                            RequestUserResolver requestUserResolver,
                            SessionService sessionService,
                            MemberInvitationService invitationService,
                            EntitlementService entitlements) {
        this.tenancyClient = tenancyClient;
        this.requestUserResolver = requestUserResolver;
        this.sessionService = sessionService;
        this.invitationService = invitationService;
        this.entitlements = entitlements;
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
        // M2.3. Multi-brand is the agency tier's defining feature, so this is the limit that
        // actually distinguishes the plans rather than merely sizing them.
        entitlements.requireCapacity(context.accountId(), PlanPolicy.Resource.BRAND);
        return tenancyClient.createBrand(context.accountId(), request.name());
    }

    /**
     * The caller's plan, and what they have used of it (M2.3).
     *
     * <p>Exists so a limit is visible <em>before</em> it is hit. Enforcement alone would mean a
     * user discovers their plan only by being refused mid-task, which reads as a bug rather than
     * a boundary. It also gives the UI what it needs to show "23 of 25 creators" rather than
     * waiting to surface a 402.
     *
     * <p>Requires only a valid session, not a permission: everyone on an account can be told what
     * that account's plan includes, and hiding it from non-admins would mean a member who cannot
     * create a creator gets no explanation of why.
     */
    @GetMapping("/plan")
    public PlanUsageResponse plan(@RequestHeader(value = "Authorization", required = false) String authorization) {
        TenantContext context = requestUserResolver.requireTenantContext(authorization);
        PlanPolicy plan = entitlements.planFor(context.accountId());

        List<ResourceUsage> usage = Arrays.stream(PlanPolicy.Resource.values())
                .map(resource -> new ResourceUsage(
                        resource.name().toLowerCase(),
                        resource.plural(),
                        entitlements.currentUsage(context.accountId(), resource),
                        plan.limitFor(resource)))
                .toList();

        return new PlanUsageResponse(plan.key(), usage);
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
        // M2.3. Counts current members PLUS pending invitations. Counting members alone would let
        // an account at its limit send any number of invitations that all fail on acceptance —
        // the person who accepts hits the wall, having done nothing wrong, and the admin who
        // invited them never sees an error.
        entitlements.requireCapacity(context.accountId(), PlanPolicy.Resource.MEMBER,
                entitlements.currentUsage(context.accountId(), PlanPolicy.Resource.MEMBER)
                        + pendingInvitationCount(context));
        var created = invitationService.invite(
                context.accountId(), context.userId(), request.email(), request.role(), request.brandId(),
                // Both are for the email body only. The inviter's email stands in for a display
                // name, which the token does not carry — "nobody invited you" is worse than an
                // address, and recipients ignore invitations from an unidentified sender.
                brandNameFor(context, request.brandId()), context.email());
        return new InviteResponse(created.invitation(), created.token(), created.emailDelivered());
    }

    /**
     * Invitations that are still outstanding and would each become a member (M2.3).
     *
     * <p>Only {@code pending} ones count. A revoked or already-accepted invitation is not a future
     * member — an accepted one is a member already, and counting it would charge the seat twice.
     * Expired ones are excluded too: they cannot be redeemed, so holding a seat for them would
     * make an account permanently smaller every time an invitation went unanswered.
     *
     * <p>Fails open on error, deliberately. If this count cannot be read, the member count alone
     * still applies; blocking a legitimate invitation because a secondary lookup failed is the
     * worse outcome, and the primary limit is still enforced.
     */
    private long pendingInvitationCount(TenantContext context) {
        try {
            JsonNode invitations = invitationService.list(context.accountId());
            if (invitations == null || !invitations.isArray()) {
                return 0;
            }
            Instant now = Instant.now();
            long pending = 0;
            for (JsonNode invitation : invitations) {
                if (!"pending".equalsIgnoreCase(invitation.path("status").asText(""))) {
                    continue;
                }
                String expiresAt = invitation.path("expiresAt").asText(null);
                if (expiresAt != null) {
                    try {
                        if (Instant.parse(expiresAt).isBefore(now)) {
                            continue;
                        }
                    } catch (Exception ignored) {
                        // An unreadable expiry is treated as still pending — the conservative
                        // reading, since the alternative under-counts and over-grants seats.
                    }
                }
                pending++;
            }
            return pending;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Best-effort brand name for the invitation email.
     *
     * <p>Falls back to null (which the composer renders as "a workspace") rather than failing:
     * an invitation must not be blocked because a display string could not be resolved. An
     * account-wide invite carries no brandId at all, in which case the caller's active brand is
     * the closest thing to the workspace they mean.
     */
    private String brandNameFor(TenantContext context, UUID requestedBrandId) {
        UUID target = requestedBrandId != null ? requestedBrandId : context.brandId();
        if (target == null) {
            return null;
        }
        try {
            return tenancyClient.findAccessibleBrands(context.userId()).stream()
                    .filter(brand -> target.equals(brand.brandId()))
                    .map(DaoTenancyClient.BrandAccess::brandName)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
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
        // Choosing what a teammate may do is the paid feature. Existing assignments keep working;
        // this refuses the change, not the roles already in force.
        entitlements.requireRoleBasedAccess(context.accountId());
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

    /**
     * The plan and its consumption (M2.3).
     *
     * @param plan  the plan key as enforced — the resolved policy, not the raw column, so an
     *              unrecognised stored value reports the {@code free} limits actually applied
     *              rather than a name nothing honours
     */
    public record PlanUsageResponse(String plan, List<ResourceUsage> usage) {
    }

    /**
     * @param limit {@code -1} means unlimited. Sent as-is rather than as a large number so a UI
     *              can render "unlimited" instead of an arbitrary ceiling that looks like a cap
     */
    public record ResourceUsage(String resource, String label, long used, int limit) {
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

    /**
     * {@code token} appears here and nowhere else — only its hash is persisted.
     *
     * <p>{@code emailDelivered} tells the UI whether an email actually went out. It exists because
     * the honest answer differs by environment: with the log-only provider nothing is delivered,
     * and a screen that says "invitation sent" in that case is lying to the person who then waits
     * for a reply that never comes. When false, the UI shows the token as the fallback it is.
     */
    public record InviteResponse(JsonNode invitation, String token, boolean emailDelivered) {
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
