package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties of the deletion workflow that, if broken, destroy data that should not have been
 * destroyed — or fail to destroy data that should have been.
 *
 * <p>These read the source rather than exercising a wired service, following
 * {@link SignInConsentTest} and {@link ConsentEvidenceCaptureTest}: the service needs the DAO
 * client, an email provider and the Spring context, and the properties pinned here are structural.
 * The behavioural rules live in {@link DeletionRequestPolicyTest}, which exercises them directly.
 */
class DeletionWorkflowTest {

    private String read(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/influencer/webe", relativePath),
                StandardCharsets.UTF_8);
    }

    private String dao(String relativePath) throws IOException {
        return Files.readString(Path.of("../InfluencerDAO/src/main/java/com/influencer/dao", relativePath),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("intake never deletes anything")
    void intakeDoesNotDelete() throws IOException {
        String service = read("identity/application/DeletionRequestService.java");
        int intakeStart = service.indexOf("public UUID intake(");
        int approveStart = service.indexOf("public Outcome approve(");
        assertTrue(intakeStart > 0 && approveStart > intakeStart);

        String intake = service.substring(intakeStart, approveStart);
        // The rule the whole design serves. An inbound email is a claim, not an authorisation:
        // sender addresses are forged trivially, so a purge on receipt would let anyone destroy
        // anyone else's account.
        assertFalse(intake.contains("deleteUser"), "intake must not delete an account");
        assertFalse(intake.contains("purge("), "intake must not purge");
        assertTrue(intake.contains("notifyOperator"), "intake must notify a human");
    }

    @Test
    @DisplayName("the purge runs only after the approval checks")
    void purgeFollowsEveryRefusal() throws IOException {
        String service = read("identity/application/DeletionRequestService.java");

        int usableCheck = service.indexOf("DeletionRequestPolicy.approvalUsable");
        int ownerGuard = service.indexOf("ownsWorkspace(subjectUserId)");
        int purge = service.indexOf("String note = purge(");

        assertTrue(usableCheck > 0 && ownerGuard > 0 && purge > 0, "all three must be present");
        assertTrue(usableCheck < purge, "an unusable link must be refused before deleting");
        assertTrue(ownerGuard < purge, "workspace ownership must be checked before deleting");
    }

    @Test
    @DisplayName("an ownership check that fails is treated as ownership")
    void ownershipCheckFailsClosed() throws IOException {
        String service = read("identity/application/DeletionRequestService.java");
        int guard = service.indexOf("private boolean ownsWorkspace(");
        String body = service.substring(guard, guard + 700);

        // Fail closed. If ownership cannot be established, refusing costs a manual review;
        // proceeding could erase a workspace of other people's records.
        assertTrue(body.contains("return true;"), "an unreadable ownership check must refuse");
        assertTrue(body.contains("refusing"), "and must say so");
    }

    @Test
    @DisplayName("ownership means OWNER, not merely account-wide access")
    void ownershipIsNotAccessibility() throws IOException {
        String repository = dao("identity/infrastructure/BrandRepository.java");
        int owned = repository.indexOf("findOwnedBrandNames");
        assertTrue(owned > 0, "the ownership query must exist");

        String query = repository.substring(Math.max(0, owned - 900), owned);
        // ADMIN and FINANCE reach every brand in the account and own none. Refusing their deletion
        // request would be wrong, so this must not reuse findAccessibleBrands' role list.
        assertTrue(query.contains("m.role = 'OWNER'"), "ownership must be OWNER only");
        assertFalse(query.contains("'OWNER','ADMIN','FINANCE'"),
                "the account-wide role list is accessibility, not ownership");
    }

    @Test
    @DisplayName("the DAO refuses to complete a request that was never approved")
    void daoEnforcesApprovalBeforeCompletion() throws IOException {
        String controller = dao("identity/api/DeletionRequestController.java");
        assertTrue(controller.contains("cannot be completed before it is approved"),
                "the DAO must refuse completion without approval");
        // And the same rule is a CHECK constraint in V40, so a bug in either layer is still caught.
        String migration = Files.readString(
                Path.of("../schema/flyway/V40__deletion_request_workflow.sql"), StandardCharsets.UTF_8);
        assertTrue(migration.contains("completed_at is null or approved_at is not null"),
                "the database must enforce it too");
    }

    @Test
    @DisplayName("a redelivered notification does not produce a second approval link")
    void redeliveryIsIdempotent() throws IOException {
        String daoController = dao("identity/api/DeletionRequestController.java");
        // SNS delivers at least once. Two rows would mean two links, each authorising an
        // irreversible act on the same person.
        assertTrue(daoController.contains("existsByRawMessageS3Key"),
                "the DAO must recognise a message it has already recorded");

        String service = read("identity/application/DeletionRequestService.java");
        assertTrue(service.contains("was already recorded; not re-notifying"),
                "a redelivery must not send a second approval link");
    }

    @Test
    @DisplayName("the audit trail has no delete path anywhere")
    void auditTrailCannotBeErased() throws IOException {
        String repository = dao("identity/infrastructure/DeletionRequestRepository.java");
        assertTrue(repository.contains("No delete method"),
                "the repository must document why it has no delete");

        String daoController = dao("identity/api/DeletionRequestController.java");
        assertFalse(daoController.contains("@DeleteMapping"),
                "an erasure request does not erase the proof it was honoured");
    }

    @Test
    @DisplayName("a failed notification never rolls back a deletion that happened")
    void notificationFailureDoesNotUndoTheWork() throws IOException {
        String service = read("identity/application/DeletionRequestService.java");
        // Every bookkeeping write runs after the thing it describes already happened, and none of
        // them can be allowed to report failure for work that succeeded.
        assertTrue(service.contains("recordQuietly"),
                "bookkeeping writes must be isolated from the caller");
        assertTrue(service.contains("must not be allowed to fail the caller"),
                "and the reason must be written down");
    }

    @Test
    @DisplayName("the approval endpoint escapes what it renders")
    void approvalPageEscapesOutput() throws IOException {
        String controller = read("identity/api/DeletionRequestController.java");
        // The outcome note carries an email address that came from an inbound message. Rendering
        // it into HTML unescaped would let a sender put markup on the operator's screen.
        assertTrue(controller.contains("escape(detail)"), "the note must be escaped");
        assertTrue(controller.contains("&lt;"), "escaping must handle angle brackets");
    }

    @Test
    @DisplayName("the SNS endpoint refuses to confirm a subscription to a stranger's topic")
    void subscriptionConfirmationIsHostChecked() throws IOException {
        String controller = read("identity/api/DeletionRequestController.java");
        // Confirming an arbitrary SubscribeURL is how a stranger gets this service to fetch a URL
        // of their choosing, and subscribes us to their topic.
        assertTrue(controller.contains("host.startsWith(\"sns.\")"),
                "only an SNS host may be confirmed");
        assertTrue(controller.contains("Refusing to confirm a subscription"),
                "and a refusal must be logged");
    }

    @Test
    @DisplayName("both public endpoints are declared public deliberately, with reasons")
    void publicPathsAreJustified() throws IOException {
        String security = read("security/SecurityConfig.java");
        assertTrue(security.contains("\"/api/deletion-requests/approve\""),
                "the approval link must be reachable without a session");
        assertTrue(security.contains("\"/api/deletion-requests\""),
                "SNS cannot authenticate");
        // The webhook records and notifies; it deletes nothing. That is what makes it safe to
        // expose, and the comment must say so.
        assertTrue(security.contains("it deletes\n            // nothing"),
                "the reason the webhook is safe to expose must be written down");
    }
}
