package com.influencer.webe.attribution.application;

import com.influencer.webe.shared.application.ResponseShapeService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Coupon generation logic for the BFF.
 *
 * A coupon is an {@code influencer_campaign_codes} row that anchors the
 * (campaign, creator) attribution pair. This service expands code templates,
 * enforces uniqueness against the tenant's existing codes, and persists via the
 * DAO. It never talks to a marketplace — that is Phase 2 (the SPI layer).
 */
@Service
public class CouponService {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no ambiguous chars
    private static final int RANDOM_SUFFIX_LEN = 4;
    private static final int MAX_COLLISION_RETRIES = 12;

    // A small blocklist; generated codes that would contain these are regenerated.
    private static final Set<String> PROFANITY = Set.of("FUCK", "SHIT", "CUNT", "DICK", "COCK", "ASS");

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final SecureRandom random = new SecureRandom();

    public CouponService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    /**
     * Generate one coupon for a (campaign, creator) pair. Honors a vanity
     * {@code code} if supplied, otherwise expands {@code codePattern}, otherwise
     * falls back to a randomized code. Enforces per-tenant uniqueness.
     */
    public JsonNode generateOne(UUID brandId, ObjectNode request) {
        Set<String> existing = loadExistingCodes(brandId);
        ObjectNode created = buildAndPersist(brandId, request, existing);
        return shape.campaignCode(created);
    }

    /**
     * Bulk-generate one coupon per creator on a campaign. {@code request} carries
     * the shared coupon attributes (pattern, discount, commission, channel, …)
     * plus a {@code creators} array of {creatorId, creatorName, campaignCreatorId}.
     * Codes are de-duplicated within the batch and against existing tenant codes.
     */
    public JsonNode generateBulk(UUID brandId, ObjectNode request) {
        JsonNode creators = request.get("creators");
        if (creators == null || !creators.isArray() || creators.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creators array is required for bulk generation");
        }
        Set<String> existing = loadExistingCodes(brandId);

        var out = shape.objectMapper().createArrayNode();
        for (JsonNode creator : creators) {
            ObjectNode perCreator = request.deepCopy();
            perCreator.remove("creators");
            perCreator.remove("code"); // vanity code is single-only; ignored in bulk
            if (creator.hasNonNull("creatorId")) {
                perCreator.put("creatorId", creator.get("creatorId").asText());
            }
            if (creator.hasNonNull("creatorName")) {
                perCreator.put("creatorName", creator.get("creatorName").asText());
            }
            if (creator.hasNonNull("campaignCreatorId")) {
                perCreator.put("campaignCreatorId", creator.get("campaignCreatorId").asText());
            }
            ObjectNode created = buildAndPersist(brandId, perCreator, existing);
            out.add(shape.campaignCode(created));
        }
        return out;
    }

    /**
     * Set creator personalization (blurb + optional embed) on a coupon and mark it
     * pending brand approval. Content Phase 3.
     */
    public JsonNode personalize(UUID brandId, UUID couponId, ObjectNode request) {
        JsonNode coupon = dao.get("/influencer-campaign-codes/" + couponId, null);
        requireOwner(coupon, brandId);
        ObjectNode update = ((ObjectNode) coupon).deepCopy();
        update.put("personalBlurb", textOr(request, "personalBlurb", ""));
        update.put("embedUrl", textOr(request, "embedUrl", ""));
        update.put("personalizationStatus", "pending");
        return shape.campaignCode(dao.put("/influencer-campaign-codes/" + couponId, update));
    }

    /** Brand approve/reject a coupon's pending personalization. Content Phase 3. */
    public JsonNode decidePersonalization(UUID brandId, UUID couponId, String decision) {
        JsonNode coupon = dao.get("/influencer-campaign-codes/" + couponId, null);
        requireOwner(coupon, brandId);
        String status;
        if ("approve".equalsIgnoreCase(decision)) {
            status = "approved";
        } else if ("reject".equalsIgnoreCase(decision)) {
            status = "rejected";
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be approve or reject");
        }
        ObjectNode update = ((ObjectNode) coupon).deepCopy();
        update.put("personalizationStatus", status);
        return shape.campaignCode(dao.put("/influencer-campaign-codes/" + couponId, update));
    }

