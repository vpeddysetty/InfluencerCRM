package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.config.WebExperienceProperties;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Brand ↔ creator share links (Content collaboration Phase 1).
 *
 * A brand creates a tokenized, scoped share for a campaign (+ optionally a
 * creator). The token grants no-login, read-only access to just that slice: the
 * campaign brief and/or the creator's personalized landing draft. Resolution is
 * strictly scoped by the token — a token never exposes other creators or the
 * brand's wider data.
 */
@Service
public class ShareService {
    private static final String TOKEN_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final int TOKEN_LEN = 24;

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final LandingService landingService;
    private final WebExperienceProperties properties;
    private final SecureRandom random = new SecureRandom();

    public ShareService(DaoGatewayClient dao,
                        ResponseShapeService shape,
                        LandingService landingService,
                        WebExperienceProperties properties) {
        this.dao = dao;
        this.shape = shape;
        this.landingService = landingService;
        this.properties = properties;
    }

    /**
     * Create a share link for a campaign (+ optional creator). scope ∈
     * brief_view | landing_review | landing_edit. Returns the token row plus a
     * ready-to-use link and a pre-filled mailto draft.
     */
    public JsonNode createShare(UUID userId, ObjectNode payload) {
        UUID campaignId = uuid(payload, "campaignId");
        if (campaignId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId is required");
        }
        UUID creatorId = uuid(payload, "creatorId");
        String scope = normalizeScope(text(payload, "scope"));

        String token = generateToken();
        ObjectNode row = shape.objectMapper().createObjectNode();
        row.put("userId", userId.toString());
        row.put("campaignId", campaignId.toString());
        if (creatorId != null) {
            row.put("creatorId", creatorId.toString());
        }
        row.put("token", token);
        row.put("scope", scope);
        row.put("revoked", false);

        JsonNode saved = dao.post("/share-tokens", row);

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("id", saved.get("id").asText());
        out.put("token", token);
        out.put("scope", scope);
        out.put("campaignId", campaignId.toString());
        if (creatorId != null) {
            out.put("creatorId", creatorId.toString());
        }
        String link = properties.getUiBaseUrl() + "/share/" + token;
        out.put("link", link);
        out.put("mailto", buildMailto(link, scope));
        return out;
    }

    /** List a brand's shares for a campaign. */
    public JsonNode listShares(UUID userId, UUID campaignId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("userId", userId.toString());
        if (campaignId != null) {
            q.put("campaignId", campaignId.toString());
        }
        return dao.get("/share-tokens", q);
    }

    /** Revoke a share (brand-auth'd; ownership-checked). */
    public void revokeShare(UUID userId, UUID shareId) {
        JsonNode row = dao.get("/share-tokens/" + shareId, null);
        requireOwner(row, userId);
        ObjectNode update = ((ObjectNode) row).deepCopy();
        update.put("revoked", true);
        dao.put("/share-tokens/" + shareId, update);
    }

    /**
     * PUBLIC: resolve a share token into its scoped payload (no auth). Returns
     * the campaign name, scope, the brief (if any), and — for landing scopes —
     * the rendered read-only landing HTML for the creator's coupon.
     */
    public JsonNode resolvePublic(String token) {
        JsonNode share = loadValidToken(token);
        UUID userId = UUID.fromString(share.get("userId").asText());
        UUID campaignId = UUID.fromString(share.get("campaignId").asText());
        String scope = text(share, "scope");

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("scope", scope);
        out.put("campaignId", campaignId.toString());
        out.put("shareId", share.get("id").asText());
        if (share.hasNonNull("creatorId")) {
            out.put("creatorId", share.get("creatorId").asText());
        }

        // Campaign name (read-only context).
        JsonNode campaign = dao.get("/campaigns/" + campaignId, null);
        if (campaign != null && campaign.hasNonNull("name")) {
            out.put("campaignName", campaign.get("name").asText());
        }

        // Brief (shared in every scope so the creator has campaign perspective).
        Map<String, String> bq = new LinkedHashMap<>();
        bq.put("userId", userId.toString());
        bq.put("campaignId", campaignId.toString());
        JsonNode briefs = dao.get("/campaign-briefs", bq);
        if (briefs != null && briefs.isArray() && briefs.size() > 0) {
            out.set("brief", shape.campaignBrief(briefs.get(0)));
        }

        // Landing preview HTML for landing scopes (read-only render for the creator's coupon).
        if (("landing_review".equals(scope) || "landing_edit".equals(scope)) && share.hasNonNull("creatorId")) {
            String html = renderCreatorLanding(userId, campaignId, UUID.fromString(share.get("creatorId").asText()));
            if (html != null) {
                out.put("landingHtml", html);
            }
        }
        return out;
    }

