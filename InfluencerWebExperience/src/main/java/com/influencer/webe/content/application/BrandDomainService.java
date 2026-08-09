package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Connecting a brand-owned domain to a hosted page (roadmap Phase E).
 *
 * <p><b>The brand owns the domain; we host the page.</b> Decision #9 — the platform never
 * resells, so this class purchases nothing. Decision #10 — hosting is always ours, so a brand
 * cannot point a domain at their own server, which keeps SSL renewal, cache invalidation and
 * rollback in one place.
 *
 * <p><b>Free hosting is two months from FIRST PUBLISH.</b> Decision #11. A brand that signs up,
 * explores for six weeks and then publishes gets the full window on the thing being trialled —
 * starting the clock at signup would let a trial expire while they were still learning the
 * builder.
 */
@Service
public class BrandDomainService {

    /**
     * Two months, per decision #11. A constant rather than a config value: changing the free
     * period is a pricing decision, and it should show up in a diff.
     */
    static final Duration FREE_HOSTING = Duration.ofDays(60);

    /**
     * A conservative hostname pattern. Deliberately narrow — this string is echoed back into DNS
     * instructions a brand copies verbatim, so anything strange should be refused rather than
     * rendered.
     */
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))+$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final DomainRegistrarPort registrar;

    public BrandDomainService(DaoGatewayClient dao, ResponseShapeService shape,
                              DomainRegistrarPort registrar) {
        this.dao = dao;
        this.shape = shape;
        this.registrar = registrar;
    }

    // ---- connect and verify (E.3) ---------------------------------------

    public JsonNode list(UUID brandId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        JsonNode rows = dao.get("/brand-domains", q);
        return rows == null ? shape.objectMapper().createArrayNode() : rows;
    }

    /**
     * Connect a domain the brand already owns.
     *
     * <p>E.3 and E.4 collapse into this one flow. The roadmap's two paths — "connect existing"
     * and "provision new" — differ only in where the brand got the domain, and since we never
     * transact the purchase (decision #9) both arrive here identically.
     */
    public JsonNode connect(UUID brandId, String domainName, UUID templateId) {
        String name = normalize(domainName);
        if (!DOMAIN.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That does not look like a domain name. Enter it without http:// or a trailing path.");
        }
        if (templateId != null) {
            requireOwnedPage(brandId, templateId);
        }

        // The token is generated here and never accepted from the caller: a caller-chosen token
        // would let someone claim a domain by naming a TXT record that already exists on it.
        byte[] entropy = new byte[24];
        RANDOM.nextBytes(entropy);
        String token = "influencrm-verify-" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("domainName", name);
        body.put("dnsStatus", "pending");
        body.put("sslStatus", "pending");
        body.put("verificationToken", token);
        if (templateId != null) {
            body.put("landingTemplateId", templateId.toString());
        }

        JsonNode saved;
        try {
            saved = dao.post("/brand-domains", body);
        } catch (RuntimeException e) {
            // The unique constraint is global, not per brand: two brands cannot both serve
            // example.com. Saying so here is better than a DNS failure days later.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That domain is already connected. If you own it, remove it from the other "
                            + "brand first.");
        }
        return withInstructions(saved);
    }

    /**
     * Check DNS, and issue a certificate once verified (E.3, E.5).
     *
     * <p>Not verified is a normal answer, not an error: DNS changes take time and a brand will
     * poll this. Returning 200 with {@code verified: false} lets a UI say "not yet" instead of
     * showing an error for something that is simply in progress.
     */
    public JsonNode verify(UUID brandId, UUID domainId) {
        JsonNode domain = requireOwnedDomain(brandId, domainId);
        String name = domain.get("domainName").asText();

        DomainRegistrarPort.Verification result =
                registrar.verifyDns(name, domain.path("verificationToken").asText(""));

        ObjectNode body = domain.deepCopy();
        if (!result.verified()) {
            body.put("dnsStatus", "verifying");
            JsonNode saved = dao.put("/brand-domains/" + domainId, body);
            ObjectNode out = (ObjectNode) withInstructions(saved);
            out.put("verified", false);
            out.put("detail", result.detail());
            out.put("provider", result.provider());
            return out;
        }

        body.put("dnsStatus", "active");
        body.put("verifiedAt", Instant.now().toString());

        // Certificate only after verification. Issuing for a name we cannot prove control of is
        // exactly what certificate authorities exist to prevent.
        DomainRegistrarPort.Certificate cert = registrar.issueCertificate(name);
        body.put("sslStatus", cert.issued() ? "active" : "failed");
        if (cert.issued()) {
            body.put("sslIssuedAt", Instant.now().toString());
        }

        JsonNode saved = dao.put("/brand-domains/" + domainId, body);
        ObjectNode out = (ObjectNode) withInstructions(saved);
        out.put("verified", true);
        out.put("detail", cert.issued() ? result.detail() : cert.detail());
        out.put("provider", result.provider());
        return out;
    }

    public void disconnect(UUID brandId, UUID domainId) {
        requireOwnedDomain(brandId, domainId);
        dao.delete("/brand-domains/" + domainId);
    }

    // ---- hosting window (decision #11) ----------------------------------

    /**
     * Start the free-hosting clock, once, on first publish.
     *
     * <p>Idempotent: a page republished later keeps its original window. Restarting the clock on
     * every publish would make the trial unbounded for anyone who unpublishes and republishes.
     */
    public JsonNode startHostingWindow(UUID brandId, UUID templateId) {
        JsonNode page = requireOwnedPage(brandId, templateId);
        if (page.hasNonNull("firstPublishedAt")) {
            return shape.landingTemplate(page);
        }

        Instant now = Instant.now();
        ObjectNode body = page.deepCopy();
        body.put("firstPublishedAt", now.toString());
        body.put("hostingExpiresAt", now.plus(FREE_HOSTING).toString());
        stringifyJsonb(body, "document", "blocks", "theme");
        return shape.landingTemplate(dao.put("/landing-templates/" + templateId, body));
    }

    /**
     * Whether a page's free hosting has run out.
     *
     * <p>A page that has never been published has no expiry and is not expired — NULL means the
     * clock has not started, not that it ran out.
     */
    public boolean isExpired(JsonNode page) {
        if (page == null || !page.hasNonNull("hostingExpiresAt")) {
            return false;
        }
        try {
            return Instant.parse(page.get("hostingExpiresAt").asText()).isBefore(Instant.now());
        } catch (Exception e) {
            // An unparseable date must not take a live page down.
            return false;
        }
    }

    /**
     * Extend hosting — a promotion, or payment.
     *
     * <p>This is why expiry is a movable date rather than an {@code is_free} boolean: a
     * campaign-wide extension is a bulk update, not a schema change.
     */
    public JsonNode extendHosting(UUID brandId, UUID templateId, int days) {
        if (days <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be positive");
        }
        JsonNode page = requireOwnedPage(brandId, templateId);

        // Extend from whichever is later: an already-expired page gets a fresh window from now
        // rather than a window that starts in the past and is instantly spent.
        Instant base = Instant.now();
        if (page.hasNonNull("hostingExpiresAt")) {
            try {
                Instant current = Instant.parse(page.get("hostingExpiresAt").asText());
                if (current.isAfter(base)) {
                    base = current;
                }
            } catch (Exception ignored) {
                // Fall back to now.
            }
        }

        ObjectNode body = page.deepCopy();
        body.put("hostingExpiresAt", base.plus(Duration.ofDays(days)).toString());
        // M5.6: a new deadline is owed a new set of warnings. Leaving the old marker in place
        // would silence every future warning for this page — a page extended at day 1 would never
        // be warned again, and would go dark unannounced at the end of the extension.
        body.putNull("hostingWarningSentAtDays");
        stringifyJsonb(body, "document", "blocks", "theme");
        return shape.landingTemplate(dao.put("/landing-templates/" + templateId, body));
    }

    /** The domain serving a page, if any — used by the renderer to resolve a public request. */
    public JsonNode domainForPage(UUID templateId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("landingTemplateId", templateId.toString());
        JsonNode rows = dao.get("/brand-domains", q);
        return rows != null && rows.isArray() && rows.size() > 0 ? rows.get(0) : null;
    }

    // ---- internals -------------------------------------------------------

    /** Attach the DNS records a brand must create. Generated, never stored, so they cannot drift. */
    private JsonNode withInstructions(JsonNode domain) {
        ObjectNode out = domain.deepCopy();
        DomainRegistrarPort.Instructions instructions = registrar.instructionsFor(
                domain.path("domainName").asText(""), domain.path("verificationToken").asText(""));
        ObjectNode dns = out.putObject("dnsInstructions");
        dns.put("verificationRecord", instructions.verificationRecord());
        dns.put("aliasRecord", instructions.aliasRecord());
        dns.put("note", instructions.note());
        out.put("provider", registrar.provider());
        return out;
    }

    private JsonNode requireOwnedDomain(UUID brandId, UUID domainId) {
        JsonNode domain = dao.get("/brand-domains/" + domainId, null);
        if (domain == null || !domain.hasNonNull("brandId")
                || !domain.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain not found");
        }
        return domain;
    }

    private JsonNode requireOwnedPage(UUID brandId, UUID templateId) {
        JsonNode page = dao.get("/landing-templates/" + templateId, null);
        if (page == null || !page.hasNonNull("brandId")
                || !page.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        return page;
    }

    /** jsonb columns are mapped as String on the entity; sending an object fails the PUT. */
    private void stringifyJsonb(ObjectNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && (value.isObject() || value.isArray())) {
                try {
                    node.put(field, shape.objectMapper().writeValueAsString(value));
                } catch (Exception ignored) {
                    node.remove(field);
                }
            }
        }
    }

    private String normalize(String domainName) {
        if (domainName == null) {
            return "";
        }
        return domainName.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceFirst("/.*$", "");
    }
}
