package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.EmailPort;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Invites a creator to work with a brand (roadmap PR-41).
 *
 * <p><b>This is what makes the collaboration feature reachable.</b>
 * {@code PageCollaborationService.invite} refuses unless the creator already holds a
 * <i>confirmed</i> link to the brand, and the only route to {@code confirmed} was a creator
 * claiming a brand they had to learn about out of band — so the backend has been complete and
 * unusable since Phase G. An invitation carries the brand's decision in a token, and redeeming it
 * creates the identity and the confirmed link together.
 *
 * <p><b>Modelled on {@link MemberInvitationService} deliberately</b>, down to the token discipline
 * and the delivery-failure behaviour. Two invitation flows that behaved differently would be two
 * sets of rules to remember, and the differences would not be principled ones.
 */
@Service
public class CreatorInvitationService {

    private static final Logger log = LoggerFactory.getLogger(CreatorInvitationService.class);

    /**
     * Seven days, matching member invitations.
     *
     * <p>Long enough that a creator who reads mail weekly is not locked out, short enough that a
     * forwarded or leaked link stops working before anyone has forgotten it exists.
     */
    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final DaoGatewayClient dao;
    private final EmailPort emailPort;
    private final String creatorPortalBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public CreatorInvitationService(DaoGatewayClient dao,
                                    EmailPort emailPort,
                                    @Value("${web-experience.creator-portal-base-url:}") String portalBaseUrl,
                                    @Value("${web-experience.ui-base-url:}") String uiBaseUrl) {
        this.dao = dao;
        this.emailPort = emailPort;
        // The creator portal is its own site (PR-43). Until it exists the invitation link falls
        // back to the operator UI, so an invitation sent today is not a dead link tomorrow.
        //
        // FIRST value only, and the trailing slash stripped: ui-base-url may be a comma-separated
        // list because the same site is served from several hostnames and CORS must allow them
        // all. MemberInvitationService learned this live — the whole string produced
        // "https://tejdux.com,https://www.tejdux.com/accept?token=..." which is not a link.
        String configured = portalBaseUrl == null || portalBaseUrl.isBlank() ? uiBaseUrl : portalBaseUrl;
        this.creatorPortalBaseUrl = configured == null ? ""
                : configured.split(",")[0].trim().replaceAll("/+$", "");
    }

    /**
     * Create an invitation and email it.
     *
     * @return the stored invitation, plus the one-time token and whether the mail was delivered
     */
    public InvitationCreated invite(UUID brandId, String email, UUID creatorId,
                                    UUID landingTemplateId, UUID invitedByUserId, String brandName) {
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty() || !normalized.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email address is required");
        }

        String token = generateToken();
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("brandId", brandId.toString());
        body.put("email", normalized);
        body.put("tokenHash", hash(token));
        body.put("expiresAt", Instant.now().plus(INVITATION_TTL).toString());
        if (creatorId != null) {
            body.put("creatorId", creatorId.toString());
        }
        if (landingTemplateId != null) {
            body.put("landingTemplateId", landingTemplateId.toString());
        }
        if (invitedByUserId != null) {
            body.put("invitedByUserId", invitedByUserId.toString());
        }

        JsonNode created;
        try {
            created = dao.post("/creator-invites", body);
        } catch (RuntimeException e) {
            // The partial unique index refuses a second PENDING invitation for the same
            // brand+email. That is a deliberate guard, not a fault: two live tokens would mean
            // revoking the visible one still let the other in. Reported as a conflict the UI can
            // act on ("already invited — resend or revoke?") rather than a 500.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This creator already has a pending invitation from your brand.");
        }

