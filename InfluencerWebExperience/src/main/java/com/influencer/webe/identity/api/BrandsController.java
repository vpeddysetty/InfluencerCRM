package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.identity.application.BulkMemberInvitationService;
import com.influencer.webe.identity.application.ConsentService;
import com.influencer.webe.identity.application.EntitlementService;
import com.influencer.webe.identity.application.MemberInvitationService;
import com.influencer.webe.identity.application.PlanPolicy;
import com.influencer.webe.identity.application.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
    private final BulkMemberInvitationService bulkInvitations;
    private final EntitlementService entitlements;
    private final ConsentService consentService;

    public BrandsController(DaoTenancyClient tenancyClient,
                            RequestUserResolver requestUserResolver,
                            SessionService sessionService,
                            MemberInvitationService invitationService,
                            BulkMemberInvitationService bulkInvitations,
                            EntitlementService entitlements,
                            ConsentService consentService) {
        this.tenancyClient = tenancyClient;
        this.requestUserResolver = requestUserResolver;
        this.sessionService = sessionService;
        this.invitationService = invitationService;
        this.bulkInvitations = bulkInvitations;
        this.entitlements = entitlements;
        this.consentService = consentService;
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
     * Invites many people at once, from a pasted list or an uploaded file.
     *
     * <p>Not a loop over the single-invite endpoint. Duplicates within one file would revoke each
     * other, the capacity check would pass once per row against the same stale count, and the
     * pending-invitation read would fail open by the size of the batch rather than by one. Why each
     * of those matters is on {@link BulkMemberInvitationService}.
     *
     * <p><b>200, not 201.</b> A batch where eleven were invited and four were already members
     * created something, skipped something, and failed at nothing — "created" describes none of
     * that. The per-row outcomes are in the body.
     *
     * <p><b>No tokens come back.</b> Recovering an invitation nobody was emailed is the resend
     * endpoint, one at a time.
     */
    @PostMapping("/members/invite/bulk")
    public BulkMemberInvitationService.BulkInviteResult inviteBulk(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody BulkInviteRequest request) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.MEMBER_INVITE);
        return bulkInvitations.inviteAll(context, request.invitations());
    }

    /**
     * Invitations that are still outstanding and would each become a member (M2.3).
     *
     * <p>Which ones count, and why, is documented on
     * {@link MemberInvitationService#pendingInvitations}. This method exists only to choose the
     * failure policy.
     *
     * <p><b>Fails open on error, deliberately</b> — and differently from the bulk path, which
     * refuses. If this count cannot be read, the member count alone still applies; blocking a
     * legitimate single invitation because a secondary lookup failed is the worse outcome, and the
     * primary limit is still enforced. A batch cannot make that trade, because the same failure
     * would over-grant by the whole batch rather than by one.
     */
    private long pendingInvitationCount(TenantContext context) {
        return invitationService.pendingInvitations(context.accountId())
                .map(MemberInvitationService.PendingInvitations::count)
                .orElse(0L);
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

    /**
     * Revokes an outstanding invitation.
     *
     * <p>The account comes from the verified token and the service checks the invitation against it.
     * Holding {@code member:remove} on one account is not a licence to revoke invitations in
     * another, and an id is all that would be needed.
     */
    @PostMapping("/members/invitations/{id}/revoke")
    public JsonNode revokeInvitation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable UUID id) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.MEMBER_REMOVE);
        return invitationService.revoke(context.accountId(), id);
    }

    /**
     * Issues a fresh link for an invitation that was never delivered.
     *
     * <p>The companion to bulk invite, which returns no tokens. One token in one response is the
     * same contract the single invite already has, and the only shape in which returning one is
     * defensible — fifty at once would be fifty live credentials in a results table.
     *
     * <p>Invalidates the previous link. Two working tokens for one invitation would mean revoking
     * the visible one still lets the other in.
     */
    @PostMapping("/members/invitations/{id}/resend")
    public InviteResponse resendInvitation(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.MEMBER_INVITE);
        var resent = invitationService.resend(context.accountId(), id, context.email());
        return new InviteResponse(resent.invitation(), resent.token(), resent.emailDelivered());
    }

    /**
     * Accepts an invitation as the signed-in user.
     *
     * <p>Requires no permission on the target account by design — the whole point is that the
     * caller is not yet a member. The token is the authorization, and the service additionally
     * checks it was issued to this user's email.
     */
    /**
     * <p>Consent is captured here as well as at signup because an invited teammate may never have
     * seen the signup form: they arrive from an email, join someone else's account, and are bound by
     * the same terms. Without this they would be the one class of user with no record of accepting
     * anything.
     */
    @PostMapping("/members/invitations/accept")
    public JsonNode acceptInvitation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @Valid @RequestBody AcceptInviteRequest request,
                                     HttpServletRequest httpRequest) {
        TenantContext context = requestUserResolver.requireTenantContext(authorization);
        consentService.requireAccepted(request.acceptedTerms());

        JsonNode result = invitationService.accept(request.token(), context.userId(), context.email());

        // The unique index makes this a no-op for someone who already consented at signup and is now
        // joining a second account — the same person, the same document version, one row. Accepting
        // an invitation is still the moment to ASK, because it is a fresh agreement to be bound.
        consentService.recordSignupConsent(
                ConsentService.SUBJECT_USER,
                context.userId(),
                context.email(),
                "invitation_accept",
                httpRequest,
                null);

        return result;
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
     * @param invitations the rows to invite. Capped here as well as in the service: this stops a
     *                    five-thousand-row body being deserialized into work before any handler code
     *                    runs, while the service's own check is what a direct caller or a test hits
     */
    public record BulkInviteRequest(
            @NotEmpty
            @Size(max = BulkMemberInvitationService.MAX_BATCH)
            @Valid
            List<BulkMemberInvitationService.InviteRow> invitations) {
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

    /**
     * @param acceptedTerms the invitee's acceptance. Boxed, as elsewhere, so an absent field is not
     *     mistaken for a deliberate refusal — both are rejected, but only one is a client bug.
     */
    public record AcceptInviteRequest(@NotBlank String token, Boolean acceptedTerms) {
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
