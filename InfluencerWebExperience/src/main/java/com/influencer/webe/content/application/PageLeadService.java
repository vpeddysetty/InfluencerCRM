package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.application.ConsentService;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lead capture from a public landing page (roadmap PR-61).
 *
 * <p><b>Consent BEFORE the row, always.</b> A refusal must leave nothing behind: if someone declines
 * the terms, there must be no record that they were ever here. This mirrors
 * {@code CreatorOnboardingController.publicSignup}, which records consent before creating a lead
 * for the same reason, and it is the ordering an erasure request depends on being able to trust.
 *
 * <p><b>Why consent is recorded through {@code ConsentService} rather than as a column.</b> There is
 * no account to attach it to — a person hands a brand their address on a page served to anonymous
 * visitors, and the platform processes it as a third party. That is the case with the weakest paper
 * trail and the one an erasure request is most likely to concern, so it gets the version, the
 * document URL and the immutable snapshot `PR-36` built. A boolean here would be a claim; that is
 * evidence.
 *
 * <p><b>Rate limited, because this is the second unauthenticated write in the product.</b>
 * {@code OP-25} was the first, and its lesson applies exactly: an endpoint anyone on the internet
 * can POST to, which writes a row and sends nothing back, is a spam funnel into a brand's inbox.
 * The limit is per PAGE for the same reason the sign-up ceiling is — an IP key is defeated by
 * spreading submissions and punishes an office behind one NAT, which is the audience a creator
 * campaign wants.
 */
@Service
public class PageLeadService {

    private static final Logger log = LoggerFactory.getLogger(PageLeadService.class);

    /**
     * How many leads one published page may accept per window.
     *
     * <p>Sized against the honest case: a page doing well collects a handful of enquiries an hour,
     * and thirty is far above that while bounding what a script can insert. Unlike the sign-up
     * ceiling this REFUSES past the limit rather than degrading — there is no lesser version of
     * storing someone's email, so the choice is store it or do not.
     */
    private static final int MAX_PER_WINDOW = 30;
    private static final Duration WINDOW = Duration.ofHours(1);

    /** In-memory, and honestly so — the same caveat {@code LoginAttemptLimiter} carries. */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final ConsentService consent;

    public PageLeadService(DaoGatewayClient dao, ResponseShapeService shape, ConsentService consent) {
        this.dao = dao;
        this.shape = shape;
        this.consent = consent;
    }

    /**
     * Record an enquiry from a public page.
     *
     * @param slug        the page's public slug — the tenant is derived from it, never from the caller
     * @param acceptedTerms what the visitor ticked; a refusal creates nothing at all
     */
    public JsonNode capture(String slug, ObjectNode payload, Boolean acceptedTerms, HttpServletRequest request) {
        JsonNode template = pageBySlug(slug);
        if (template == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        if (!"published".equalsIgnoreCase(text(template, "status"))) {
            // Same rule as the public renderer: an unpublished page is not addressable, so a form
            // posting to one is either a stale tab or someone probing.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }

        String email = text(payload, "email");
        if (email == null || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email address is required");
        }

        // BEFORE anything is written. A refusal must leave no trace that this person was here.
        consent.requireAccepted(acceptedTerms);

        if (!allow(slug)) {
            // 429 rather than a silent drop: a visitor who typed a real message deserves to know it
            // did not arrive, and a brand behind a spam wave deserves the log line.
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many enquiries from this page just now. Please try again shortly.");
        }

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", text(template, "brandId"));
        body.put("landingTemplateId", text(template, "id"));
        body.put("email", email.trim());
        // Rebuilt rather than forwarded: the caller supplies three fields and nothing else. A
        // brandId or a createdAt in the body is dropped here rather than trusted, exactly as
        // publicSignup does.
        putIfPresent(payload, body, "name");
        putIfPresent(payload, body, "message");
        if (request != null) {
            body.put("ipAddress", clientIp(request));
            body.put("userAgent", header(request, "User-Agent"));
        }

        JsonNode saved = dao.post("/page-leads", body);

        // AFTER the row exists, so the consent record can name what it consented to. Best-effort:
        // losing the evidence is bad, losing the lead the visitor just sent is worse, and the
        // failure is loud enough to find.
        try {
            consent.recordSignupConsent(
                    ConsentService.SUBJECT_LEAD,
                    saved != null && saved.hasNonNull("id") ? UUID.fromString(saved.get("id").asText()) : null,
                    email.trim(),
                    "page_lead",
                    request,
                    null);
        } catch (RuntimeException e) {
            log.warn("Lead captured but consent not recorded for a page_lead: {}", e.toString());
        }

        // Deliberately thin: the public caller learns that it worked and nothing about the brand,
        // the page id, or what else is stored.
        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("received", true);
        return out;
    }

    /** What has come in for a page. Brand-side, so the full row is returned. */
    public JsonNode listFor(UUID brandId, UUID templateId) {
        JsonNode template = read("/landing-templates/" + templateId, new LinkedHashMap<>());
        if (template == null || !brandId.toString().equals(text(template, "brandId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("landingTemplateId", templateId.toString());
        JsonNode leads = read("/page-leads", query);
        return leads == null ? shape.objectMapper().createArrayNode() : leads;
    }

    // ---- rate limiting -------------------------------------------------

    private boolean allow(String slug) {
        Instant now = Instant.now();
        Window updated = windows.compute(slug == null ? "" : slug.toLowerCase(java.util.Locale.ROOT),
                (ignored, existing) -> existing == null || existing.startedAt.plus(WINDOW).isBefore(now)
                        ? new Window(1, now)
                        : new Window(existing.count + 1, existing.startedAt));
        return updated.count <= MAX_PER_WINDOW;
    }

    private record Window(int count, Instant startedAt) {
    }

    // ---- helpers -------------------------------------------------------

    private JsonNode pageBySlug(String slug) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("publicSlug", slug);
        JsonNode templates = read("/landing-templates", query);
        if (templates == null || !templates.isArray() || templates.isEmpty()) {
            return null;
        }
        return templates.get(0);
    }

    /**
     * The client's address as far as it can be known.
     *
     * <p>Behind CloudFront the socket address is the CDN, so the forwarded header is the only place
     * the visitor's address appears — and its FIRST entry is the client, the rest being proxies.
     * Spoofable, and recorded anyway: it is one signal among several on a record whose purpose is
     * answering "who submitted this", not a security control.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = header(request, "X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? null : value.substring(0, Math.min(value.length(), 500));
    }

    private void putIfPresent(JsonNode source, ObjectNode target, String field) {
        String value = text(source, field);
        if (value != null) {
            // Bounded: these land in a brand's inbox view, and an unbounded message field on an
            // unauthenticated endpoint is a way to fill a database with one request.
            target.put(field, value.substring(0, Math.min(value.length(), field.equals("message") ? 2000 : 200)));
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private JsonNode read(String path, Map<String, String> query) {
        try {
            return dao.get(path, query);
        } catch (RuntimeException e) {
            log.info("Lead capture could not read {}: {}", path, e.toString());
            return null;
        }
    }
}
