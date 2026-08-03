package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.identity.infrastructure.DaoUserClient;
import com.influencer.webe.security.AccountRole;
import org.springframework.stereotype.Service;

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

    private final DaoTenancyClient tenancyClient;
    private final DaoUserClient userClient;
    private final SecureRandom random = new SecureRandom();

    public MemberInvitationService(DaoTenancyClient tenancyClient, DaoUserClient userClient) {
        this.tenancyClient = tenancyClient;
        this.userClient = userClient;
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
    public InvitationCreated invite(UUID accountId, UUID invitedBy, String email, String role, UUID brandId) {
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

        return new InvitationCreated(created, token);
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
    public record InvitationCreated(JsonNode invitation, String token) {
    }
}
