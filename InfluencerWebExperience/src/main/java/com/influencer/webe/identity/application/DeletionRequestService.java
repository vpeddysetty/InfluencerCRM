package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoDeletionRequestClient;
import com.influencer.webe.identity.infrastructure.DaoUserClient;
import com.influencer.webe.shared.application.EmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The deletion workflow: record what arrived, ask a human, then act.
 *
 * <p>{@link DeletionRequestPolicy} holds the rules; this does the I/O they govern.
 *
 * <h2>Why a human is in the loop at all</h2>
 *
 * <p>Requests arrive by email and sender addresses are trivially forged. An automated purge would
 * let anyone destroy anyone else's account by sending a message that claims to be from them, and
 * deletion is not recoverable. So intake RECORDS and NOTIFIES, and a separate explicit approval
 * AUTHORISES. V40 encodes the same rule as a CHECK constraint, so a bug here cannot complete an
 * unapproved request.
 *
 * <h2>What is deliberately not automated</h2>
 *
 * <p>Nothing here decides that a request is illegitimate and discards it. Every message that
 * arrives is recorded and notified, even one that does not read like a deletion request, because a
 * rights request dropped on the floor is invisible to everyone including the person who sent it.
 * {@link DeletionRequestPolicy#readsAsDeletionRequest} only shapes the notification.
 */
@Service
public class DeletionRequestService {

    private static final Logger log = LoggerFactory.getLogger(DeletionRequestService.class);

    private final DaoDeletionRequestClient client;
    private final DaoUserClient userClient;
    private final EmailPort emailPort;
    private final String operatorEmail;
    private final String publicBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public DeletionRequestService(
            DaoDeletionRequestClient client,
            DaoUserClient userClient,
            EmailPort emailPort,
            @Value("${web-experience.deletion.operator-email:vijay.peddysetty@kmpsglobal.com}")
            String operatorEmail,
            @Value("${web-experience.public-base-url:https://api.tejdux.com}") String publicBaseUrl) {
        this.client = client;
        this.userClient = userClient;
        this.emailPort = emailPort;
        this.operatorEmail = operatorEmail;
        this.publicBaseUrl = publicBaseUrl;
    }

    // -----------------------------------------------------------------------
    // 1. Intake
    // -----------------------------------------------------------------------

    /**
     * Records an arriving request and notifies the operator.
     *
     * @return the request id, or null when nothing could be recorded
     */
    public UUID intake(String fromHeader, String subject, String body, String rawMessageS3Key) {
        String address = DeletionRequestPolicy.addressFrom(fromHeader);
        if (address == null) {
            // Nothing to attribute it to and no one to reply to. Notified anyway: a malformed
            // sender on a genuine request is exactly the case a human needs to see.
            log.error("Deletion request with an unreadable From header, message {}", rawMessageS3Key);
            notifyOperatorOfUnattributable(fromHeader, subject, rawMessageS3Key);
            return null;
        }

        String scope = DeletionRequestPolicy.scopeFor(subject, body);
        String provider = DeletionRequestPolicy.providerNamedIn(subject, body);
        UUID subjectUserId = lookupUserId(address);

        String token = newToken();
        Instant expires = Instant.now().plus(DeletionRequestPolicy.APPROVAL_TTL);

        JsonNode saved;
        try {
            saved = client.record(address, subjectUserId, scope, provider, rawMessageS3Key,
                    DeletionRequestPolicy.SOURCE_EMAIL, hash(token), expires);
        } catch (RuntimeException e) {
            // The request exists in the world whether or not we could store it. Log loudly and
            // still tell the operator, so it is not lost because a database was briefly down.
            log.error("Could not record deletion request from {} ({}): {}",
                    address, rawMessageS3Key, e.toString());
            notifyOperatorOfUnattributable(fromHeader, subject, rawMessageS3Key);
            return null;
        }

        UUID id = idOf(saved);
        if (id == null) {
            log.error("Deletion request recorded but no id came back, message {}", rawMessageS3Key);
            return null;
        }

        // A redelivery returns the existing row, which already has its own token. Sending a second
        // approval link for it would be a second authorisation for one request.
        boolean isNew = saved.hasNonNull("approvalTokenHash")
                && hash(token).equals(saved.get("approvalTokenHash").asText());
        if (!isNew) {
            log.info("Deletion request {} was already recorded; not re-notifying", id);
            return id;
        }

        notifyOperator(id, address, subject, body, scope, provider, subjectUserId, token, expires);
        return id;
    }

    // -----------------------------------------------------------------------
    // 2. Approval
    // -----------------------------------------------------------------------

    /**
     * Redeems an approval link and carries out the deletion.
     *
     * <p>Everything that can refuse does so before anything is destroyed: an unknown token, an
     * expired one, one already used, and a requester who owns a workspace.
     */
    public Outcome approve(String token) {
        if (token == null || token.isBlank()) {
            throw invalidApproval();
        }
        JsonNode request;
        try {
            request = client.byApprovalTokenHash(hash(token));
        } catch (RuntimeException e) {
            throw invalidApproval();
        }
        if (request == null || request.isNull()) {
            throw invalidApproval();
        }

        UUID id = idOf(request);
        Instant expiresAt = instantOf(request, "approvalExpiresAt");
        Instant approvedAt = instantOf(request, "approvedAt");

        if (!DeletionRequestPolicy.approvalUsable(expiresAt, approvedAt, Instant.now())) {
            // Deliberately one message for expired and already-used. Both mean "this link will not
            // work again", and distinguishing them tells a holder of a stale link which case they
            // are in without helping anyone legitimate.
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This approval link is no longer valid. Links work once and expire after "
                    + DeletionRequestPolicy.APPROVAL_TTL.toDays() + " days.");
        }

        String email = textOf(request, "subjectEmail");
        UUID subjectUserId = uuidOf(request, "subjectUserId");
        String scope = textOf(request, "scope");

        // THE OWNER GUARD. Deleting a workspace owner destroys creator records the BRAND is
        // controller for -- other people's personal data held under the brand's legal basis, not
        // the requester's. One person's erasure request is not authority to erase a third party's
        // records. V37 anticipated this with refused_at/refused_reason.
        if (DeletionRequestPolicy.SCOPE_ACCOUNT.equals(scope) && subjectUserId != null
                && ownsWorkspace(subjectUserId)) {
            client.approve(id, operatorEmail, Instant.now());
            client.refuse(id, DeletionRequestPolicy.REFUSED_OWNS_WORKSPACE, Instant.now());
            notifyRequesterOfRefusal(id, email);
            log.info("Deletion request {} refused: requester owns a workspace", id);
            return new Outcome(id, false, DeletionRequestPolicy.REFUSED_OWNS_WORKSPACE);
        }

        client.approve(id, operatorEmail, Instant.now());

        String note = purge(subjectUserId, email, scope, textOf(request, "provider"));
        client.complete(id, note, Instant.now());

        notifyRequesterOfCompletion(id, email, note);
        notifyOperatorOfCompletion(id, email, note);

        log.info("Deletion request {} completed: {}", id, note);
        return new Outcome(id, true, note);
    }

    /** What happened, for the page the operator lands on. */
    public record Outcome(UUID requestId, boolean deleted, String note) { }

    // -----------------------------------------------------------------------
    // 3. The purge itself
    // -----------------------------------------------------------------------

    /**
     * Removes the data and returns a note of what went.
     *
     * <p>Account scope deletes the user row. The schema cascades from there — sessions, federated
     * identities, verifications — while consent records and this request deliberately survive,
     * because they are the evidence the deletion was lawful.
     */
    private String purge(UUID subjectUserId, String email, String scope, String provider) {
        if (subjectUserId == null) {
            // Attributable to no account. Recorded as complete rather than refused: there was
            // nothing to delete, which is a different fact from refusing to delete something.
            return "No account was found for " + email + "; nothing to delete.";
        }
        if (DeletionRequestPolicy.SCOPE_PROVIDER.equals(scope)) {
            try {
                userClient.deleteFederatedIdentity(subjectUserId, provider);
                return "Removed the " + provider + " connection and the profile data obtained from it. "
                        + "The account itself was left intact, as requested.";
            } catch (RuntimeException e) {
                log.error("Provider-scoped purge failed for {} ({}): {}", subjectUserId, provider, e.toString());
                return "FAILED to remove the " + provider + " connection: " + e;
            }
        }
        try {
            userClient.deleteUser(subjectUserId);
            return "Deleted the account for " + email + " and the data cascading from it. "
                    + "Consent and deletion records are retained as evidence that this was lawful, "
                    + "under the legal-records basis described in the privacy policy.";
        } catch (RuntimeException e) {
            log.error("Purge failed for {}: {}", subjectUserId, e.toString());
            return "FAILED to delete the account: " + e;
        }
    }

    private boolean ownsWorkspace(UUID userId) {
        try {
            return userClient.ownsAnyBrand(userId);
        } catch (RuntimeException e) {
            // Fail closed. If ownership cannot be established, refusing costs a manual review;
            // proceeding could destroy a workspace of other people's records.
            log.error("Could not determine workspace ownership for {}; refusing: {}", userId, e.toString());
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------------
    //
    // None of these can fail the workflow. A purge that succeeded and an email that did not is a
    // notification problem; rolling back would be impossible anyway, since the data is already gone.

    private void notifyOperator(UUID id, String address, String subject, String body, String scope,
                                String provider, UUID subjectUserId, String token, Instant expires) {
        String approvalUrl = publicBaseUrl + "/api/deletion-requests/approve?token=" + token;
        String looksGenuine = DeletionRequestPolicy.readsAsDeletionRequest(subject, body)
                ? "Yes" : "NO - this may not be a deletion request. Read it before approving.";

        String text = """
                A data deletion request arrived.

                From:        %s
                Subject:     %s
                Scope:       %s%s
                Account:     %s
                Reads as a deletion request: %s

                --- message ---
                %s
                --- end ---

                NOTHING HAS BEEN DELETED. To approve and carry it out:

                %s

                This link works once and expires %s.

                If this is not a genuine request, do nothing. The link will expire on its own and
                the request stays on the open list at /api/deletion-requests/open.
                """.formatted(
                        address,
                        subject == null ? "(none)" : subject,
                        scope,
                        provider == null ? "" : " (" + provider + ")",
                        subjectUserId == null ? "no matching account" : subjectUserId.toString(),
                        looksGenuine,
                        body == null ? "(empty)" : body.strip(),
                        approvalUrl,
                        expires);

        // EmailPort.send does not throw; it reports through Result. The approval link is in this
        // message, so a failure means nobody can approve the request until a new link is issued --
        // recoverable, because the request stays on the open list, but it must be loud.
        EmailPort.Result result = emailPort.send(
                EmailPort.Message.text(operatorEmail, "Deletion request from " + address, text));
        if (result.sent()) {
            recordQuietly(() -> client.markOperatorNotified(id, Instant.now()),
                    "mark operator notified for " + id);
        } else {
            log.error("Could not notify the operator about deletion request {}: {}", id, result.detail());
        }
    }

    private void notifyOperatorOfUnattributable(String fromHeader, String subject, String key) {
        EmailPort.Result result = emailPort.send(EmailPort.Message.text(
                operatorEmail, "Deletion request that could not be recorded",
                """
                A message arrived at the deletion address that could not be recorded.

                From header: %s
                Subject:     %s
                Raw message: %s

                It is in the intake bucket for 90 days. Nothing was recorded in the database, so
                this email is the only notice of it.
                """.formatted(fromHeader, subject, key)));
        if (!result.sent()) {
            // Both the database write and the email failed. Nothing but this log records that a
            // request arrived at all, which is why it is ERROR and names the object key.
            log.error("Could not notify the operator about unrecordable request {}: {}",
                    key, result.detail());
        }
    }

    private void notifyRequesterOfCompletion(UUID id, String email, String note) {
        EmailPort.Result result = emailPort.send(EmailPort.Message.text(
                email, "Your data has been deleted",
                """
                We have carried out your deletion request.

                %s

                Reference: %s

                If you believe this was done in error, reply to this message. Note that the
                deletion itself cannot be undone.
                """.formatted(note, id)));
        if (result.sent()) {
            recordQuietly(() -> client.markRequesterNotified(id, Instant.now()),
                    "mark requester notified for " + id);
        } else {
            // Expected while SES is in the sandbox, which only permits verified addresses. The
            // deletion still happened; requester_notified_at stays null, which is the honest
            // record that they were not told.
            log.error("Could not notify the requester about deletion {}: {}", id, result.detail());
        }
    }

    private void notifyRequesterOfRefusal(UUID id, String email) {
        EmailPort.Result result = emailPort.send(EmailPort.Message.text(
                email, "About your deletion request",
                """
                We cannot carry out your request as it stands.

                %s

                Reference: %s

                Reply to this message and we will help.
                """.formatted(DeletionRequestPolicy.REFUSED_OWNS_WORKSPACE, id)));
        if (result.sent()) {
            recordQuietly(() -> client.markRequesterNotified(id, Instant.now()),
                    "mark requester notified for " + id);
        } else {
            log.error("Could not notify the requester about refusal {}: {}", id, result.detail());
        }
    }

    private void notifyOperatorOfCompletion(UUID id, String email, String note) {
        EmailPort.Result result = emailPort.send(EmailPort.Message.text(
                operatorEmail, "Deletion completed for " + email,
                "Request %s has been carried out.%n%n%s%n".formatted(id, note)));
        if (!result.sent()) {
            log.error("Could not confirm deletion {} to the operator: {}", id, result.detail());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID lookupUserId(String email) {
        try {
            return userClient.findByEmail(email)
                    .map(DaoUserClient.UserRecord::id)
                    .orElse(null);
        } catch (RuntimeException e) {
            // Not finding an account is a normal outcome -- someone may write from an address they
            // never registered -- and must not stop the request being recorded.
            log.info("No account found for deletion request from {}: {}", email, e.toString());
            return null;
        }
    }

    /**
     * Runs a bookkeeping write that must not be allowed to fail the caller.
     *
     * <p>Every use is a "we sent the email" timestamp recorded AFTER the thing it describes already
     * happened. Throwing here would report failure for a deletion that was carried out, or for a
     * notification that was delivered -- and neither can be taken back by an exception.
     */
    private void recordQuietly(Runnable write, String what) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.error("Could not {}: {}", what, e.toString());
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    private static ResponseStatusException invalidApproval() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "This approval link is not valid.");
    }

    private static UUID idOf(JsonNode node) {
        return uuidOf(node, "id");
    }

    private static UUID uuidOf(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        try {
            return UUID.fromString(node.get(field).asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String textOf(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.get(field).asText();
    }

    private static Instant instantOf(JsonNode node, String field) {
        String value = textOf(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
