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

    public JsonNode campaignTypeWorkflowStagesList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(campaignTypeWorkflowStage(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode campaignTypeWorkflowStage(JsonNode source) {
        return pick(source, "id", "userId", "campaignType", "stageKey", "stageLabel", "position", "isActive", "createdAt", "updatedAt");
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

    public JsonNode workflowTasksList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(workflowTask(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode workflowTask(JsonNode source) {
        ObjectNode out = pick(source, "id", "userId", "campaignCreatorId", "taskType", "stageKey", "title", "description", "assigneeActor", "assigneeCreatorId", "status", "priority", "dueAt", "completedAt", "createdAt", "updatedAt");
        if (source != null && source.hasNonNull("agreedFee")) {
            out.set("fee", source.get("agreedFee"));
        } else if (source != null && source.hasNonNull("fee")) {
            out.set("fee", source.get("fee"));
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

    public JsonNode workflowApprovalsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(workflowApproval(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode workflowApproval(JsonNode source) {
        return pick(source, "id", "userId", "campaignCreatorId", "reviewRound", "submissionUrl", "submittedByActor", "submittedAt", "decision", "decidedByActor", "decidedAt", "createdAt");
    }

    public JsonNode workflowPaymentsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(workflowPayment(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode workflowPayment(JsonNode source) {
        return pick(source, "id", "userId", "campaignCreatorId", "amount", "currency", "status", "scheduledAt", "paidAt", "failedAt", "createdAt", "updatedAt");
    }

    public JsonNode workflowEventsList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(workflowEvent(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode workflowEvent(JsonNode source) {
        return pick(source, "id", "userId", "campaignCreatorId", "actor", "eventType", "eventBody", "eventData", "createdAt");
    }

    public JsonNode campaignCodesList(JsonNode source, Integer page, Integer size) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode item : asArray(source)) {
            out.add(campaignCode(item));
        }
        return paginateIfRequested(out, page, size);
    }

    public JsonNode campaignCode(JsonNode source) {
        return pick(source, "id", "userId", "campaignId", "creatorId", "campaignCreatorId", "code", "codeType", "landingUrl", "startsAt", "endsAt", "isActive", "createdAt", "updatedAt");
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