    private void requireOwner(JsonNode coupon, UUID brandId) {
        if (coupon == null || coupon.isNull() || !coupon.hasNonNull("id")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "coupon not found");
        }
        if (!coupon.hasNonNull("brandId") || !coupon.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your coupon");
        }
    }

    // ---- internals -----------------------------------------------------

    private ObjectNode buildAndPersist(UUID brandId, ObjectNode request, Set<String> existing) {
        String code = resolveCode(request, existing);
        existing.add(code.toUpperCase(Locale.ROOT)); // reserve within this batch

        ObjectNode payload = shape.objectMapper().createObjectNode();
        payload.put("brandId", brandId.toString());
        copyIfPresent(request, payload, "campaignId");
        copyIfPresent(request, payload, "creatorId");
        copyIfPresent(request, payload, "campaignCreatorId");
        payload.put("code", code);
        payload.put("codeType", textOr(request, "codeType", "discount"));
        copyIfPresent(request, payload, "landingUrl");
        copyIfPresent(request, payload, "startsAt");
        copyIfPresent(request, payload, "endsAt");
        copyIfPresent(request, payload, "discountType");
        copyIfPresent(request, payload, "discountValue");
        copyIfPresent(request, payload, "commissionType");
        copyIfPresent(request, payload, "commissionValue");
        copyIfPresent(request, payload, "channel");
        copyIfPresent(request, payload, "marketplaceConnectionId");
        // ref_slug: default to a URL-safe form of the code when not provided
        payload.put("refSlug", textOr(request, "refSlug", slugify(code)));
        payload.put("isActive", request.has("isActive") ? request.get("isActive").asBoolean(true) : true);
        payload.put("syncStatus", "local");

        if (payload.get("campaignId") == null || payload.get("creatorId") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId and creatorId are required");
        }

        JsonNode saved = dao.post("/influencer-campaign-codes", payload);
        if (saved == null || !saved.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO returned no coupon");
        }
        return (ObjectNode) saved;
    }

    /**
     * Resolve the final code: vanity > template pattern > randomized. Validates
     * uniqueness and profanity; templates/randomized retry on collision.
     */
    private String resolveCode(ObjectNode request, Set<String> existing) {
        String vanity = textOrNull(request, "code");
        if (vanity != null) {
            String normalized = normalize(vanity);
            if (normalized.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code cannot be blank");
            }
            if (existing.contains(normalized.toUpperCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Coupon code already exists: " + normalized);
            }
            if (isProfane(normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is not allowed");
            }
            return normalized;
        }

        String pattern = textOrNull(request, "codePattern");
        for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
            String candidate = pattern != null ? expandPattern(pattern, request) : randomCode();
            candidate = normalize(candidate);
            if (candidate.isBlank() || isProfane(candidate) || existing.contains(candidate.toUpperCase(Locale.ROOT))) {
                // On a fixed pattern with no random token, appending randomness breaks the collision.
                candidate = normalize(candidate + randomSuffix());
                if (candidate.isBlank() || existing.contains(candidate.toUpperCase(Locale.ROOT))) {
                    continue;
                }
            }
            return candidate;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Unable to generate a unique coupon code after retries");
    }

    /**
     * Expand a template like {@code {CREATOR}{DISCOUNT}} → {@code JADE20}.
     * Tokens: {CREATOR}, {BRAND}, {DISCOUNT}, {CHANNEL}, {RANDOM}.
     */
    private String expandPattern(String pattern, ObjectNode request) {
        String creator = alnum(textOr(request, "creatorName", ""));
        String brand = alnum(textOr(request, "brandName", ""));
        String channel = alnum(textOr(request, "channel", ""));
        String discount = discountToken(request);

        return pattern
                .replace("{CREATOR}", creator.toUpperCase(Locale.ROOT))
                .replace("{BRAND}", brand.toUpperCase(Locale.ROOT))
                .replace("{CHANNEL}", channel.toUpperCase(Locale.ROOT))
                .replace("{DISCOUNT}", discount)
                .replace("{RANDOM}", randomSuffix());
    }

    private String discountToken(ObjectNode request) {
        JsonNode v = request.get("discountValue");
        if (v == null || v.isNull()) {
            return "";
        }
        double d = v.asDouble();
        // Integer-ish values render clean: 20.0 -> "20"
        if (d == Math.floor(d)) {
            return Integer.toString((int) d);
        }
        return Double.toString(d).replace(".", "");
    }

    private Set<String> loadExistingCodes(UUID brandId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", brandId.toString());
        JsonNode existing = dao.get("/influencer-campaign-codes", query);
        Set<String> codes = new HashSet<>();
        if (existing != null && existing.isArray()) {
            for (JsonNode node : existing) {
                if (node.hasNonNull("code")) {
                    codes.add(node.get("code").asText().toUpperCase(Locale.ROOT));
                }
            }
        }
        return codes;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(RANDOM_SUFFIX_LEN);
        for (int i = 0; i < RANDOM_SUFFIX_LEN; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private boolean isProfane(String code) {
        String upper = code.toUpperCase(Locale.ROOT);
        for (String bad : PROFANITY) {
            if (upper.contains(bad)) {
                return true;
            }
        }
        return false;
    }

    /** Uppercase, keep A-Z 0-9 and dashes, collapse whitespace to nothing. */
    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\\-]", "");
    }

    private String alnum(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "");
    }

    private String slugify(String code) {
        return code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-");
    }

    private void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        if (from != null && from.hasNonNull(field)) {
            to.set(field, from.get(field));
        }
    }

    private String textOr(JsonNode node, String field, String fallback) {
        String v = textOrNull(node, field);
        return v == null ? fallback : v;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) {
            return null;
        }
        String v = node.get(field).asText();
        return (v == null || v.isBlank()) ? null : v;
    }
}
