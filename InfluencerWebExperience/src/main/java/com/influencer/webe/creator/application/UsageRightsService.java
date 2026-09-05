package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a brand may do with a creator's content, and for how long (roadmap PR-68).
 *
 * <p><b>Why this exists.</b> Paying a creator buys the content being made and, separately,
 * permission to use it. Most deals leave copyright with the creator and grant an implied licence to
 * the post on their own feed — nothing more. Running that photo as a paid ad without an explicit
 * grant is infringement plus a likeness claim, and it is a routine source of demand letters. The
 * agency holds that risk: it negotiated the terms and runs the ads.
 *
 * <p><b>It records the TERMS, not the agreement.</b> No document, no signature, no evidence — the
 * contract lives wherever the agency signs contracts. That boundary is what keeps this small rather
 * than `PR-53`'s e-signature work, and it is the same line {@code TaxThresholdService} draws by
 * recording that a W-9 arrived without holding the form.
 *
 * <p><b>Unset is UNKNOWN, never granted.</b> A grant nobody recorded must read as unrecorded. On a
 * legal question, failing open is worse than an empty field: an empty field prompts someone to ask,
 * and an affirmative answer stops them.
 */
@Service
public class UsageRightsService {

    /**
     * The vocabulary the UI offers.
     *
     * <p>Enforced here rather than by a check constraint: the real vocabulary is open — whitelisting,
     * dark posts, packaging, out-of-home — and a database constraint would need a migration the
     * first time an agency negotiates something ordinary that nobody listed. Widening this list
     * costs nothing; widening a constraint costs a deploy.
     */
    static final Set<String> SCOPES = Set.of(
            "organic", "paid_amplification", "brand_channels", "web", "email", "print");

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public UsageRightsService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    /**
     * Record what was agreed on one engagement.
     *
     * <p>Ownership is proved before anything is written: an engagement id from another brand must
     * not become a way to discover, or edit, someone else's terms.
     */
    public JsonNode record(UUID brandId, UUID engagementId, JsonNode payload) {
        JsonNode existing = dao.get("/campaign-creators/" + engagementId, new LinkedHashMap<>());
        if (existing == null || !brandId.toString().equals(existing.path("brandId").asText(null))) {
            // Not "forbidden": saying which of the two it was confirms the id exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Engagement not found");
        }

        ObjectNode body = existing.deepCopy();
        if (payload.has("usageScopes")) {
            body.set("usageScopes", validatedScopes(payload.get("usageScopes")));
        }
        if (payload.has("usagePlatforms")) {
            // Not validated against a list. Platforms outlive any enum we would write, and refusing
            // a real platform because it postdates this code would be worse than accepting a typo.
            body.set("usagePlatforms", payload.get("usagePlatforms"));
        }
        copyIfPresent(payload, body, "rightsStartAt");
        copyIfPresent(payload, body, "rightsEndAt");
        copyIfPresent(payload, body, "exclusivityDays");
        copyIfPresent(payload, body, "usageRightsNote");

        requireOrderedTerm(body);
        return shape.campaignCreator(dao.put("/campaign-creators/" + engagementId, body));
    }

    /**
     * Licences lapsing within {@code days}, soonest first.
     *
     * <p><b>The half of rights tracking with operational value.</b> Recording terms is bookkeeping;
     * knowing what expires this month is what stops a brand running an ad it no longer has the
     * right to run — and it is a renewal prompt, which is where an agency's next month of revenue
     * comes from.
     *
     * <p>Only rows with an end date can appear. A perpetual grant has a start and no end, and an
     * unrecorded one has neither: both are correctly absent rather than surfacing as something to
     * chase.
     */
    public JsonNode expiringWithin(UUID brandId, int days) {
        Instant now = Instant.now();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("brandId", brandId.toString());
        query.put("from", now.toString());
        query.put("until", now.plus(days, ChronoUnit.DAYS).toString());

        JsonNode rows = dao.get("/campaign-creators/expiring-rights", query);
        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("withinDays", days);
        ArrayNode expiring = out.putArray("expiring");
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                expiring.add(shape.campaignCreator(row));
            }
        }
        out.put("count", expiring.size());
        return out;
    }

    // ---- helpers -------------------------------------------------------

    private ArrayNode validatedScopes(JsonNode node) {
        ArrayNode out = shape.objectMapper().createArrayNode();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usageScopes must be a list");
        }
        for (JsonNode value : node) {
            String scope = value.asText("").trim().toLowerCase(Locale.ROOT);
            if (!SCOPES.contains(scope)) {
                // Named in the message. "Invalid scope" without the list sends someone to the source
                // to find out what is allowed.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown usage scope '" + value.asText("") + "'. Allowed: "
                                + String.join(", ", List.copyOf(SCOPES)));
            }
            out.add(scope);
        }
        return out;
    }

    /**
     * A licence cannot end before it starts.
     *
     * <p>Checked because the pair is entered by hand and a reversed term is silently unenforceable:
     * every expiry query would skip it, so the one row that most needs chasing would be the one
     * that never appears.
     */
    private void requireOrderedTerm(JsonNode body) {
        if (!body.hasNonNull("rightsStartAt") || !body.hasNonNull("rightsEndAt")) {
            return;
        }
        try {
            Instant start = Instant.parse(body.get("rightsStartAt").asText());
            Instant end = Instant.parse(body.get("rightsEndAt").asText());
            if (end.isBefore(start)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The licence cannot end before it starts.");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rights dates must be ISO-8601 instants.");
        }
    }

    private void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        if (from.has(field)) {
            to.set(field, from.get(field));
        }
    }
}