    // ---- internals -----------------------------------------------------

    private String renderCreatorLanding(UUID userId, UUID campaignId, UUID creatorId) {
        // Find the creator's coupon on this campaign, then render via LandingService preview.
        Map<String, String> q = new LinkedHashMap<>();
        q.put("userId", userId.toString());
        q.put("campaignId", campaignId.toString());
        JsonNode coupons = dao.get("/influencer-campaign-codes", q);
        String couponId = null;
        if (coupons != null && coupons.isArray()) {
            for (JsonNode c : coupons) {
                if (c.hasNonNull("creatorId") && c.get("creatorId").asText().equals(creatorId.toString())) {
                    couponId = c.get("id").asText();
                    break;
                }
            }
        }
        // Load the campaign's landing template blocks.
        Map<String, String> tq = new LinkedHashMap<>();
        tq.put("userId", userId.toString());
        tq.put("campaignId", campaignId.toString());
        JsonNode templates = dao.get("/landing-templates", tq);
        if (templates == null || !templates.isArray() || templates.size() == 0) {
            return null;
        }
        JsonNode template = templates.get(0);

        ObjectNode preview = shape.objectMapper().createObjectNode();
        preview.put("campaignId", campaignId.toString());
        preview.put("name", template.hasNonNull("name") ? template.get("name").asText() : "Landing page");
        // blocks may be a JSON string from the DAO — pass through; LandingService handles both.
        preview.set("blocks", template.get("blocks"));
        if (couponId != null) {
            preview.put("couponId", couponId);
        }
        return landingService.previewTemplate(userId, preview);
    }

    private JsonNode loadValidToken(String token) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("token", token);
        JsonNode rows = dao.get("/share-tokens", q);
        if (rows == null || !rows.isArray() || rows.size() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found");
        }
        JsonNode share = rows.get(0);
        if (share.hasNonNull("revoked") && share.get("revoked").asBoolean()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This share link has been revoked");
        }
        if (share.hasNonNull("expiresAt")) {
            try {
                if (Instant.parse(share.get("expiresAt").asText()).isBefore(Instant.now())) {
                    throw new ResponseStatusException(HttpStatus.GONE, "This share link has expired");
                }
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception ignored) {
                // unparseable expiry → treat as non-expiring
            }
        }
        return share;
    }

    private String buildMailto(String link, String scope) {
        String subject = scope.startsWith("landing")
                ? "Review your campaign landing page"
                : "Your campaign brief";
        String body = "Hi,%0D%0A%0D%0AHere's the " + (scope.startsWith("landing") ? "landing page draft" : "campaign brief")
                + " to review:%0D%0A" + link + "%0D%0A%0D%0AThanks!";
        return "mailto:?subject=" + subject.replace(" ", "%20") + "&body=" + body;
    }

    private String normalizeScope(String scope) {
        if (scope == null) {
            return "brief_view";
        }
        switch (scope) {
            case "brief_view":
            case "landing_review":
            case "landing_edit":
                return scope;
            default:
                return "brief_view";
        }
    }

    private String generateToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LEN);
        for (int i = 0; i < TOKEN_LEN; i++) {
            sb.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    private void requireOwner(JsonNode row, UUID userId) {
        if (row == null || row.isNull() || !row.hasNonNull("id")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found");
        }
        if (!row.hasNonNull("userId") || !row.get("userId").asText().equals(userId.toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your share");
        }
    }

    private UUID uuid(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field) || payload.get(field).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(field).asText());
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
