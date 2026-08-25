package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Brand-saved page templates (roadmap PR-39, piece D).
 *
 * <p><b>What a saved template is:</b> the ordered section list of a page the brand liked, with the
 * campaign-specific values removed. It is not a rendered page and holds no HTML — see
 * {@code V43__brand_page_templates.sql} for why that matters when the design system changes.
 *
 * <p><b>What saving strips, and why it is enforced here.</b> The editor also strips before it
 * sends (so the user can see what will be saved), but this is the boundary that has to hold: a
 * template that kept the creator's name would silently credit the wrong person on the next
 * campaign's public page, under the brand's own name. That is a mistake that reaches the outside
 * world, so it is re-applied server-side rather than trusted from a client that could be an older
 * bundle, a replayed request, or a script.
 */
@Service
public class BrandPageTemplateService {

    /**
     * Fields cleared on save, by section type.
     *
     * <p>Only identity is removed. The creator's QUOTE stays — it is a sentence about the product
     * that the brand may reasonably reuse as a starting point — while the name, handle, platform
     * and portrait all belong to one person on one campaign.
     *
     * <p>Coupon tokens are deliberately NOT stripped: `{{coupon.code}}` resolving to whoever the
     * next campaign's creator is, is the entire purpose of a token.
     */
    private static final Map<String, String[]> STRIPPED_FIELDS = Map.of(
            "creator", new String[]{"name", "handle", "platform", "portrait"});

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public BrandPageTemplateService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    /** This brand's saved templates, newest first. */
    public JsonNode list(UUID brandId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        JsonNode rows = dao.get("/brand-page-templates", q);
        ArrayNode out = shape.objectMapper().createArrayNode();
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                out.add(project(row));
            }
        }
        return out;
    }

    /**
     * Save the given sections as a reusable template.
     *
     * @param payload {@code { name, sections: [...] }}
     */
    public JsonNode save(UUID brandId, UUID userId, ObjectNode payload) {
        String name = payload.path("name").asText("").trim();
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A template name is required");
        }
        if (name.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That name is too long");
        }

        JsonNode sections = payload.get("sections");
        if (sections == null || !sections.isArray() || sections.isEmpty()) {
            // A template with no sections would sit in the picker producing an empty page. Better
            // to refuse it than to store something whose only effect is to waste a click.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "There is nothing on this page to save as a template");
        }

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("name", name);
        if (userId != null) {
            body.put("createdByUserId", userId.toString());
        }
        try {
            body.put("sections", shape.objectMapper().writeValueAsString(strip(sections)));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That page could not be saved as a template");
        }
        return project(dao.post("/brand-page-templates", body));
    }

    /**
     * Whether this brand already has a template of that name.
     *
     * <p>Exists so the plan check can tell a replacement from a new one: an account at its limit
     * must still be able to overwrite the templates it already owns, which is the same distinction
     * the landing-page upsert draws.
     *
     * <p>Case-insensitive, matching {@code uq_brand_page_templates_name} — the DAO would treat
     * "Spring launch" and "spring launch" as the same row, so the check has to agree or the limit
     * would fire on a save the storage layer was about to accept as a replacement.
     */
    public boolean existsByName(UUID brandId, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String wanted = name.trim();
        for (JsonNode existing : list(brandId)) {
            if (wanted.equalsIgnoreCase(existing.path("name").asText(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Delete one saved template.
     *
     * <p>The brand is on the query string because the DAO scopes the lookup by it — {@code delete}
     * here takes no query map, so it is appended by hand. Without it, a guessed UUID would reach
     * another brand's template.
     */
    public void delete(UUID brandId, UUID templateId) {
        dao.delete("/brand-page-templates/" + templateId + "?brandId=" + brandId);
    }

    /**
     * Remove the values that belong to one campaign rather than to the brand.
     *
     * <p>Unknown section types pass through untouched rather than being dropped: a template saved
     * by a newer build than the one serving the request should survive the round trip. Dropping
     * what it does not recognise would quietly reshape the page.
     */
    private ArrayNode strip(JsonNode sections) {
        ArrayNode out = shape.objectMapper().createArrayNode();
        for (JsonNode section : sections) {
            ObjectNode copy = section.deepCopy();
            String type = copy.path("type").asText("");
            String[] clear = STRIPPED_FIELDS.get(type);
            if (clear != null && copy.get("fields") != null && copy.get("fields").isObject()) {
                ObjectNode fields = (ObjectNode) copy.get("fields");
                for (String field : clear) {
                    if (fields.has(field)) {
                        // Emptied rather than removed, so the editor still renders the input and
                        // the brand can see the field exists and is theirs to fill.
                        fields.put(field, "");
                    }
                }
            }
            out.add(copy);
        }
        return out;
    }

    /** The shape the UI reads. `sections` is parsed, since the DAO hands jsonb back as a string. */
    private JsonNode project(JsonNode row) {
        ObjectNode out = shape.objectMapper().createObjectNode();
        if (row == null) {
            return out;
        }
        out.put("id", row.path("id").asText(""));
        out.put("name", row.path("name").asText(""));
        out.put("createdAt", row.path("createdAt").asText(""));
        JsonNode sections = row.get("sections");
        if (sections != null && sections.isTextual()) {
            try {
                out.set("sections", shape.objectMapper().readTree(sections.asText()));
            } catch (Exception e) {
                out.set("sections", shape.objectMapper().createArrayNode());
            }
        } else {
            out.set("sections", sections == null || sections.isNull()
                    ? shape.objectMapper().createArrayNode() : sections);
        }
        return out;
    }
}
