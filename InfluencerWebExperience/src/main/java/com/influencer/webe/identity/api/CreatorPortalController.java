package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.application.ConsentService;
import com.influencer.webe.identity.application.CreatorPortalService;
import com.influencer.webe.identity.infrastructure.DaoCreatorIdentityClient;
import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.shared.application.RequestUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The creator portal (roadmap Stage 4).
 *
 * <p>Two audiences, deliberately in one file because they are two halves of one handshake: a
 * creator claims a brand's record, and the brand approves it. Splitting them would hide that the
 * approval is what makes the claim mean anything.
 *
 * <p>Creator routes authenticate with an opaque portal token, never the operator JWT — a creator
 * has no account, no brand and no {@code account_role}. Brand routes authenticate normally and
 * require {@code creator:write}, because confirming a link is a statement about the brand's own
 * creator record.
 */
@RestController
@RequestMapping("/api/creator-portal")
public class CreatorPortalController {

    private final CreatorPortalService portalService;
    private final DaoCreatorIdentityClient creatorIdentityClient;
    private final RequestUserResolver requestUserResolver;
    private final ConsentService consentService;

    public CreatorPortalController(CreatorPortalService portalService,
                                   DaoCreatorIdentityClient creatorIdentityClient,
                                   RequestUserResolver requestUserResolver,
                                   ConsentService consentService) {
        this.portalService = portalService;
        this.creatorIdentityClient = creatorIdentityClient;
        this.requestUserResolver = requestUserResolver;
        this.consentService = consentService;
    }

    // ------------------------------------------------------------------ creator side

    /**
     * Creator portal signup — a real authenticating account, not a CRM record.
     *
     * <p>Worth stating because the landing page long described creators as records a brand owns
     * rather than people who sign in. Since the portal exists they are both, and a creator is the
     * data subject whose personal data the platform is largely processing — so this is the surface
     * where consent matters most, not least.
     */
    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView signup(@Valid @RequestBody CreatorSignupRequest request,
                              HttpServletRequest httpRequest) {
        consentService.requireAccepted(request.acceptedTerms());

        CreatorPortalService.CreatorSession session =
                portalService.signup(request.email(), request.password(), request.displayName());

        consentService.recordSignupConsent(
                ConsentService.SUBJECT_CREATOR_IDENTITY,
                session.creatorIdentityId(),
                session.email(),
                "creator_portal_signup",
                httpRequest,
                null);

        return SessionView.of(session);
    }

    @PostMapping("/auth/login")
    public SessionView login(@Valid @RequestBody CreatorLoginRequest request) {
        return SessionView.of(portalService.login(request.email(), request.password()));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = "X-Creator-Token", required = false) String token) {
        portalService.logout(token);
    }

    @GetMapping("/me")
    public SessionView me(@RequestHeader(value = "X-Creator-Token", required = false) String token) {
        return SessionView.of(requireCreator(token));
    }

    /** Everything this creator has been confirmed against, across every brand. */
    @GetMapping("/collaborations")
    public List<Map<String, Object>> collaborations(
            @RequestHeader(value = "X-Creator-Token", required = false) String token) {
        return portalService.collaborations(requireCreator(token).creatorIdentityId());
    }

    /** Asserts that a brand's creator record is this person. Unverified until the brand agrees. */
    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode claim(@RequestHeader(value = "X-Creator-Token", required = false) String token,
                          @Valid @RequestBody ClaimRequest request) {
        var session = requireCreator(token);
        return portalService.claim(session.creatorIdentityId(), request.creatorId(), request.brandId());
    }

    @GetMapping("/claims")
    public JsonNode myClaims(@RequestHeader(value = "X-Creator-Token", required = false) String token) {
        return creatorIdentityClient.links(requireCreator(token).creatorIdentityId(), null);
    }

    // ------------------------------------------------------------------ brand side

    /** Claims awaiting a decision on the caller's active brand. */
    @GetMapping("/pending-claims")
    public JsonNode pendingClaims(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CREATOR_READ);
        return creatorIdentityClient.pendingForBrand(brandId);
    }

    /**
     * Approves or refuses a claim.
     *
     * <p>Requires {@code creator:write}: confirming a link decides who may see a brand's negotiated
     * terms for that creator, which is a write about the brand's own data even though the row it
     * touches lives in the identity schema.
     */
    @PostMapping("/claims/{linkId}/{decision}")
    public JsonNode decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID linkId,
                           @PathVariable String decision) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.CREATOR_WRITE);
        String status = switch (decision) {
            case "approve" -> "confirmed";
            case "reject" -> "rejected";
            default -> throw new IllegalArgumentException("decision must be approve or reject");
        };
        return creatorIdentityClient.decide(linkId, status, context.userId());
    }

    /**
     * Invites a creator directly, linking them as confirmed in one step.
     *
     * <p>Confirmed immediately because the brand is the party that would otherwise be approving —
     * requiring them to approve their own invitation would be ceremony, not a control.
     */
    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode inviteCreator(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @Valid @RequestBody CreatorInviteRequest request) {
        TenantContext context = requestUserResolver.requirePermission(authorization, Permission.CREATOR_WRITE);
        var identity = creatorIdentityClient.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException(
                        "That creator has no portal account yet. Ask them to sign up first."));
        return creatorIdentityClient.link(
                UUID.fromString(identity.get("id").asText()),
                request.creatorId(),
                context.brandId(),
                "confirmed",
                context.userId());
    }

    private CreatorPortalService.CreatorSession requireCreator(String token) {
        return portalService.resolve(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "A valid creator session is required"));
    }

    // ------------------------------------------------------------------ payloads

    /**
     * @param acceptedTerms whether the consent checkbox was ticked. Boxed so that "absent" is not
     *     silently reported as a deliberate refusal; either way ConsentService rejects it.
     */
    public record CreatorSignupRequest(@Email @NotBlank String email,
                                       @NotBlank String password,
                                       String displayName,
                                       Boolean acceptedTerms) {
    }

    public record CreatorLoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record ClaimRequest(UUID creatorId, UUID brandId) {
    }

    public record CreatorInviteRequest(@Email @NotBlank String email, UUID creatorId) {
    }

    /** No password hash, and no brand or account fields — a creator session has neither. */
    public record SessionView(String token, UUID creatorIdentityId, String email, String displayName) {
        static SessionView of(CreatorPortalService.CreatorSession session) {
            return new SessionView(session.token(), session.creatorIdentityId(),
                    session.email(), session.displayName());
        }
    }
}
