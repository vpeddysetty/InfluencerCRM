package com.influencer.webe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ResponseShapeService {
    private final ObjectMapper objectMapper;

    public ResponseShapeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode campaignsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(campaign(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode campaign(JsonNode source) {
        return pick(source, "id", "userId", "name", "budget", "status", "campaignType", "customAttributes", "createdAt", "updatedAt");
    }

    public JsonNode workflowBoardsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(workflowBoard(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode workflowBoard(JsonNode source) {
        return pick(source, "id", "userId", "name", "startDate", "endDate", "isActive", "position", "createdAt", "updatedAt");
    }

    public JsonNode workflowBoardStagesList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(workflowBoardStage(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode workflowBoardStage(JsonNode source) {
        return pick(source, "id", "userId", "boardId", "stageName", "position", "createdAt", "updatedAt");
    }

    public JsonNode workflowCardsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(workflowCard(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode workflowCard(JsonNode source) {
        ObjectNode out = pick(source, "id", "userId", "campaignId", "creatorId", "boardId", "stageId",
                "name", "status", "feeCurrency", "notes", "position", "createdAt", "updatedAt");
        // Always expose placement keys (null when unassigned) so the UI can rely on them.
        if (!out.has("boardId")) {
            out.putNull("boardId");
        }
        if (!out.has("stageId")) {
            out.putNull("stageId");
        }
        if (source != null && source.hasNonNull("agreedFee")) {
            out.set("agreedFee", source.get("agreedFee"));
        }
        if (source != null && source.has("tags") && !source.get("tags").isNull()) {
            JsonNode tagsNode = source.get("tags");
            if (tagsNode.isTextual()) {
                try {
                    JsonNode parsed = objectMapper.readTree(tagsNode.asText());
                    out.set("tags", parsed.isArray() ? parsed : objectMapper.createArrayNode());
                } catch (Exception ignored) {
                    out.set("tags", objectMapper.createArrayNode());
                }
            } else if (tagsNode.isArray()) {
                out.set("tags", tagsNode);
            }
        }
        if (!out.has("tags")) {
            out.set("tags", objectMapper.createArrayNode());
        }
        return out;
    }

    public JsonNode creatorsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(creator(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode creator(JsonNode source) {
        return pick(source, "id", "userId", "name", "handle", "platform", "email", "customAttributes", "createdAt", "updatedAt");
    }

    public JsonNode campaignCreatorsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(campaignCreator(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode campaignCreator(JsonNode source) {
        ObjectNode out = pick(source, "id", "userId", "campaignId", "creatorId", "notes", "createdAt", "updatedAt");
        if (source != null && source.hasNonNull("agreedFee")) {
            out.set("fee", source.get("agreedFee"));
        } else if (source != null && source.hasNonNull("fee")) {
            out.set("fee", source.get("fee"));
        }
        if (source != null && source.hasNonNull("contentDueAt")) {
            out.set("dueDate", source.get("contentDueAt"));
        } else if (source != null && source.hasNonNull("dueDate")) {
            out.set("dueDate", source.get("dueDate"));
        }
        if (source != null && source.has("tags") && !source.get("tags").isNull()) {
            JsonNode tagsNode = source.get("tags");
            if (tagsNode.isTextual()) {
                try {
                    JsonNode parsed = objectMapper.readTree(tagsNode.asText());
                    out.set("tags", parsed.isArray() ? parsed : objectMapper.createArrayNode());
                } catch (Exception ignored) {
                    out.set("tags", objectMapper.createArrayNode());
                }
            } else if (tagsNode.isArray()) {
                out.set("tags", tagsNode);
            }
        }
        if (!out.has("tags")) {
            out.set("tags", objectMapper.createArrayNode());
        }
        return out;
    }

    public JsonNode importBatchesList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(importBatch(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode importBatch(JsonNode source) {
        return pick(source, "id", "userId", "status", "sourceFilename", "sourceFileStored", "columnMapping", "rowCount", "createdAt", "updatedAt");
    }

    public JsonNode importDiscoverResult(JsonNode source) {
        return source == null || source.isNull() ? objectMapper.createObjectNode() : source;
    }

    public JsonNode importPreviewResult(JsonNode source) {
        return source == null || source.isNull() ? objectMapper.createObjectNode() : source;
    }

    public JsonNode importHydrateResult(JsonNode source) {
        return source == null || source.isNull() ? objectMapper.createObjectNode() : source;
    }

    public JsonNode campaignCodesList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(campaignCode(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode campaignCode(JsonNode source) {
        ObjectNode out = pick(source, "id", "userId", "campaignId", "creatorId", "campaignCreatorId",
                "code", "codeType", "landingUrl", "startsAt", "endsAt", "isActive",
                "marketplaceConnectionId", "discountType", "discountValue",
                "commissionType", "commissionValue", "channel", "refSlug",
                "externalCouponId", "syncStatus",
                "publicSlug", "personalBlurb", "embedUrl", "personalizationStatus",
                "createdAt", "updatedAt");
        if (!out.has("personalizationStatus")) {
            out.put("personalizationStatus", "none");
        }
        // syncStatus is always meaningful for the UI (defaults to "local" server-side).
        if (!out.has("syncStatus")) {
            out.put("syncStatus", "local");
        }
        return out;
    }

    public JsonNode saleAttributionsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(saleAttribution(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode saleAttribution(JsonNode source) {
        ObjectNode out = pick(source,
                "id", "userId", "campaignCodeId", "campaignId", "creatorId", "campaignCreatorId",
                "orderId", "orderLineId", "saleAmount", "discountAmount", "netAmount", "commissionAmount",
                "currency", "occurredAt", "trackedAt", "createdAt", "updatedAt");

        String platform = normalizeEnum(readText(source, "platform"),
                Set.of("instagram", "tiktok", "youtube", "shopify", "amazon", "woocommerce", "direct", "other"),
                Map.of("ig", "instagram", "yt", "youtube"),
                "direct");
        String status = normalizeEnum(readText(source, "status"),
                Set.of("pending", "attributed", "refunded", "cancelled"),
                Map.of("refund", "refunded", "canceled", "cancelled"),
                "pending");

        out.put("platform", platform);
        out.put("status", status);
        return out;
    }

    // ---- marketplace connections --------------------------------------
    public JsonNode marketplaceConnectionsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(marketplaceConnection(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode marketplaceConnection(JsonNode source) {
        // Deliberately omit credentialsEncrypted — never expose secrets to the UI.
        ObjectNode out = pick(source, "id", "userId", "providerKey", "displayName", "status",
                "externalAccountRef", "syncCursor", "metadata", "createdAt", "updatedAt");
        if (!out.has("status")) {
            out.put("status", "connected");
        }
        return out;
    }

    // ---- influencer commissions ---------------------------------------
    public JsonNode commissionsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(commission(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode commission(JsonNode source) {
        ObjectNode out = pick(source, "id", "userId", "attributionId", "creatorId", "campaignId",
                "grossSale", "commissionAmount", "currency", "status", "approvedAt", "payoutId",
                "createdAt", "updatedAt");
        if (!out.has("status")) {
            out.put("status", "pending");
        }
        return out;
    }

    // ---- influencer payouts -------------------------------------------
    public JsonNode payoutsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(payout(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode payout(JsonNode source) {
        ObjectNode out = pick(source, "id", "userId", "creatorId", "periodStart", "periodEnd",
                "totalAmount", "currency", "method", "providerKey", "providerRef", "status", "notes",
                "paidAt", "createdAt", "updatedAt");
        if (!out.has("status")) {
            out.put("status", "draft");
        }
        return out;
    }

    // ---- daily attribution stats --------------------------------------
    public JsonNode dailyStatsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(dailyStat(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode dailyStat(JsonNode source) {
        return pick(source, "id", "userId", "day", "creatorId", "campaignId", "channel",
                "clicks", "orders", "grossSales", "discounts", "commission", "refunds",
                "createdAt", "updatedAt");
    }

    // ---- campaign briefs (content Phase 1) ----------------------------
    public JsonNode campaignBriefsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(campaignBrief(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode campaignBrief(JsonNode source) {
        ObjectNode out = pick(source, "id", "userId", "campaignId", "disclosureText",
                "status", "createdAt", "updatedAt");
        out.set("content", parseJsonOrDefault(source, "content", objectMapper.createObjectNode()));
        out.set("assets", parseJsonOrDefault(source, "assets", objectMapper.createArrayNode()));
        out.set("hashtags", parseJsonOrDefault(source, "hashtags", objectMapper.createArrayNode()));
        if (!out.has("status")) {
            out.put("status", "draft");
        }
        return out;
    }

    // ---- landing templates (content Phase 2) --------------------------
    public JsonNode landingTemplatesList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(landingTemplate(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode landingTemplate(JsonNode source) {
        ObjectNode out = pick(source, "id", "userId", "campaignId", "publicSlug", "name",
                "status", "createdAt", "updatedAt");
        out.set("blocks", parseJsonOrDefault(source, "blocks", objectMapper.createArrayNode()));
        out.set("theme", parseJsonOrDefault(source, "theme", objectMapper.createObjectNode()));
        if (!out.has("status")) {
            out.put("status", "draft");
        }
        return out;
    }

    /** Read a jsonb field that the DAO may return as a JSON string or a live node. */
    private JsonNode parseJsonOrDefault(JsonNode source, String field, JsonNode fallback) {
        if (source == null || !source.has(field) || source.get(field).isNull()) {
            return fallback;
        }
        JsonNode node = source.get(field);
        if (node.isTextual()) {
            try {
                return objectMapper.readTree(node.asText());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return node;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public List<String> asStringList(JsonNode source) {
        List<String> values = new ArrayList<>();
        if (source == null || !source.isArray()) {
            return values;
        }
        for (JsonNode item : source) {
            if (item != null && !item.isNull()) {
                String value = item.asText();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private ArrayNode asArray(JsonNode source) {
        ArrayNode out = objectMapper.createArrayNode();
        if (source == null || source.isNull()) {
            return out;
        }
        if (source.isArray()) {
            for (JsonNode item : source) {
                out.add(item);
            }
            return out;
        }
        if (source.has("items") && source.get("items").isArray()) {
            for (JsonNode item : source.get("items")) {
                out.add(item);
            }
            return out;
        }
        return out;
    }

    private JsonNode paginateIfRequested(ArrayNode items, Integer page, Integer size) {
        if (page == null && size == null) {
            return items;
        }

        int resolvedSize = size == null ? 20 : Math.max(1, size);
        int resolvedPage = page == null ? 1 : Math.max(1, page);
        int total = items.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / resolvedSize);
        int start = Math.min((resolvedPage - 1) * resolvedSize, total);
        int end = Math.min(start + resolvedSize, total);

        ArrayNode slice = objectMapper.createArrayNode();
        for (int i = start; i < end; i++) {
            slice.add(items.get(i));
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("items", slice);
        response.put("page", resolvedPage);
        response.put("size", resolvedSize);
        response.put("total", total);
        response.put("totalPages", totalPages);
        response.put("hasPrevious", resolvedPage > 1);
        response.put("hasNext", resolvedPage < totalPages);
        return response;
    }

    private ObjectNode pick(JsonNode source, String... fields) {
        ObjectNode out = objectMapper.createObjectNode();
        if (source == null || source.isNull()) {
            return out;
        }
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null && !value.isNull()) {
                out.set(field, value);
            }
        }
        return out;
    }

    private String readText(JsonNode source, String field) {
        if (source == null || source.get(field) == null || source.get(field).isNull()) {
            return null;
        }
        String value = source.get(field).asText();
        return value == null ? null : value.trim().toLowerCase();
    }

    private String normalizeEnum(String raw,
                                 Set<String> allowed,
                                 Map<String, String> aliases,
                                 String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String aliasResolved = aliases.getOrDefault(raw, raw);
        return allowed.contains(aliasResolved) ? aliasResolved : fallback;
    }
}
