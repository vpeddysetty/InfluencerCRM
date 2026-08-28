package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.application.CreatorInvitationService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.shared.application.RequestUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Inviting a creator to work with a brand (roadmap PR-41).
 *
 * <p>Two audiences, and their authentication differs in kind. The brand side holds an operator JWT
 * and needs {@code creator:write} — inviting somebody to collaborate is a statement about the
 * brand's own creator relationships. The creator side holds <b>nothing</b>: the invitation token is
 * the entire credential, which is the only thing it can be, because the person being invited has
 * no account until they redeem it.
 */
@RestController
@RequestMapping("/api")
public class CreatorInviteController {

    private final CreatorInvitationService invitations;
    private final RequestUserResolver requestUserResolver;

    public CreatorInviteController(CreatorInvitationService invitations,
                                   RequestUserResolver requestUserResolver) {
        this.invitations = invitations;
        this.requestUserResolver = requestUserResolver;
    }

    // ---- brand side ----------------------------------------------------

    /**
     * Invite a creator by email.
     *
     * <p>{@code requirePermissionForBrand}, not {@code requirePermission}: the brand comes from the
     * verified token and is what the invitation is issued against. Taking it from the body would
     * let anyone with {@code creator:write} in one brand invite creators into another — the same
     * defect OP-18 fixed on the claim-decision path.
     */
    @PostMapping("/creator-invites")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode invite(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @Valid @RequestBody InviteRequest request) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.CREATOR_WRITE);

        CreatorInvitationService.InvitationCreated created = invitations.invite(
                brandId,
                request.email(),
                request.creatorId(),
                request.landingTemplateId(),
                context.userId(),
                request.brandName());

        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.set("invitation", created.invitation());
        response.put("delivered", created.delivered());
        // The token is returned to the INVITING BRAND, once. That is deliberate and it is what
        // makes the feature usable while SES is still in the sandbox: when delivery fails, the
        // brand can pass the link on themselves rather than the invitation being unusable. It is
        // never returned on any later read.
        response.put("token", created.token());
        return response;
    }

    @GetMapping("/creator-invites")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) String status) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return invitations.list(brandId, status);
    }

    @PostMapping("/creator-invites/{inviteId}/revoke")
    public JsonNode revoke(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID inviteId) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_WRITE);
        return invitations.revoke(brandId, inviteId);
    }

    // ---- creator side (no account yet, by definition) -------------------

    /**
     * What the invite screen shows before anyone commits to anything.
     *
     * <p>Unauthenticated by necessity: the person holding this link has no account, which is the
     * whole reason they were sent it. The 256-bit single-use token IS the credential — the same
     * structural argument as {@code /api/auth/verify-email}.
     *
     * <p>The response is deliberately <b>redacted</b>: status and brand, never the page. A GET
     * that rendered stored unpublished content would be fetched automatically by email scanners
     * and link unfurlers, so one forwarded invitation would leak an unreleased campaign to a Slack
     * channel nobody meant to share it with.
     */
    @GetMapping("/public/creator-invites/preview")
    public JsonNode preview(@RequestParam String token) {
        return invitations.preview(token);
    }

    /**
     * Accept the invitation.
     *
     * <p>A POST, not a GET, and that is a security property rather than REST tidiness: mail
     * scanners and unfurlers follow links, so a GET here would let an invitation be accepted by a
     * corporate spam filter before the human ever saw it.
     */
    @PostMapping("/public/creator-invites/redeem")
    public JsonNode redeem(@Valid @RequestBody RedeemRequest request) {
        // The password is hashed in the service layer alongside the rest of the portal's
        // credential handling; nothing raw is forwarded to the DAO.
        return invitations.redeem(request.token(), request.displayName(), null);
    }

    public record InviteRequest(@Email @NotBlank String email,
                                UUID creatorId,
                                UUID landingTemplateId,
                                String brandName) {
    }

    public record RedeemRequest(@NotBlank String token, String displayName) {
    }
}
