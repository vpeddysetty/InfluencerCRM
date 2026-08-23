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
 * The rules that keep consent evidence honest.
 *
 * <p>Every one of these is a property that, if broken, produces a record that still LOOKS like
 * evidence. That is the failure mode worth guarding: a missing hash announces itself, while a wrong
 * or invented one is discovered only when somebody checks it, which is the worst possible moment.
 *
 * <p>These read the source rather than exercising a wired service, following {@link SignInConsentTest}
 * — constructing a real {@code ConsentService} needs the DAO client and the whole Spring context,
 * and the properties being pinned here are structural.
 */
class ConsentEvidenceCaptureTest {

    private String read(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/influencer/webe", relativePath),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a failed snapshot records consent WITHOUT a hash rather than inventing one")
    void missingSnapshotMeansNoHash() throws IOException {
        String service = read("identity/application/ConsentService.java");

        // The whole point. If the document could not be fetched or stored, the consent is still
        // recorded -- losing the evidence must not lose the consent -- but the hash column stays
        // null. A placeholder would be indistinguishable from a real capture and unverifiable.
        assertTrue(service.contains("snapshot == null ? null : snapshot.sha256()"),
                "an absent snapshot must yield a null hash, never a substitute");
        assertTrue(service.contains("snapshot == null ? null : snapshot.s3Key()"),
                "an absent snapshot must yield a null key");
    }

    @Test
    @DisplayName("the document is snapshotted at startup, not on the signup path")
    void snapshotHappensAtStartup() throws IOException {
        String service = read("identity/application/ConsentService.java");

        // A per-signup fetch would put an external HTTP call in front of every registration and
        // write identical bytes thousands of times into a bucket where nothing can be deleted for
        // seven years. The document changes when republished, not when someone signs up.
        assertTrue(service.contains("@EventListener(ApplicationReadyEvent.class)"),
                "snapshots must be taken once at startup");
        assertTrue(service.contains("snapshotPublishedDocuments()"));
    }

    @Test
    @DisplayName("a snapshot failure never stops the application or the signup")
    void snapshotFailureIsNotFatal() throws IOException {
        String service = read("identity/application/ConsentService.java");

        // An app that refuses to boot because a marketing page is briefly unreachable turns a
        // cosmetic outage into a total one.
        assertTrue(service.contains("catch (Exception e)"),
                "snapshot must swallow its failures");
        assertTrue(service.contains("Consent will be recorded without a "),
                "and must say plainly what the consequence is");
    }

    @Test
    @DisplayName("the receipt is written after the database row, never instead of it")
    void receiptFollowsTheAuthoritativeWrite() throws IOException {
        String service = read("identity/application/ConsentService.java");

        int dbWrite = service.indexOf("consentClient.record(");
        int receipt = service.indexOf("evidenceWriter.writeReceipt(");

        assertTrue(dbWrite > 0 && receipt > 0, "both writes must be present");
        assertTrue(dbWrite < receipt,
                "the Postgres row is authoritative; the S3 receipt is a convenience for an auditor");
    }

    @Test
    @DisplayName("the URL is recorded alongside the version, and both are configurable together")
    void urlAccompaniesVersion() throws IOException {
        String service = read("identity/application/ConsentService.java");

        // A version string cannot be resolved back to text once a document moves, so the address is
        // part of the record and is bumped in the same change that republishes the document.
        assertTrue(service.contains("web-experience.legal.terms-url"));
        assertTrue(service.contains("web-experience.legal.privacy-url"));
        assertTrue(service.contains("urlFor(consentType)"),
                "the recorded URL must be chosen per document type");
    }

    @Test
    @DisplayName("the DAO refuses a malformed hash instead of storing it")
    void daoValidatesTheHash() throws IOException {
        String controller = Files.readString(
                Path.of("../InfluencerDAO/src/main/java/com/influencer/dao/identity/api/ConsentController.java"),
                StandardCharsets.UTF_8);

        assertTrue(controller.contains("SHA256_HEX"), "the DAO must validate the hash format");
        assertTrue(controller.contains("64 lowercase hex characters"),
                "and must say what it expected");
        // Optional, though: absent is a legitimate state meaning "captured before evidence capture".
        assertTrue(controller.contains("sha256 != null && !sha256.isBlank()"),
                "an absent hash must remain acceptable");
    }

    @Test
    @DisplayName("consent evidence is never allowed to fail a signup")
    void evidenceNeverBreaksSignup() throws IOException {
        String writer = read("identity/infrastructure/ConsentEvidenceWriter.java");

        // By the time this runs the account exists. Throwing would hand the caller an error for an
        // account that was in fact created.
        assertTrue(writer.contains("Never rethrown"),
                "the receipt path must document that it swallows failures");
        assertFalse(writer.contains("throw new RuntimeException"),
                "the writer must not propagate failures to the signup path");
    }
}
