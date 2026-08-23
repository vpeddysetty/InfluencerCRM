package com.influencer.webe.identity.application;

import com.influencer.webe.identity.infrastructure.DaoConsentClient;
import com.influencer.webe.identity.infrastructure.ConsentEvidenceWriter;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Captures acceptance of the terms of service and the privacy policy.
 *
 * <p>Two responsibilities, deliberately together: rejecting a signup that did not consent, and
 * recording the one that did. Splitting them would let a caller do the second without the first,
 * which is the failure this exists to prevent.
 *
 * <h2>Why the rejection is here and not only in the browser</h2>
 *
 * <p>A checkbox is a UI affordance, not a control: every signup endpoint is public and reachable
 * with curl. GDPR Article 7(1) requires the controller to demonstrate consent, and a record written
 * only when the client chose to send one demonstrates nothing. So {@link #requireAccepted} runs
 * server-side on every surface and the write follows it.
 *
 * <h2>Ordering</h2>
 *
 * <p>The check runs BEFORE the account is created, the record is written AFTER. The check must come
 * first so a refusal creates nothing; the write must come second because a consent record needs the
 * subject id, which does not exist until the account does. The gap between them is the one failure
 * mode worth naming — see {@link #recordSignupConsent}.
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    public static final String SUBJECT_USER = "user";
    public static final String SUBJECT_CREATOR_IDENTITY = "creator_identity";
    public static final String SUBJECT_LEAD = "lead";

    public static final String TYPE_TERMS = "terms_of_service";
    public static final String TYPE_PRIVACY = "privacy_policy";

    private final DaoConsentClient consentClient;

    /**
     * The versions currently published at /terms/ and /privacy/.
     *
     * <p>Configuration rather than a constant because the documents are revised independently of a
     * code release, and the recorded version must be what the person was actually shown. When a
     * policy is republished, bump the matching property in the same change that publishes it.
     */
    private final String termsVersion;
    private final String privacyVersion;

    /**
     * The addresses those versions are published at.
     *
     * <p>Paired with the version properties above and bumped in the same change, because the record
     * has to say where the person read the document as well as which revision it was. A version
     * without a URL cannot be resolved back to text once a document moves.
     */
    private final String termsUrl;
    private final String privacyUrl;

    private final ConsentEvidenceWriter evidenceWriter;

    /**
     * The snapshot taken for each document at startup, keyed by consent type.
     *
     * <p>Populated once by {@link #snapshotPublishedDocuments()} and read on every signup. Empty
     * when no evidence bucket is configured, or when a document could not be fetched -- in which
     * case consent is still recorded, just without the hash. Losing the evidence must not lose the
     * consent.
     */
    private final Map<String, ConsentEvidenceWriter.Snapshot> snapshots = new ConcurrentHashMap<>();

    public ConsentService(DaoConsentClient consentClient,
                          ConsentEvidenceWriter evidenceWriter,
                          @Value("${web-experience.legal.terms-version:2026-08-11}") String termsVersion,
                          @Value("${web-experience.legal.privacy-version:2026-08-11}") String privacyVersion,
                          @Value("${web-experience.legal.terms-url:https://www.tejdux.com/terms/}") String termsUrl,
                          @Value("${web-experience.legal.privacy-url:https://www.tejdux.com/privacy/}") String privacyUrl) {
        this.consentClient = consentClient;
        this.evidenceWriter = evidenceWriter;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
        this.termsUrl = termsUrl;
        this.privacyUrl = privacyUrl;
    }

    /**
     * Fetches each published document and stores its bytes, once, at startup.
     *
     * <p><b>Why at startup and not per signup.</b> The document changes when it is republished, not
     * when someone signs up. Fetching per signup would put an external HTTP call on the signup path
     * and write the same bytes thousands of times into a bucket where nothing can be deleted for
     * seven years.
     *
     * <p><b>Why it never throws.</b> A failure here means signups record consent without a hash --
     * exactly what every account created before this feature did. An application that refuses to
     * start because a marketing page is briefly unreachable would turn a cosmetic outage into a
     * total one.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void snapshotPublishedDocuments() {
        if (!evidenceWriter.enabled()) {
            return;
        }
        snapshot(TYPE_TERMS, termsVersion, termsUrl);
        snapshot(TYPE_PRIVACY, privacyVersion, privacyUrl);
    }

    private void snapshot(String consentType, String version, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.error("Cannot snapshot {} {}: GET {} returned {}. Consent will be recorded "
                        + "without a document hash.", consentType, version, url, response.statusCode());
                return;
            }
            ConsentEvidenceWriter.Snapshot stored =
                    evidenceWriter.snapshotDocument(consentType, version, url, response.body());
            if (stored != null) {
                snapshots.put(consentType, stored);
            }
        } catch (Exception e) {
            log.error("Cannot snapshot {} {} from {}: {}. Consent will be recorded without a "
                    + "document hash.", consentType, version, url, e.toString());
        }
    }

    /** The URL a document of this type is published at. */
    private String urlFor(String consentType) {
        return TYPE_TERMS.equals(consentType) ? termsUrl : privacyUrl;
    }

    /**
     * Rejects a signup that did not affirmatively accept.
     *
     * <p>Only {@code true} passes. A null — the shape an older client or a hand-rolled request
     * sends — is a refusal, not a default: treating "field absent" as acceptance would recreate
     * implied consent through the back door, and a pre-ticked or inferred box is exactly what
     * Article 4(11) excludes by requiring a clear affirmative act.
     */
    public void requireAccepted(Boolean acceptedTerms) {
        if (!Boolean.TRUE.equals(acceptedTerms)) {
            throw new IllegalArgumentException(
                    "You must accept the Terms of Service and Privacy Policy to continue");
        }
    }

    /**
     * Records acceptance of both documents for a newly created account.
     *
     * <p>Two rows, not one: the terms are contractual necessity and the privacy policy is consent,
     * they are revised on separate schedules, and a single row could not express "still on the old
     * privacy policy but current on the terms".
     *
     * <p><b>Failures here do not fail the signup.</b> The account exists by this point, and throwing
     * would leave the caller with an error for an account that was in fact created — the worst of
     * both outcomes. A missing record is recoverable from the log; a phantom failed signup is not.
     * The log is ERROR because a gap in consent evidence is a compliance problem, not noise.
     */
    public void recordSignupConsent(String subjectType,
                                    UUID subjectId,
                                    String email,
                                    String source,
                                    HttpServletRequest request,
                                    String metadataJson) {
        recordSignupConsent(subjectType, subjectId, email, source, metadataJson,
                clientIp(request), header(request, "User-Agent"));
    }

    /**
     * As above, but with the client details supplied directly.
     *
     * <p>For the federated flow, where the act of consenting happened on an earlier request: by the
     * time the account exists the current request is the provider's redirect, so reading the IP and
     * user agent from it would describe the wrong client. The caller passes what it captured at the
     * moment of the act instead.
     */
    public void recordSignupConsent(String subjectType,
                                    UUID subjectId,
                                    String email,
                                    String source,
                                    String metadataJson,
                                    String ipAddress,
                                    String userAgent) {
        record(subjectType, subjectId, email, TYPE_TERMS, termsVersion, source, metadataJson, ipAddress, userAgent);
        record(subjectType, subjectId, email, TYPE_PRIVACY, privacyVersion, source, metadataJson, ipAddress, userAgent);
    }

    private void record(String subjectType,
                        UUID subjectId,
                        String email,
                        String consentType,
                        String documentVersion,
                        String source,
                        String metadataJson,
                        String ipAddress,
                        String userAgent) {
        // Absent when no bucket is configured or the fetch failed. Nothing is substituted: the DAO
        // rejects a malformed hash, and an invented one would read as evidence until checked.
        ConsentEvidenceWriter.Snapshot snapshot = snapshots.get(consentType);
        String documentUrl = urlFor(consentType);
        String documentSha256 = snapshot == null ? null : snapshot.sha256();
        String evidenceKey = snapshot == null ? null : snapshot.s3Key();

        try {
            com.fasterxml.jackson.databind.JsonNode saved = consentClient.record(
                    subjectType,
                    subjectId,
                    email,
                    consentType,
                    documentVersion,
                    source,
                    ipAddress,
                    userAgent,
                    metadataJson,
                    documentUrl,
                    documentSha256,
                    evidenceKey);

            // After the authoritative write, and never in place of it. The receipt is a convenience
            // for an auditor reading the bucket; the row is the record.
            UUID consentId = null;
            if (saved != null && saved.hasNonNull("id")) {
                try {
                    consentId = UUID.fromString(saved.get("id").asText());
                } catch (IllegalArgumentException ignored) {
                    // A receipt keyed by a random id is still a usable receipt.
                }
            }
            evidenceWriter.writeReceipt(consentId, subjectType, subjectId, email, consentType,
                    documentVersion, documentUrl, documentSha256, source, ipAddress, userAgent,
                    Instant.now());
        } catch (RuntimeException e) {
            log.error("Failed to record {} consent for {} {} from {}: {}",
                    consentType, subjectType, subjectId, source, e.toString());
        }
    }

    /** One account's consent history, newest first. */
    public com.fasterxml.jackson.databind.JsonNode history(String subjectType, UUID subjectId) {
        return consentClient.bySubject(subjectType, subjectId);
    }

    /**
     * Records consent for an existing account that has none, and reports whether it did.
     *
     * <p>For accounts that predate consent capture, or were created by a path that did not record
     * it. A social SIGN-IN does not ask for consent — the agreement belongs to registration — but an
     * account with no consent record at all is a gap that only a sign-in is in a position to close,
     * because that is the only moment the person is present and identified.
     *
     * <p>Records rather than blocks. The account already exists and its owner is already using the
     * product; refusing them entry over a missing historical record would punish them for our own
     * omission. The record is written so the gap is closed and auditable from then on.
     *
     * <p>A no-op when a record already exists, so a returning user is not re-recorded on every
     * sign-in. The unique index would collapse a repeat of the same document version anyway; this
     * avoids the write entirely and, more importantly, avoids logging a fresh act of consent that
     * never happened.
     */
    public boolean recordIfMissing(String subjectType,
                                   UUID subjectId,
                                   String email,
                                   String source,
                                   String ipAddress,
                                   String userAgent) {
        try {
            com.fasterxml.jackson.databind.JsonNode existing = history(subjectType, subjectId);
            if (existing != null && existing.isArray() && !existing.isEmpty()) {
                return false;
            }
        } catch (RuntimeException e) {
            // Unreadable history is not a reason to refuse a sign-in, and not a reason to write a
            // duplicate either. Leave it alone and say so.
            log.error("Could not read consent history for {} {}: {}", subjectType, subjectId, e.toString());
            return false;
        }

        recordSignupConsent(subjectType, subjectId, email, source, null, ipAddress, userAgent);
        return true;
    }

    /** Everything one email address ever agreed to — answers a subject access request. */
    public com.fasterxml.jackson.databind.JsonNode historyByEmail(String email) {
        return consentClient.byEmail(email.trim().toLowerCase());
    }

    /**
     * The caller's IP, preferring the first X-Forwarded-For hop.
     *
     * <p>Every request arrives through CloudFront and Caddy, so {@code getRemoteAddr()} is a proxy
     * address and recording it would evidence nothing. The FIRST entry in X-Forwarded-For is the
     * original client; later entries are the proxies it passed through.
     *
     * <p>The header is client-controllable and therefore not trustworthy as an access-control input.
     * That is acceptable here: this is corroborating evidence attached to a consent record, not an
     * authorisation decision, and a forged value is no worse than the absent value it replaces.
     */
    public String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    /** The caller's User-Agent, truncated. Exposed so a caller capturing it early agrees with us. */
    public String userAgent(HttpServletRequest request) {
        return header(request, "User-Agent");
    }

    private String header(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        if (value == null) {
            return null;
        }
        // The column is unbounded text, but a User-Agent is attacker-controlled and unbounded input
        // in an append-only table is a slow way to fill a disk.
        return value.length() > 512 ? value.substring(0, 512) : value;
    }
}
