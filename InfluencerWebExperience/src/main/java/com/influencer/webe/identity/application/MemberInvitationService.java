package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.identity.infrastructure.DaoUserClient;
import com.influencer.webe.security.AccountRole;
import com.influencer.webe.shared.application.EmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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
        // FIRST value only. ui-base-url may be a comma-separated list because the same site
        // is served from several hostnames and CORS must allow them all; this builds
        // an invitation link someone must click, which needs exactly one.
        // Verified live 2026-08-22: the whole string produced
        // "https://tejdux.com,https://www.tejdux.com/verify-email?token=..." - not a link.
        this.uiBaseUrl = uiBaseUrl == null ? ""
                : uiBaseUrl.split(",")[0].trim().replaceAll("/+$", "");
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

    /**
     * The invitations that are still outstanding and would each become a member.
     *
     * <p>Only {@code pending} ones count. A revoked or already-accepted invitation is not a future
     * member — an accepted one is a member already, and counting it would charge the seat twice.
     * Expired ones are excluded too: they cannot be redeemed, so holding a seat for them would make
     * an account permanently smaller every time an invitation went unanswered.
     *
     * <p><b>Returns empty rather than zero when the read fails</b>, so the caller chooses the
     * failure policy instead of inheriting one. The two callers want opposite things: a single
     * invite carries on (over-granting by at most one seat is better than refusing a legitimate
     * invitation over a secondary lookup), while a batch must refuse (the same failure would
     * over-grant by the whole batch size). Returning {@code 0} here would silently hand the batch
     * the wrong answer.
     *
     * <p>The count and the address set come from one read on purpose. Two reads could disagree, and
     * a seat count that does not match the addresses it was derived from is worse than either
     * alone.
     */
    public Optional<PendingInvitations> pendingInvitations(UUID accountId) {
        JsonNode invitations;
        try {
            invitations = tenancyClient.invitations(accountId);
        } catch (RuntimeException e) {
            log.warn("Could not read outstanding invitations for account {}: {}", accountId, e.toString());
            return Optional.empty();
        }
        if (invitations == null || !invitations.isArray()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        Set<String> emails = new HashSet<>();
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
                    // An unreadable expiry is treated as still pending — the conservative reading,
                    // since the alternative under-counts and over-grants seats.
                }
            }
            emails.add(normalizeEmail(invitation.path("email").asText("")));
        }
        // Sized from the addresses rather than counted separately: two invitations to the same
        // address cannot both be pending (uq_member_invitations_pending), so they are the same
        // number — and deriving one from the other means they cannot drift apart.
        return Optional.of(new PendingInvitations(Set.copyOf(emails)));
    }

    /**
     * Revokes an invitation the caller's account owns.
     *
     * <p><b>The ownership check is the point.</b> Invitation ids are the only thing this endpoint
     * ever took, and the DAO looks them up by id alone — so before this, anyone holding
     * {@code member:remove} on their own account could revoke any invitation in <em>any</em>
     * account by presenting its id.
     */
    public JsonNode revoke(UUID accountId, UUID invitationId) {
        requireOwnedBy(accountId, invitationId);
        return tenancyClient.revokeInvitation(invitationId);
    }

    /**
     * Issues a fresh token for an existing invitation and returns it once.
     *
     * <p>Exists because a bulk invite deliberately returns no tokens, and while the email provider
     * is {@code log} nothing is delivered either — without this, an invitation created in bulk would
     * be unrecoverable, and the only remedy would be to revoke it and start again.
     *
     * <p>The previous token stops working. That is not a side effect to work around: two live tokens
     * for one invitation would mean revoking the visible one still lets the other in.
     */
    public InvitationCreated resend(UUID accountId, UUID invitationId, String inviterName) {
        JsonNode existing = requireOwnedBy(accountId, invitationId);

        String token = generateToken();
        JsonNode rotated = tenancyClient.rotateInvitationToken(
                invitationId, hash(token), Instant.now().plus(INVITATION_TTL));

        String email = rotated.path("email").asText(existing.path("email").asText(""));
        AccountRole role = AccountRole.parse(rotated.path("role").asText(null))
                .orElse(AccountRole.MARKETER);
        // Brand name is display-only and the composer renders null as "a workspace"; resolving it
        // here would cost a lookup to improve one line of an email that already names the inviter.
        boolean delivered = sendInvitationEmail(email, role, token, null, inviterName);

        return new InvitationCreated(rotated, token, delivered);
    }

    /**
     * Confirms an invitation exists and belongs to {@code accountId}.
     *
     * <p><b>404 rather than 403 when it belongs to someone else.</b> Distinguishing "no such
     * invitation" from "not yours" would confirm that a given id exists in another account, which
     * is itself a disclosure — the id is all an attacker would have.
     */
    private JsonNode requireOwnedBy(UUID accountId, UUID invitationId) {
        JsonNode invitation = tenancyClient.invitation(invitationId);
        if (invitation == null
                || invitation.path("id").asText(null) == null
                || !String.valueOf(accountId).equals(invitation.path("accountId").asText(null))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found");
        }
        return invitation;
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

    /**
     * The outstanding invitations on an account: the addresses, and therefore the seats they hold.
     *
     * <p>Addresses are normalized lowercase, matching the {@code citext} column they came from, so
     * a caller can test membership without repeating the normalization and getting it subtly
     * different.
     */
    public record PendingInvitations(Set<String> emails) {

        public long count() {
            return emails.size();
        }

        public boolean contains(String email) {
            return emails.contains(email == null ? "" : email.trim().toLowerCase());
        }
    }
}
