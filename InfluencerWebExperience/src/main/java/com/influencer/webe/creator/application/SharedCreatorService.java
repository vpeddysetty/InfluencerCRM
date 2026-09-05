package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.application.BrandAccessPort;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Where else this agency already works with the same creator (roadmap PR-66).
 *
 * <p><b>The insight only an agency-aware tool can offer.</b> An agency knows perfectly well that it
 * books the same person for three clients; the product could not say so, because
 * {@code uq_creators_brand_platform_handle} makes each brand's creator a separate row. That
 * separation is correct — one client must not see another's notes, rates or vetting decisions on a
 * shared creator — so this does not merge the rows. It reports that they exist, and what each brand
 * agreed, to a caller who can already open both.
 *
 * <p><b>Scoped to the caller's own brands, never to the platform.</b> "Which brands work with this
 * handle" is a question whose answer belongs to other customers, and it is not the question asked
 * here: the brand list comes from {@link BrandAccessPort}, the same answer the brand switcher and
 * `PR-64` use, and the DAO only sorts rows within it. A marketer granted one brand sees one brand's
 * row and learns nothing about anyone else's roster.
 *
 * <p><b>Read-only, and it changes no tenancy rule.</b> Nothing here writes, and nothing here makes
 * a creator reachable that was not already reachable — it answers a question the caller could have
 * answered by opening each workspace in turn.
 */
@Service
public class SharedCreatorService {

    private final DaoGatewayClient dao;
    private final BrandAccessPort brandAccess;
    private final ResponseShapeService shape;

    public SharedCreatorService(DaoGatewayClient dao, BrandAccessPort brandAccess,
                                ResponseShapeService shape) {
        this.dao = dao;
        this.brandAccess = brandAccess;
        this.shape = shape;
    }

    /**
     * Other brands of the caller's that work with the same creator.
     *
     * @param userId    the CALLER, from the verified token
     * @param brandId   the brand whose creator record is open — excluded from the result, because
     *                  telling someone the record they are looking at exists is not an insight
     * @param creatorId the creator record being viewed; its handle and platform are the match key
     */
    public JsonNode alsoWorkingWith(UUID userId, UUID brandId, UUID creatorId) {
        ObjectNode out = shape.objectMapper().createObjectNode();
        ArrayNode others = out.putArray("alsoAt");

        JsonNode creator = dao.get("/creators/" + creatorId, new LinkedHashMap<>());
        if (creator == null || !brandId.toString().equals(creator.path("brandId").asText(null))) {
            // Not this brand's creator. Answering anything at all here would turn a creator id into
            // a probe for which handles exist elsewhere.
            return out;
        }

        String handle = text(creator, "handle");
        String platform = text(creator, "platform");
        if (handle == null || platform == null) {
            return out;
        }

        // The caller's own brands, minus the one being viewed. This list is the security boundary:
        // the DAO sorts within it and computes nothing.
        Map<UUID, String> reachable = new LinkedHashMap<>();
        for (BrandAccessPort.BrandAccess access : brandAccess.findAccessibleBrandsForPort(userId)) {
            if (!access.brandId().equals(brandId)) {
                reachable.put(access.brandId(), access.brandName());
            }
        }
        if (reachable.isEmpty()) {
            return out;
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("platform", platform);
        query.put("handle", handle);
        query.put("brandIds", reachable.keySet().stream()
                .map(UUID::toString)
                .collect(Collectors.joining(",")));

        JsonNode rows = dao.get("/creators/across-brands", query);
        if (rows == null || !rows.isArray()) {
            return out;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String otherBrandId = row.path("brandId").asText(null);
            // Belt and braces. The DAO was given only reachable brands, but a row for anything else
            // must never render: this is the one place a scoping mistake would be visible to a user
            // rather than caught by a test.
            if (otherBrandId == null || !reachable.containsKey(UUID.fromString(otherBrandId))
                    || !seen.add(otherBrandId)) {
                continue;
            }
            ObjectNode entry = others.addObject();
            entry.put("brandId", otherBrandId);
            entry.put("brandName", reachable.get(UUID.fromString(otherBrandId)));
            entry.put("creatorId", row.path("id").asText(null));
            // The rate each brand agreed, which is the actually useful part: an agency about to
            // negotiate wants to know what it already pays this person elsewhere. Absent stays
            // absent rather than becoming zero -- "no rate recorded" and "works for nothing" are
            // very different things to carry into a negotiation.
            putIfPresent(entry, row, "preferredRate");
            putIfPresent(entry, row, "minimumFee");
            putIfPresent(entry, row, "vettingStatus");
        }
        return out;
    }

    private void putIfPresent(ObjectNode target, JsonNode source, String field) {
        if (source.hasNonNull(field)) {
            target.put(field, source.get(field).asText());
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }
}
