package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.identity.infrastructure.ConsentEvidenceWriter;
import com.influencer.webe.identity.infrastructure.DaoConsentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consent is the evidence that a signup was lawful, so the two properties worth pinning down are
 * that a refusal cannot slip through, and that a recording failure cannot destroy the account that
 * was just created.
 */
class ConsentServiceTest {

    private static final String VERSION = "2026-08-11";

    private final RecordingConsentClient client = new RecordingConsentClient();

    /**
     * A writer with no bucket, which is the disabled state: it records nothing and, crucially, does
     * not throw. Consent capture has to keep working with no evidence store configured, which is
     * exactly how local development runs.
     */
    private final ConsentEvidenceWriter evidenceWriter =
            new ConsentEvidenceWriter("", "us-east-1", "", "", "", new ObjectMapper());

    private final ConsentService service = new ConsentService(
            client, evidenceWriter, VERSION, VERSION,
            "https://www.tejdux.com/terms/", "https://www.tejdux.com/privacy/");

    @Nested
    @DisplayName("enforcement")
    class Enforcement {

        @Test
        @DisplayName("only an explicit true is accepted")
        void trueIsAccepted() {
            assertThatCode(() -> service.requireAccepted(true)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent field is a refusal, not a default")
        void nullIsRejected() {
            // The important case. An older client, or a hand-rolled request that simply omits the
            // field, must not be treated as having consented — that is implied consent through the
            // back door, and Article 4(11) requires a clear affirmative act.
            assertThatThrownBy(() -> service.requireAccepted(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must accept");
        }

        @Test
        @DisplayName("an explicit false is a refusal")
        void falseIsRejected() {
            assertThatThrownBy(() -> service.requireAccepted(false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("one acceptance writes both documents, at their own versions")
        void writesTermsAndPrivacy() {
            UUID subject = UUID.randomUUID();

            service.recordSignupConsent(ConsentService.SUBJECT_USER, subject,
                    "owner@example.com", "brand_signup", null, null);

            // Two rows, not one: the documents are revised separately, and a single row could not
            // express "current on the terms, behind on the privacy policy".
            assertThat(client.calls).hasSize(2);
            assertThat(client.calls).extracting(c -> c.consentType)
                    .containsExactlyInAnyOrder(ConsentService.TYPE_TERMS, ConsentService.TYPE_PRIVACY);
            assertThat(client.calls).allSatisfy(c -> {
                assertThat(c.subjectId).isEqualTo(subject);
                assertThat(c.documentVersion).isEqualTo(VERSION);
                assertThat(c.source).isEqualTo("brand_signup");
            });
        }

        @Test
        @DisplayName("a lead is recorded by email, with no subject id")
        void leadHasNoSubjectId() {
            service.recordSignupConsent(ConsentService.SUBJECT_LEAD, null,
                    "creator@example.com", "landing_page_lead", null, "{\"slug\":\"spring\"}");

            assertThat(client.calls).hasSize(2);
            assertThat(client.calls).allSatisfy(c -> {
                assertThat(c.subjectId).isNull();
                assertThat(c.subjectEmail).isEqualTo("creator@example.com");
            });
        }

        @Test
        @DisplayName("a failure to record does not fail the signup")
        void recordingFailureIsSwallowed() {
            // By this point the account exists. Propagating would hand the caller an error for an
            // account that was in fact created — leaving them unable to sign in OR sign up again.
            // A missing record is recoverable from the ERROR log; a phantom failed signup is not.
            client.failNext = true;

            assertThatCode(() -> service.recordSignupConsent(ConsentService.SUBJECT_USER,
                    UUID.randomUUID(), "owner@example.com", "brand_signup", null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the URL is recorded even when no snapshot could be taken")
        void urlIsRecordedWithoutASnapshot() {
            // The evidence writer is disabled here, so there is no hash -- but the address the
            // document is published at is known from configuration and is still worth recording.
            service.recordSignupConsent(ConsentService.SUBJECT_USER, UUID.randomUUID(),
                    "owner@example.com", "brand_signup", null, null);

            assertThat(client.calls).hasSize(2);
            assertThat(client.calls).extracting(c -> c.documentUrl)
                    .containsExactlyInAnyOrder("https://www.tejdux.com/terms/",
                            "https://www.tejdux.com/privacy/");
        }

        @Test
        @DisplayName("without a snapshot the hash is null, never a placeholder")
        void absentSnapshotYieldsNullHash() {
            // The property the whole feature depends on. A fabricated or empty-string hash would
            // read as evidence and fail the moment anyone checked it against the stored bytes;
            // null honestly says "this was captured without a snapshot".
            service.recordSignupConsent(ConsentService.SUBJECT_USER, UUID.randomUUID(),
                    "owner@example.com", "brand_signup", null, null);

            assertThat(client.calls).allSatisfy(c -> {
                assertThat(c.documentSha256).isNull();
                assertThat(c.evidenceS3Key).isNull();
            });
        }

        @Test
        @DisplayName("each document type gets its own URL")
        void urlMatchesDocumentType() {
            service.recordSignupConsent(ConsentService.SUBJECT_USER, UUID.randomUUID(),
                    "owner@example.com", "brand_signup", null, null);

            assertThat(client.calls)
                    .filteredOn(c -> ConsentService.TYPE_TERMS.equals(c.consentType))
                    .singleElement()
                    .satisfies(c -> assertThat(c.documentUrl).isEqualTo("https://www.tejdux.com/terms/"));
            assertThat(client.calls)
                    .filteredOn(c -> ConsentService.TYPE_PRIVACY.equals(c.consentType))
                    .singleElement()
                    .satisfies(c -> assertThat(c.documentUrl).isEqualTo("https://www.tejdux.com/privacy/"));
        }
    }

    /** Captures what would have been written, and can be told to fail. */
    private static final class RecordingConsentClient extends DaoConsentClient {

        private final List<Call> calls = new ArrayList<>();
        private boolean failNext;

        private RecordingConsentClient() {
            super(null);
        }

        /**
         * Overrides the TWELVE-argument overload, deliberately.
         *
         * <p>The nine-argument form delegates to this one, so overriding that instead would leave
         * the real method running underneath and this fake never called -- the tests would pass
         * while asserting nothing about what was written.
         */
        @Override
        public JsonNode record(String subjectType, UUID subjectId, String subjectEmail,
                               String consentType, String documentVersion, String source,
                               String ipAddress, String userAgent, String metadataJson,
                               String documentUrl, String documentSha256, String evidenceS3Key) {
            if (failNext) {
                throw new IllegalStateException("DAO unavailable");
            }
            calls.add(new Call(subjectType, subjectId, subjectEmail, consentType,
                    documentVersion, source, documentUrl, documentSha256, evidenceS3Key));
            return null;
        }

        private record Call(String subjectType, UUID subjectId, String subjectEmail,
                            String consentType, String documentVersion, String source,
                            String documentUrl, String documentSha256, String evidenceS3Key) {
        }
    }
}