        boolean delivered = sendInvitationEmail(normalized, token, brandName);
        return new InvitationCreated(created, token, delivered);
    }

    /**
     * Look up an invitation for the invite screen, without redeeming it.
     *
     * <p>Returns a <b>redacted</b> view: brand name and status, never the page. A GET that rendered
     * stored unpublished content would be fetched automatically by email scanners, Slack and
     * WhatsApp unfurlers, and link prewarmers — so one forwarded invitation would leak an
     * unreleased campaign to whoever the recipient happened to paste it in front of. Acceptance is
     * a POST for the same reason.
     */
    public JsonNode preview(String token) {
        JsonNode invite = fetchByToken(token);
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("status", invite.path("status").asText("pending"));
        view.put("email", invite.path("email").asText(""));
        view.put("expiresAt", invite.path("expiresAt").asText(""));
        if (invite.hasNonNull("brandId")) {
            view.put("brandId", invite.get("brandId").asText());
        }
        // Deliberately NOT the landing template's content, name or slug.
        view.put("hasPage", invite.hasNonNull("landingTemplateId"));
        return view;
    }

    /**
     * Redeem an invitation, creating the identity and the confirmed link.
     *
     * <p>The whole redemption happens in one DAO transaction — see {@code CreatorInviteController}
     * for why a partial one is worse than a failure in both directions.
     */
    /** Matches CreatorPortalService: the same encoder has to verify what this writes. */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public JsonNode redeem(String token, String displayName, String rawPassword) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        if (displayName != null && !displayName.isBlank()) {
            body.put("displayName", displayName.trim());
        }
        // Hashed HERE, never forwarded raw and never hashed in the browser: the DAO stores what it
        // is given, so a raw password crossing this boundary would be a raw password at rest.
        // BCrypt to match CreatorPortalService, which is what verifies it at sign-in -- a different
        // algorithm here would produce an account that cannot log in.
        if (rawPassword != null && !rawPassword.isBlank()) {
            body.put("passwordHash", passwordEncoder.encode(rawPassword));
        }
        // Read the invitation BEFORE redeeming it: the DAO returns the confirmed LINK row, which
        // carries the brand and the identity but not the page the invitation was sent from. The
        // caller needs that page id to put the creator on it, and after redemption the token is
        // spent -- so this is the last moment it can be looked up.
        //
        // Best-effort: a failure here must not stop a redemption that would otherwise work. The
        // page grant is a convenience on top of the identity and the link, not a precondition.
        JsonNode invited = null;
        try {
            invited = fetchByToken(token);
        } catch (RuntimeException ignored) {
            // Falls through: redeem below reports the real problem with a message meant for a user.
        }

        try {
            JsonNode result = dao.post("/creator-invites/by-token/" + hash(token) + "/redeem", body);
            return withInvitationContext(result, invited);
        } catch (RuntimeException e) {
            // Expired, already used, revoked, or never existed. Deliberately one message: telling
            // a caller which of those applies lets someone probing tokens learn that one was real.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This invitation is no longer valid. Ask the brand to send a new one.");
        }
    }

    public JsonNode list(UUID brandId, String status) {
        Map<String, String> query = status == null || status.isBlank()
                ? Map.of("brandId", brandId.toString())
                : Map.of("brandId", brandId.toString(), "status", status);
        JsonNode rows = dao.get("/creator-invites", query);
        return rows == null ? JsonNodeFactory.instance.arrayNode() : rows;
    }

    public JsonNode revoke(UUID brandId, UUID inviteId) {
        JsonNode invite = dao.get("/creator-invites", Map.of("brandId", brandId.toString()));
        boolean ours = false;
        if (invite != null && invite.isArray()) {
            for (JsonNode row : invite) {
                if (inviteId.toString().equals(row.path("id").asText())) {
                    ours = true;
                    break;
                }
            }
        }
        if (!ours) {
            // 404 rather than 403: a caller probing invitation ids must not learn which exist.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found");
        }
        return dao.post("/creator-invites/" + inviteId + "/revoke",
                JsonNodeFactory.instance.objectNode());
    }

    // ---- internals -----------------------------------------------------

    /**
     * Add the page and the inviter to the link row the DAO hands back.
     *
     * <p>Copied rather than re-fetched because the token is spent by now, and returned as extra
     * fields rather than a new shape so existing callers see exactly what they saw before.
     *
     * <p>Only these two. The invitation also holds the email and the status, which the redeemed
     * link already implies, and there is no reason to widen a public endpoint's response beyond
     * what its caller has to act on.
     */
    private JsonNode withInvitationContext(JsonNode redeemed, JsonNode invite) {
        if (redeemed == null || !redeemed.isObject() || invite == null) {
            return redeemed;
        }
        ObjectNode merged = ((ObjectNode) redeemed);
        if (invite.hasNonNull("landingTemplateId")) {
            merged.put("landingTemplateId", invite.get("landingTemplateId").asText());
        }
        if (invite.hasNonNull("invitedByUserId")) {
            merged.put("invitedByUserId", invite.get("invitedByUserId").asText());
        }
        return merged;
    }

    private JsonNode fetchByToken(String token) {
        JsonNode invite;
        try {
            invite = dao.get("/creator-invites/by-token/" + hash(token), Map.of());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found");
        }
        if (invite == null || !invite.hasNonNull("status")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found");
        }
        return invite;
    }

    /**
     * Send the invitation. A delivery failure does <b>not</b> roll the invitation back.
     *
     * <p>The token is valid whether or not the mail server was reachable, and discarding a good
     * invitation because SES was briefly down would turn a retryable problem into a lost one. The
     * caller gets {@code delivered=false} and can offer the link directly — which is also what
     * makes this feature work at all while SES is still in the sandbox.
     */
    private boolean sendInvitationEmail(String email, String token, String brandName) {
        String brand = brandName == null || brandName.isBlank() ? "A brand" : brandName.trim();
        try {
            // The token travels in a query string, so it is encoded here rather than assumed safe:
            // the alphabet is Base64-URL today, and encoding means a future change to the token
            // format cannot silently produce a broken link.
            String acceptUrl = creatorPortalBaseUrl + "/invite?token="
                    + UriUtils.encodeQueryParam(token, StandardCharsets.UTF_8);

            String body = brand + " has invited you to collaborate with them on Tejdux.\n\n"
                    + "Tejdux is where brands and creators work on campaign pages together. "
                    + "Accepting lets " + brand + " share pages with you, and lets you edit the "
                    + "ones they ask you to help with.\n\n"
                    + "Accept the invitation:\n" + acceptUrl + "\n\n"
                    + "This link expires in 7 days and can only be used once.\n\n"
                    + "If you were not expecting this, you can ignore this email — nothing is "
                    + "shared with you unless you accept.";

            // The port REPORTS failure rather than throwing it: the `log` provider returns
            // sent=false having written a line, and that is the configured default today. Checking
            // the result rather than only catching exceptions is what stops this reporting
            // delivered=true for mail that was never sent.
            EmailPort.Result result = emailPort.send(EmailPort.Message.text(email,
                    brand + " invited you to collaborate on Tejdux", body));
            if (!result.sent()) {
                log.warn("Creator invitation stored but not delivered to {} via {}: {}",
                        email, result.provider(), result.detail());
            }
            return result.sent();
        } catch (RuntimeException e) {
            // Logged WITHOUT the token or the URL: application logs are not a secret store, and an
            // invitation link in a log is a credential in a log.
            log.warn("Creator invitation stored but not delivered to {}", email, e);
            return false;
        }
    }

    private String generateToken() {
        // 32 bytes = 256 bits, the same as member invitations, refresh tokens and portal sessions.
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, Base64-URL encoded — byte-identical to {@code MemberInvitationService.hash}.
     *
     * <p>Not BCrypt: the token is 256 bits of {@code SecureRandom}, so there is no low-entropy
     * secret to brute-force and a slow hash would only cost latency on every redemption.
     */
    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @param invitation the stored row
     * @param token      shown ONCE. Never stored, never logged — the caller may display it so a
     *                   brand can pass the link on directly when mail delivery failed.
     * @param delivered  whether the email was accepted by the transport
     */
    public record InvitationCreated(JsonNode invitation, String token, boolean delivered) {
    }
}
