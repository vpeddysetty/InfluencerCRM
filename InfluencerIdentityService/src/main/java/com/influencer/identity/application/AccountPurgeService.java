package com.influencer.identity.application;

import com.influencer.identity.domain.DeletionRequest;
import com.influencer.identity.domain.FederatedIdentity;
import com.influencer.identity.domain.Membership;
import com.influencer.identity.domain.User;
import com.influencer.identity.infrastructure.DeletionRequestRepository;
import com.influencer.identity.infrastructure.FederatedIdentityRepository;
import com.influencer.identity.infrastructure.MembershipRepository;
import com.influencer.identity.infrastructure.RefreshTokenRepository;
import com.influencer.identity.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes the data-deletion process published at /data-deletion/.
 *
 * <p><b>Requests arrive by email, not through a button.</b> That is the published process, so this
 * service is driven by an operator acting on an attributed request rather than by the subject's
 * own session. {@link DeletionRequest} is the audit trail; this class is what makes its
 * {@code completedAt} truthful.
 *
 * <h2>What this deliberately does not delete</h2>
 *
 * <ul>
 *   <li><b>Consent records.</b> {@code identity.consent_records} has no FK to the user for exactly
 *       this reason (V36): the record that someone consented is what proves the deletion was
 *       lawful. Cascading it would destroy the evidence at the moment it becomes relevant.</li>
 *   <li><b>The account and its brands.</b> The FK from {@code accounts.legacy_user_id} is
 *       {@code ON DELETE SET NULL}, not cascade, and V13 explains why: an agency owner leaving
 *       must not delete the agency and every client brand with it. Deleting a workspace owner is
 *       therefore refused here until ownership moves or the caller states the intent explicitly —
 *       which mirrors the warning the published page already shows the requester.</li>
 *   <li><b>Billing and tax records.</b> Retained under a legal obligation, as both the privacy
 *       policy and the deletion page state.</li>
 * </ul>
 *
 * <h2>What the database does on its own</h2>
 *
 * <p>{@code refresh_tokens} and {@code federated_identities} are {@code ON DELETE CASCADE} on the
 * user, as are {@code memberships} and {@code brand_access}. Deleting the user row removes them.
 * Sessions are revoked explicitly first anyway, so an in-flight token cannot outlive the request
 * by even the length of this transaction.
 */
