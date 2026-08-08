package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.identity.infrastructure.DaoUserClient;
import com.influencer.webe.security.AccountRole;
import com.influencer.webe.shared.application.EmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Invites a user onto an existing account, and redeems those invitations.
 *
 * <p>Replaces the only mechanism that previously existed for this: signing a user up, deleting the
 * solo account their signup created, and re-parenting them with raw SQL — which is what
 * {@code schema/seed/test_accounts.sql} still did before Stage 3.
 *
 * <p>The token is generated here, returned once, and never stored. Only its SHA-256 hash reaches
 * the database, so a dump contains no usable invitations. That mirrors how refresh tokens and
 * passwords are already handled, and is the reason redemption looks the invitation up *by hash*
 * rather than by id.
 */
@Service
public class MemberInvitationService {

    /**
     * Seven days. Long enough to survive a weekend and an inattentive inbox; short enough that a
     * forgotten invitation in an old email is not a permanent way into an account.
     */
    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(MemberInvitationService.class);

    private final DaoTenancyClient tenancyClient;
    private final DaoUserClient userClient;
    private final EmailPort emailPort;
    private final String uiBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public MemberInvitationService(DaoTenancyClient tenancyClient,
                                   DaoUserClient userClient,
                                   EmailPort emailPort,
                                   @Value("${web-experience.ui-base-url}") String uiBaseUrl) {
        this.tenancyClient = tenancyClient;
        this.userClient = userClient;
        this.emailPort = emailPort;
        // Trailing slash stripped once here rather than at each call site, so the accept URL
        // cannot come out with a double slash that some mail clients decline to linkify.
        this.uiBaseUrl = uiBaseUrl == null ? "" : uiBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Creates an invitation and returns it together with the one-time token.
     *
     * <p>The role is validated against {@link AccountRole} so an unknown value is refused rather
     * than reaching the database and failing on the enum constraint as a 500.
     *
     * <p>{@code OWNER} cannot be granted here. Ownership is not a role you hand out through an
     * invite screen: it carries billing and the right to delete the account, and transferring it
     * should be a deliberate, separately-confirmed act rather than a dropdown selection.
     */
    public InvitationCreated invite(UUID accountId, UUID invitedBy, String email, String role, UUID brandId,
                                    String brandName, String inviterName) {
        String normalizedEmail = normalizeEmail(email);
        AccountRole resolved = AccountRole.parse(role)
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + role));
        if (resolved == AccountRole.OWNER) {
            throw new IllegalArgumentException(
                    "OWNER cannot be granted by invitation; transfer ownership explicitly instead");
        }

        String token = generateToken();
        JsonNode created = tenancyClient.createInvitation(
                accountId, normalizedEmail, resolved.name(), brandId,
                hash(token), invitedBy, Instant.now().plus(INVITATION_TTL));

        boolean delivered = sendInvitationEmail(normalizedEmail, resolved, token, brandName, inviterName);

        return new InvitationCreated(created, token, delivered);
    }

    /**
     * Sends the invitation, best-effort.
     *
     * <p>Deliberately after the invitation is persisted and deliberately unable to fail the call.
     * The token exists and is valid whether or not the mail server was reachable; rolling the
     * invitation back over a transient SMTP problem would destroy a valid grant and leave the
     * inviter with nothing to resend.
     *
     * <p>The response still carries the token (see {@link InvitationCreated}), so the UI can fall
     * back to showing it when {@code provider()} is {@code log} — which is exactly the situation
     * where nothing was actually delivered.
     */
    private boolean sendInvitationEmail(String email, AccountRole role, String token,
                                        String brandName, String inviterName) {
        try {
            // The token travels in the query string, so it must be encoded: the alphabet is
            // Base64-URL, but encoding here means a future change to the token format cannot
            // silently produce a link that breaks on the first `+` or `=`.
            String acceptUrl = uiBaseUrl + "/accept-invitation?token="
                    + UriUtils.encodeQueryParam(token, StandardCharsets.UTF_8);

            EmailPort.Result result = emailPort.send(
                    InvitationEmail.compose(email, brandName, inviterName, role.name().toLowerCase(), acceptUrl));

            if (!result.sent()) {
                // Logged without the token: application logs are not a secret store, and this
                // line exists to say delivery failed, not to provide a way around it.
                log.warn("Invitation created but not delivered to {} via {}: {}",
                        email, result.provider(), result.detail());
                return false;
            }

            // The log-only provider reports `sent` so callers are not forced to special-case a
            // local setup — but nothing left the building, and the UI must not claim it did.
            // Asking the port which provider is active is the honest test.
            return !"log".equals(emailPort.provider());
        } catch (RuntimeException e) {
            // EmailPort.send is documented not to throw, but a caller-side failure — a malformed
            // base URL, an encoding problem — must not take down an invitation that already
            // exists in the database.
            log.warn("Invitation created but the notification could not be composed for {}", email, e);
            return false;
        }
    }

    /**
     * Redeems a token for the signed-in user.
     *
     * <p>The invitation's email must match the accepting user's. Without that check, a token
     * forwarded to a third party would let whoever holds it join the account — an invitation is
     * addressed to someone, not merely to whoever presents it.
     */
    public JsonNode accept(String token, UUID userId, String userEmail) {
        JsonNode invitation = tenancyClient.invitationByTokenHash(hash(token));
        if (invitation == null || invitation.get("id") == null) {
            throw new IllegalArgumentException("Invitation not found or already used");
        }
        String invitedEmail = invitation.hasNonNull("email") ? invitation.get("email").asText() : null;
        if (invitedEmail == null || !invitedEmail.equalsIgnoreCase(normalizeEmail(userEmail))) {
            throw new IllegalArgumentException("This invitation was issued to a different email address");
        }
        return tenancyClient.acceptInvitation(UUID.fromString(invitation.get("id").asText()), userId);
    }

    /**
     * Redeems a token for someone who has no account yet, creating the user first.
     *
     * <p>The new user is provisioned into the *inviting* account, never a solo one of their own.
     * That distinction is the whole point of Stage 3: signing up and then being re-parented is what
     * the seed script had to do with raw SQL.
     */
    public JsonNode acceptAsNewUser(String token, String email, String passwordHash, String displayName) {
        JsonNode invitation = tenancyClient.invitationByTokenHash(hash(token));
        if (invitation == null || invitation.get("id") == null) {
            throw new IllegalArgumentException("Invitation not found or already used");
        }
        String invitedEmail = invitation.get("email").asText();
        if (!invitedEmail.equalsIgnoreCase(normalizeEmail(email))) {
            throw new IllegalArgumentException("This invitation was issued to a different email address");
        }

        DaoUserClient.UserRecord user = userClient.findByEmail(invitedEmail).orElseGet(() ->
                userClient.createUser(new DaoUserClient.UserPayload(
                        null, invitedEmail, passwordHash, displayName, "{}", "owner", "free", null, null)));

        return tenancyClient.acceptInvitation(UUID.fromString(invitation.get("id").asText()), user.id());
    }

    public JsonNode list(UUID accountId) {
        return tenancyClient.invitations(accountId);
    }

    public JsonNode revoke(UUID invitationId) {
        return tenancyClient.revokeInvitation(invitationId);
    }

    /** 256 bits from a CSPRNG: an invitation token is a credential and must not be guessable. */
    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

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
        return email == null ? "" : email.trim().toLowerCase();
    }

    /**
     * The invitation plus its token.
     *
     * <p>The token is present only here, on the response to the call that created it. It is never
     * readable again — losing it means re-inviting, which is the correct trade for not storing a
     * live credential.
     */
    /**
     * @param emailDelivered whether a provider accepted the invitation email. False with the
     *                       log-only provider, and false on a delivery failure — in both cases the
     *                       invitation is still valid and the token is still the way in.
     */
    public record InvitationCreated(JsonNode invitation, String token, boolean emailDelivered) {
    }
}