@Service
public class AccountPurgeService {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeService.class);

    /** Roles that make a membership an ownership stake rather than a seat. */
    private static final String ROLE_OWNER = "owner";

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final FederatedIdentityRepository federatedIdentities;
    private final RefreshTokenRepository refreshTokens;
    private final DeletionRequestRepository deletionRequests;

    public AccountPurgeService(UserRepository users,
                               MembershipRepository memberships,
                               FederatedIdentityRepository federatedIdentities,
                               RefreshTokenRepository refreshTokens,
                               DeletionRequestRepository deletionRequests) {
        this.users = users;
        this.memberships = memberships;
        this.federatedIdentities = federatedIdentities;
        this.refreshTokens = refreshTokens;
        this.deletionRequests = deletionRequests;
    }

    /**
     * Records a request received by email.
     *
     * <p>Recorded before anything is deleted, so a request that later fails or is refused still
     * leaves a trace. The published page promises we confirm scope before acting; this is the row
     * that confirmation refers to.
     */
    @Transactional
    public DeletionRequest record(String email, String scope, String provider) {
        DeletionRequest request = new DeletionRequest();
        request.setSubjectEmail(email);
        request.setScope(scope);
        request.setProvider(provider == null ? null : provider.toLowerCase(Locale.ROOT));
        // Resolve the user now while the row still exists. Null is legitimate: a request may name
        // an address that never had an account, and refusing it is still an outcome worth keeping.
        users.findByEmailIgnoreCase(email).ifPresent(u -> request.setSubjectUserId(u.getId()));
        return deletionRequests.save(request);
    }

    /** Marks a request acknowledged, backing the "within 5 business days" promise. */
    @Transactional
    public DeletionRequest acknowledge(UUID requestId) {
        DeletionRequest request = deletionRequests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such deletion request: " + requestId));
        if (request.getAcknowledgedAt() == null) {
            request.setAcknowledgedAt(Instant.now());
        }
        return deletionRequests.save(request);
    }

    /** Records that a request will not be actioned, and why. A refusal is an outcome, not an error. */
    @Transactional
    public DeletionRequest refuse(UUID requestId, String reason) {
        DeletionRequest request = deletionRequests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such deletion request: " + requestId));
        request.setRefusedAt(Instant.now());
        request.setRefusedReason(reason);
        return deletionRequests.save(request);
    }

    /**
     * Deletes only the data obtained from one federated provider, leaving the account intact.
     *
     * <p>/data-deletion/ section 3.2 promises this as a distinct outcome from account deletion,
     * and it is the one Meta's reviewers exercise. It is refused when the provider link is the
     * user's last credential, because silently removing it would lock them out — which is the
     * opposite of what "keep my account, drop the connection" asks for. {@link CredentialPolicy}
     * owns that rule; it spans the password column and this table, so neither can decide alone.
     */
    @Transactional
    public DeletionRequest purgeProviderData(UUID requestId) {
        DeletionRequest request = deletionRequests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such deletion request: " + requestId));

        if (!DeletionRequest.SCOPE_PROVIDER.equals(request.getScope())) {
            throw new IllegalStateException("Request " + requestId + " is not provider-scoped");
        }
        UUID userId = request.getSubjectUserId();
        if (userId == null) {
            return refuse(requestId, "No account found for " + request.getSubjectEmail());
        }

        String provider = request.getProvider();
        List<FederatedIdentity> links = federatedIdentities.findByUserId(userId).stream()
                .filter(f -> provider.equalsIgnoreCase(f.getProvider()))
                .toList();

        if (links.isEmpty()) {
            request.setCompletedAt(Instant.now());
            request.setOutcomeNote("No " + provider + " connection was present; nothing to remove.");
            return deletionRequests.save(request);
        }

        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User vanished mid-request: " + userId));
        boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
        long otherLinks = federatedIdentities.countByUserId(userId) - links.size();

        if (!hasPassword && otherLinks == 0) {
            return refuse(requestId, "Removing the " + provider + " connection would leave the account "
                    + "with no way to sign in. Set a password first, or request full account deletion.");
        }

        federatedIdentities.deleteAll(links);
        // Sessions established through that provider must not outlive it.
        refreshTokens.deleteAllForUser(userId);

        request.setCompletedAt(Instant.now());
        request.setOutcomeNote("Removed " + links.size() + " " + provider
                + " connection(s), the stored provider identifier and profile details, and revoked sessions.");
        log.info("Purged {} provider data for user {} under request {}", provider, userId, requestId);
        return deletionRequests.save(request);
    }

    /**
     * Deletes a user and the personal data attached to them.
     *
     * @param force proceed even though the user owns a workspace. The published page says we will
     *              tell an owner what is in scope and ask them to confirm; this flag is that
     *              confirmation, and it is never inferred. Defaulting it to true would let one
     *              mis-attributed email delete a whole agency.
     */
    @Transactional
    public DeletionRequest purgeAccount(UUID requestId, boolean force) {
        DeletionRequest request = deletionRequests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such deletion request: " + requestId));

        if (!DeletionRequest.SCOPE_ACCOUNT.equals(request.getScope())) {
            throw new IllegalStateException("Request " + requestId + " is not account-scoped");
        }
        UUID userId = request.getSubjectUserId();
        if (userId == null) {
            return refuse(requestId, "No account found for " + request.getSubjectEmail());
        }

        Optional<User> maybeUser = users.findById(userId);
        if (maybeUser.isEmpty()) {
            // Already gone. Completing rather than refusing: the requested end state holds.
            request.setCompletedAt(Instant.now());
            request.setOutcomeNote("User row was already absent; no further action required.");
            return deletionRequests.save(request);
        }

        List<Membership> owned = memberships.findByUserId(userId).stream()
                .filter(m -> ROLE_OWNER.equalsIgnoreCase(m.getRole()))
                .toList();

        if (!owned.isEmpty() && !force) {
            return refuse(requestId, "This account owns " + owned.size() + " workspace(s). Deleting it "
                    + "would remove data other members rely on. Transfer ownership first, or confirm "
                    + "the cascade explicitly.");
        }

        // Revoke first: a token that survives even briefly past this point is a session on a
        // deleted account.
        refreshTokens.deleteAllForUser(userId);

        int links = federatedIdentities.findByUserId(userId).size();

        // memberships, brand_access, refresh_tokens and federated_identities are ON DELETE CASCADE
        // on this row. accounts.legacy_user_id is ON DELETE SET NULL, so the workspace survives by
        // design (V13). consent_records has no FK at all and is untouched, on purpose (V36).
        users.deleteById(userId);

        // The FK nulls this column, but only for rows the database can see. Setting it here keeps
        // the in-memory entity honest for the response and the log line below.
        request.setSubjectUserId(null);
        request.setCompletedAt(Instant.now());
        request.setOutcomeNote("Deleted the user record, " + links + " federated connection(s), "
                + "memberships and sessions. Consent records and billing records are retained as "
                + "required. Backups rotate within 7 days."
                + (owned.isEmpty() ? "" : " Owned workspaces were included at the requester's confirmation."));

        log.info("Purged account for user {} under request {} (force={})", userId, requestId, force);
        return deletionRequests.save(request);
    }
}
