package com.influencer.dao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.influencer.dao.model.Campaign;
import com.influencer.dao.model.CampaignCreator;
import com.influencer.dao.model.CampaignTypeWorkflowStage;
import com.influencer.dao.model.Creator;
import com.influencer.dao.model.CreatorWorkflowTask;
import com.influencer.dao.model.ImportBatch;
import com.influencer.dao.repository.CampaignCreatorRepository;
import com.influencer.dao.repository.CampaignRepository;
import com.influencer.dao.repository.CampaignTypeWorkflowStageRepository;
import com.influencer.dao.repository.CreatorRepository;
import com.influencer.dao.repository.CreatorWorkflowTaskRepository;
import com.influencer.dao.repository.ImportBatchRepository;
import com.influencer.dao.repository.UserRepository;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ImportBatchHydrationService {
    private final ImportBatchRepository importBatchRepository;
    private final UserRepository userRepository;
    private final CampaignRepository campaignRepository;
    private final CreatorRepository creatorRepository;
    private final CampaignCreatorRepository campaignCreatorRepository;
    private final CreatorWorkflowTaskRepository creatorWorkflowTaskRepository;
    private final CampaignTypeWorkflowStageRepository workflowStageRepository;
    private final ObjectMapper objectMapper;

    public ImportBatchHydrationService(
            ImportBatchRepository importBatchRepository,
            UserRepository userRepository,
            CampaignRepository campaignRepository,
            CreatorRepository creatorRepository,
            CampaignCreatorRepository campaignCreatorRepository,
            CreatorWorkflowTaskRepository creatorWorkflowTaskRepository,
            CampaignTypeWorkflowStageRepository workflowStageRepository,
            ObjectMapper objectMapper) {
        this.importBatchRepository = importBatchRepository;
        this.userRepository = userRepository;
        this.campaignRepository = campaignRepository;
        this.creatorRepository = creatorRepository;
        this.campaignCreatorRepository = campaignCreatorRepository;
        this.creatorWorkflowTaskRepository = creatorWorkflowTaskRepository;
        this.workflowStageRepository = workflowStageRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public HydrateImportBatchResponse hydrate(UUID importBatchId, HydrateImportBatchRequest request) {
        return hydrateInternal(importBatchId, request, false);
    }

    @Transactional(readOnly = true)
    public HydrateImportBatchResponse preview(UUID importBatchId, HydrateImportBatchRequest request) {
        return hydrateInternal(importBatchId, request, true);
    }

    private HydrateImportBatchResponse hydrateInternal(UUID importBatchId, HydrateImportBatchRequest request, boolean forceDryRun) {
        ImportBatch importBatch = importBatchRepository.findById(importBatchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ImportBatch not found"));

        if (!userRepository.existsById(importBatch.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ImportBatch user does not exist");
        }

        if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rows cannot be empty");
        }

        List<ColumnMappingEntry> mappings = parseMappings(importBatch.getColumnMapping(), importBatch.getSourceFilename());
        if (mappings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ImportBatch columnMapping does not contain usable mappings");
        }

        HydrateImportBatchResponse response = new HydrateImportBatchResponse();
        response.setImportBatchId(importBatch.getId());
        response.setSourceFilename(importBatch.getSourceFilename());
        response.setDryRun(forceDryRun || request.isDryRun());

        int rowIndex = 0;
        for (Map<String, Object> row : request.getRows()) {
            rowIndex++;
            Map<String, Object> normalizedRow = normalizeRow(row);
            HydrationRowPlan plan = buildPlan(importBatch, mappings, normalizedRow, rowIndex);

            if (plan.isEmpty()) {
                response.incrementSkippedCount();
                continue;
            }

            if (!response.isDryRun()) {
                HydratedEntity<Campaign> campaignResult = null;
                HydratedEntity<Creator> creatorResult = null;

                if (plan.campaignValues != null) {
                    campaignResult = upsertCampaign(importBatch, plan.campaignValues);
                    campaignRepository.save(campaignResult.getEntity());
                    response.incrementUpdatedOrCreated(campaignResult.isCreated());
                }
                if (plan.creatorValues != null) {
                    creatorResult = upsertCreator(importBatch, plan.creatorValues);
                    creatorRepository.save(creatorResult.getEntity());
                    response.incrementUpdatedOrCreated(creatorResult.isCreated());
                }
                if (plan.campaignCreatorValues != null) {
                    UUID campaignId = resolveCampaignId(importBatch, plan.campaignValues, campaignResult, normalizedRow, rowIndex);
                    UUID creatorId = resolveCreatorId(importBatch, plan.creatorValues, creatorResult, normalizedRow, rowIndex);
                    plan.campaignCreatorValues.put("campaignId", campaignId);
                    plan.campaignCreatorValues.put("creatorId", creatorId);
                    HydratedEntity<CampaignCreator> campaignCreatorResult = upsertCampaignCreator(importBatch, plan.campaignCreatorValues);
                    CampaignCreator savedCampaignCreator = campaignCreatorRepository.save(campaignCreatorResult.getEntity());
                    upsertWorkflowItemTask(savedCampaignCreator, plan.campaignCreatorValues);
                    response.incrementUpdatedOrCreated(campaignCreatorResult.isCreated());
                }
            } else {
                response.incrementPlannedOperations(plan.operationCount());
            }
        }

        return response;
    }

    private HydrationRowPlan buildPlan(ImportBatch importBatch, List<ColumnMappingEntry> mappings, Map<String, Object> row, int rowIndex) {
        Map<String, Object> campaignValues = new LinkedHashMap<>();
        Map<String, Object> creatorValues = new LinkedHashMap<>();
        Map<String, Object> campaignCreatorValues = new LinkedHashMap<>();

        for (ColumnMappingEntry mapping : mappings) {
            String sourceColumn = firstNonBlank(mapping.getSpreadsheetColumn(), mapping.getSourceColumn());
            if (sourceColumn == null) {
                continue;
            }

            Object rawValue = lookupValue(row, sourceColumn);
            if (rawValue == null) {
                continue;
            }

            String targetEntity = normalizeEntityName(firstNonBlank(mapping.getTargetEntity(), inferDefaultEntity(importBatch.getSourceFilename())));
            String targetAttribute = toPropertyName(firstNonBlank(mapping.getTargetAttribute(), mapping.getTargetField()));

            if (targetAttribute == null) {
                continue;
            }

            Map<String, Object> targetValues = switch (targetEntity) {
                case "creator" -> creatorValues;
                case "campaign_creator" -> campaignCreatorValues;
                default -> campaignValues;
            };
            targetValues.put(targetAttribute, rawValue);
        }

        if (!campaignValues.isEmpty()) {
            campaignValues.putIfAbsent("userId", importBatch.getUserId());
        }
        if (!creatorValues.isEmpty()) {
            creatorValues.putIfAbsent("userId", importBatch.getUserId());
            creatorValues.putIfAbsent("importBatchId", importBatch.getId());
            creatorValues.putIfAbsent("source", importBatch.getSourceFilename());
        }
        if (!campaignCreatorValues.isEmpty()) {
            campaignCreatorValues.putIfAbsent("userId", importBatch.getUserId());
            campaignCreatorValues.putIfAbsent("importBatchId", importBatch.getId());
        }

        return new HydrationRowPlan(campaignValues, creatorValues, campaignCreatorValues);
    }

    private UUID resolveCampaignId(ImportBatch importBatch, Map<String, Object> campaignValues, HydratedEntity<Campaign> campaignResult, Map<String, Object> row, int rowIndex) {
        Object explicitId = campaignValues.get("id");
        if (explicitId != null) {
            UUID campaignId = toUuid(explicitId);
            if (!campaignRepository.existsById(campaignId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Row " + rowIndex + ": campaign id " + campaignId + " does not exist");
            }
            return campaignId;
        }

        if (campaignResult != null && campaignResult.getEntity() != null && campaignResult.getEntity().getId() != null) {
            return campaignResult.getEntity().getId();
        }

        String campaignName = stringValue(campaignValues.get("name"));
        if (campaignName == null) {
            campaignName = stringValue(lookupValue(row, "campaign_name"));
        }
        if (campaignName == null) {
            campaignName = stringValue(lookupValue(row, "name"));
        }
        if (campaignName == null) {
            return null;
        }

        Optional<Campaign> existing = campaignRepository.findByUserIdAndName(importBatch.getUserId(), campaignName);
        return existing.map(Campaign::getId).orElse(null);
    }

    private UUID resolveCreatorId(ImportBatch importBatch, Map<String, Object> creatorValues, HydratedEntity<Creator> creatorResult, Map<String, Object> row, int rowIndex) {
        Object explicitId = creatorValues.get("id");
        if (explicitId != null) {
            UUID creatorId = toUuid(explicitId);
            if (!creatorRepository.existsById(creatorId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Row " + rowIndex + ": creator id " + creatorId + " does not exist");
            }
            return creatorId;
        }

        if (creatorResult != null && creatorResult.getEntity() != null && creatorResult.getEntity().getId() != null) {
            return creatorResult.getEntity().getId();
        }

        String handle = stringValue(creatorValues.get("handle"));
        if (handle == null) {
            handle = stringValue(lookupValue(row, "handle"));
        }
        if (handle == null) {
            handle = stringValue(lookupValue(row, "creator_handle"));
        }
        if (handle == null) {
            return null;
        }

        String platform = stringValue(creatorValues.get("platform"));
        if (platform == null) {
            platform = "instagram";
        }

        Optional<Creator> existing = creatorRepository.findByUserIdAndPlatformAndHandle(importBatch.getUserId(), platform, handle);
        return existing.map(Creator::getId).orElse(null);
    }

    private HydratedEntity<Campaign> upsertCampaign(ImportBatch importBatch, Map<String, Object> values) {
        String name = stringValue(values.get("name"));
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaign.name is required");
        }

        Optional<Campaign> existingCampaign = campaignRepository.findByUserIdAndName(importBatch.getUserId(), name);
        Campaign campaign = existingCampaign.orElseGet(Campaign::new);

        campaign.setUserId(importBatch.getUserId());
        applyValues(campaign, values);
        campaign.setName(name);
        if (campaign.getStatus() == null) {
            campaign.setStatus("draft");
        }
        if (campaign.getCampaignType() == null) {
            campaign.setCampaignType("paid");
        }
        if (campaign.getCurrency() == null) {
            campaign.setCurrency("USD");
        }
        if (campaign.getPriority() == null) {
            campaign.setPriority("medium");
        }
        if (campaign.getDeliverablesRequired() == null) {
            campaign.setDeliverablesRequired(new String[0]);
        }
        if (campaign.getCustomAttributes() == null) {
            campaign.setCustomAttributes("{}");
        }
        return new HydratedEntity<>(campaign, !existingCampaign.isPresent());
    }

    private HydratedEntity<Creator> upsertCreator(ImportBatch importBatch, Map<String, Object> values) {
        String handle = stringValue(values.get("handle"));
        if (handle == null || handle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creator.handle is required");
        }

        String platform = stringValue(values.get("platform"));
        if (platform == null || platform.isBlank()) {
            platform = "instagram";
            values.put("platform", platform);
        }

        Optional<Creator> existingCreator = creatorRepository.findByUserIdAndPlatformAndHandle(importBatch.getUserId(), platform, handle);
        Creator creator = existingCreator.orElseGet(Creator::new);

        creator.setUserId(importBatch.getUserId());
        creator.setImportBatchId(importBatch.getId());
        applyValues(creator, values);
        creator.setHandle(handle);
        creator.setPlatform(platform);
        if (creator.getSource() == null) {
            creator.setSource(importBatch.getSourceFilename());
        }
        if (creator.getStatus() == null) {
            creator.setStatus("active");
        }
        if (creator.getTags() == null) {
            creator.setTags(new String[0]);
        }
        if (creator.getLanguages() == null) {
            creator.setLanguages(new String[0]);
        }
        if (creator.getContentCategories() == null) {
            creator.setContentCategories(new String[0]);
        }
        if (creator.getAudienceDemographics() == null) {
            creator.setAudienceDemographics("{}");
        }
        if (creator.getCurrency() == null) {
            creator.setCurrency("USD");
        }
        if (creator.getCustomAttributes() == null) {
            creator.setCustomAttributes("{}");
        }
        return new HydratedEntity<>(creator, !existingCreator.isPresent());
    }

    private HydratedEntity<CampaignCreator> upsertCampaignCreator(ImportBatch importBatch, Map<String, Object> values) {
        UUID campaignId = toUuid(values.get("campaignId"));
        UUID creatorId = toUuid(values.get("creatorId"));
        if (campaignId == null || creatorId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaign_creator requires campaignId and creatorId");
        }

        if (!campaignRepository.existsById(campaignId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId " + campaignId + " does not exist");
        }
        if (!creatorRepository.existsById(creatorId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creatorId " + creatorId + " does not exist");
        }

        Optional<CampaignCreator> existingCampaignCreator = campaignCreatorRepository.findByCampaignIdAndCreatorId(campaignId, creatorId);
        CampaignCreator campaignCreator = existingCampaignCreator.orElseGet(CampaignCreator::new);

        campaignCreator.setUserId(importBatch.getUserId());
        campaignCreator.setImportBatchId(importBatch.getId());
        campaignCreator.setCampaignId(campaignId);
        campaignCreator.setCreatorId(creatorId);
        applyValues(campaignCreator, values);
        campaignCreator.setCampaignId(campaignId);
        campaignCreator.setCreatorId(creatorId);
        if (campaignCreator.getOutreachStatus() == null) {
            campaignCreator.setOutreachStatus("new");
        }
        if (campaignCreator.getContractStatus() == null) {
            campaignCreator.setContractStatus("not_sent");
        }
        if (campaignCreator.getDeliverableStatus() == null) {
            campaignCreator.setDeliverableStatus("pending");
        }
        if (campaignCreator.getPaymentStatus() == null) {
            campaignCreator.setPaymentStatus("pending");
        }
        if (campaignCreator.getContentReviewStatus() == null) {
            campaignCreator.setContentReviewStatus("not_requested");
        }
        if (campaignCreator.getFeeCurrency() == null) {
            campaignCreator.setFeeCurrency("USD");
        }
        if (campaignCreator.getPerformanceMetrics() == null) {
            campaignCreator.setPerformanceMetrics("{}");
        }
        if (campaignCreator.getCustomAttributes() == null) {
            campaignCreator.setCustomAttributes("{}");
        }
        return new HydratedEntity<>(campaignCreator, !existingCampaignCreator.isPresent());
    }

    private void upsertWorkflowItemTask(CampaignCreator campaignCreator, Map<String, Object> values) {
        if (campaignCreator == null || campaignCreator.getId() == null) {
            return;
        }

        Optional<CreatorWorkflowTask> existingTask = creatorWorkflowTaskRepository
                .findByCampaignCreatorIdAndTaskType(campaignCreator.getId(), "workflow_item")
                .stream()
                .findFirst();

        CreatorWorkflowTask task = existingTask.orElseGet(CreatorWorkflowTask::new);
        task.setUserId(campaignCreator.getUserId());
        task.setCampaignCreatorId(campaignCreator.getId());
        task.setTaskType("workflow_item");
        task.setTitle("Workflow item");
        task.setDescription(campaignCreator.getNotes());
        task.setAgreedFee(campaignCreator.getAgreedFee());
        task.setTags(campaignCreator.getTags() == null ? new ArrayList<>() : campaignCreator.getTags());
        task.setDueAt(campaignCreator.getContentDueAt());
        task.setAssigneeActor("brand_owner");
        task.setStatus(task.getStatus() == null ? "todo" : task.getStatus());
        task.setPriority(task.getPriority() == null ? "medium" : task.getPriority());
        task.setMetadata(task.getMetadata() == null ? "{}" : task.getMetadata());
        task.setCreatedByActor(task.getCreatedByActor() == null ? "brand_owner" : task.getCreatedByActor());
        task.setStageKey(resolveWorkflowTaskStage(campaignCreator.getUserId(), campaignCreator.getCampaignId(), stringValue(values.get("stage"))));
        creatorWorkflowTaskRepository.save(task);
    }

    private String resolveWorkflowTaskStage(UUID userId, UUID campaignId, String requestedStage) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaign not found for campaignId: " + campaignId));

        String campaignType = normalizeCampaignType(campaign.getCampaignType());
        List<CampaignTypeWorkflowStage> activeStages = workflowStageRepository
                .findByUserIdAndCampaignTypeOrderByPositionAsc(userId, campaignType)
                .stream()
                .filter(stage -> stage != null && Boolean.TRUE.equals(stage.getIsActive()))
                .toList();

        if (activeStages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No active workflow stages configured for campaign type \"" + campaignType + "\".");
        }

        String normalizedRequestedStage = normalizePipelineStage(requestedStage);
        if (normalizedRequestedStage == null) {
            return activeStages.get(0).getStageKey();
        }

        boolean isAllowed = activeStages.stream().anyMatch(stage -> normalizedRequestedStage.equals(stage.getStageKey()));
        return isAllowed ? normalizedRequestedStage : activeStages.get(0).getStageKey();
    }

    private String normalizeCampaignType(String campaignType) {
        String normalized = campaignType == null ? "" : campaignType.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "paid" : normalized;
    }

    private List<ColumnMappingEntry> parseMappings(String columnMappingJson, String sourceFilename) {
        if (columnMappingJson == null || columnMappingJson.isBlank()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(columnMappingJson);
            if (root.isTextual()) {
                root = objectMapper.readTree(root.asText());
            }
            List<ColumnMappingEntry> mappings = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode node : root) {
                    mappings.add(objectMapper.treeToValue(node, ColumnMappingEntry.class));
                }
                return mappings;
            }

            if (root.has("recommendations") && root.get("recommendations").isArray()) {
                for (JsonNode node : root.get("recommendations")) {
                    mappings.add(objectMapper.treeToValue(node, ColumnMappingEntry.class));
                }
                return mappings;
            }

            if (root.isObject()) {
                root.fields().forEachRemaining(entry -> {
                    ColumnMappingEntry mapping = new ColumnMappingEntry();
                    mapping.setSpreadsheetColumn(entry.getKey());
                    if (entry.getValue().isTextual()) {
                        mapping.setTargetAttribute(entry.getValue().asText());
                        mapping.setTargetEntity(inferDefaultEntity(sourceFilename));
                    } else if (entry.getValue().isObject()) {
                        JsonNode value = entry.getValue();
                        mapping.setTargetEntity(textAt(value, "target_entity", inferDefaultEntity(sourceFilename)));
                        mapping.setTargetAttribute(textAt(value, "target_attribute", textAt(value, "target_field", null)));
                    }
                    mappings.add(mapping);
                });
            }

            return mappings;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to parse columnMapping JSON", exception);
        }
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (row == null) {
            return normalized;
        }

        row.forEach((key, value) -> normalized.put(normalizeKey(key), value));
        return normalized;
    }

    private Object lookupValue(Map<String, Object> normalizedRow, String key) {
        if (key == null) {
            return null;
        }
        return normalizedRow.get(normalizeKey(key));
    }

    private void applyValues(Object target, Map<String, Object> values) {
        values.forEach((propertyName, rawValue) -> {
            if (rawValue == null) {
                return;
            }

            BeanWrapperImpl wrapper = new BeanWrapperImpl(target);
            if (!wrapper.isWritableProperty(propertyName)) {
                return;
            }

            Class<?> targetType = wrapper.getPropertyType(propertyName);
            if (targetType == null) {
                return;
            }

            wrapper.setPropertyValue(propertyName, convertValue(rawValue, targetType));
        });
    }

    private Object convertValue(Object rawValue, Class<?> targetType) {
        if (rawValue == null) {
            return null;
        }

        if (targetType.isInstance(rawValue)) {
            return rawValue;
        }

        if (targetType == String.class) {
            if (rawValue instanceof Map || rawValue instanceof List) {
                try {
                    return objectMapper.writeValueAsString(rawValue);
                } catch (Exception exception) {
                    return String.valueOf(rawValue);
                }
            }
            return String.valueOf(rawValue);
        }

        String text = String.valueOf(rawValue).trim();
        if (text.isEmpty()) {
            return null;
        }

        if (targetType == UUID.class) {
            return UUID.fromString(text);
        }
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.valueOf(text);
        }
        if (targetType == Long.class || targetType == long.class) {
            return Long.valueOf(text);
        }
        if (targetType == BigDecimal.class) {
            return new BigDecimal(text);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.valueOf(text);
        }
        if (targetType == LocalDate.class) {
            return LocalDate.parse(text);
        }
        if (targetType == Instant.class) {
            return Instant.parse(text);
        }
        if (targetType == String[].class) {
            if (rawValue instanceof List<?> list) {
                return list.stream().map(String::valueOf).toArray(String[]::new);
            }
            if (rawValue.getClass().isArray()) {
                Object[] items = (Object[]) rawValue;
                String[] converted = new String[items.length];
                for (int index = 0; index < items.length; index++) {
                    converted[index] = String.valueOf(items[index]);
                }
                return converted;
            }
            return text.split("\\s*,\\s*");
        }

        return text;
    }

    private String normalizePipelineStage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = normalizeKey(value);
        if (normalized.equals("outreach") || normalized.equals("contacted") || normalized.equals("new")) {
            return "outreach";
        }
        if (normalized.equals("agreed")
                || normalized.equals("agree")
                || normalized.equals("negotiation")
                || normalized.equals("negotiating")
                || normalized.equals("contractsent")
                || normalized.equals("booked")) {
            return "agreed";
        }
        if (normalized.equals("shipped") || normalized.equals("productshipped") || normalized.equals("sampledelivered")) {
            return "shipped";
        }
        if (normalized.equals("posted") || normalized.equals("published") || normalized.equals("live")) {
            return "posted";
        }
        if (normalized.equals("paid") || normalized.equals("paymentcomplete") || normalized.equals("paymentcompleted")) {
            return "paid";
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported campaign_creator stage '" + value + "'. Allowed stages: outreach, agreed, shipped, posted, paid");
    }

    private String inferDefaultEntity(String sourceFilename) {
        String normalized = normalizeKey(sourceFilename);
        if (normalized.contains("campaigncreator") || normalized.contains("campaigncreatorrow") || normalized.contains("campaigncreatorsheet")) {
            return "campaign_creator";
        }
        if (normalized.contains("creator")) {
            return "creator";
        }
        if (normalized.contains("campaign")) {
            return "campaign";
        }
        return "creator";
    }

    private String normalizeEntityName(String value) {
        String normalized = normalizeKey(value);
        if (normalized.contains("campaigncreator") || normalized.contains("campaign_creator")) {
            return "campaign_creator";
        }
        if (normalized.contains("creator")) {
            return "creator";
        }
        if (normalized.contains("campaign")) {
            return "campaign";
        }
        return "campaign";
    }

    private String toPropertyName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] tokens = value.trim().split("[_\\-\\s]+");
        if (tokens.length == 0) {
            return value;
        }

        StringBuilder builder = new StringBuilder(tokens[0].toLowerCase(Locale.ROOT));
        for (int index = 1; index < tokens.length; index++) {
            if (tokens[index].isEmpty()) {
                continue;
            }
            builder.append(Character.toUpperCase(tokens[index].charAt(0)));
            if (tokens[index].length() > 1) {
                builder.append(tokens[index].substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.toString();
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private UUID toUuid(Object value) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        return UUID.fromString(text);
    }

    private String textAt(JsonNode node, String fieldName, String defaultValue) {
        JsonNode field = node.get(fieldName);
        return field != null && field.isTextual() ? field.asText() : defaultValue;
    }

    public static class HydrateImportBatchRequest {
        private List<Map<String, Object>> rows = List.of();
        private boolean dryRun;

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public void setRows(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }
    }

    public static class HydrateImportBatchResponse {
        private UUID importBatchId;
        private String sourceFilename;
        private boolean dryRun;
        private int createdCount;
        private int updatedCount;
        private int skippedCount;
        private int plannedOperationCount;

        public UUID getImportBatchId() {
            return importBatchId;
        }

        public void setImportBatchId(UUID importBatchId) {
            this.importBatchId = importBatchId;
        }

        public String getSourceFilename() {
            return sourceFilename;
        }

        public void setSourceFilename(String sourceFilename) {
            this.sourceFilename = sourceFilename;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public int getCreatedCount() {
            return createdCount;
        }

        public int getUpdatedCount() {
            return updatedCount;
        }

        public int getSkippedCount() {
            return skippedCount;
        }

        public int getPlannedOperationCount() {
            return plannedOperationCount;
        }

        public void incrementUpdatedOrCreated(boolean created) {
            if (created) {
                createdCount++;
            } else {
                updatedCount++;
            }
        }

        public void incrementSkippedCount() {
            skippedCount++;
        }

        public void incrementPlannedOperations(int amount) {
            plannedOperationCount += amount;
        }
    }

    public static class ColumnMappingEntry {
        @JsonAlias({"spreadsheet_column"})
        private String spreadsheetColumn;
        @JsonAlias({"source_column"})
        private String sourceColumn;
        @JsonAlias({"target_entity"})
        private String targetEntity;
        @JsonAlias({"target_attribute"})
        private String targetAttribute;
        @JsonAlias({"target_field"})
        private String targetField;

        public String getSpreadsheetColumn() {
            return spreadsheetColumn;
        }

        public void setSpreadsheetColumn(String spreadsheetColumn) {
            this.spreadsheetColumn = spreadsheetColumn;
        }

        public String getSourceColumn() {
            return sourceColumn;
        }

        public void setSourceColumn(String sourceColumn) {
            this.sourceColumn = sourceColumn;
        }

        public String getTargetEntity() {
            return targetEntity;
        }

        public void setTargetEntity(String targetEntity) {
            this.targetEntity = targetEntity;
        }

        public String getTargetAttribute() {
            return targetAttribute;
        }

        public void setTargetAttribute(String targetAttribute) {
            this.targetAttribute = targetAttribute;
        }

        public String getTargetField() {
            return targetField;
        }

        public void setTargetField(String targetField) {
            this.targetField = targetField;
        }
    }

    private static class HydrationRowPlan {
        private final ResolvedValues campaignValues;
        private final ResolvedValues creatorValues;
        private final ResolvedValues campaignCreatorValues;

        private HydrationRowPlan(Map<String, Object> campaignValues, Map<String, Object> creatorValues, Map<String, Object> campaignCreatorValues) {
            this.campaignValues = campaignValues.isEmpty() ? null : new ResolvedValues(campaignValues);
            this.creatorValues = creatorValues.isEmpty() ? null : new ResolvedValues(creatorValues);
            this.campaignCreatorValues = campaignCreatorValues.isEmpty() ? null : new ResolvedValues(campaignCreatorValues);
        }

        private boolean isEmpty() {
            return campaignValues == null && creatorValues == null && campaignCreatorValues == null;
        }

        private int operationCount() {
            int count = 0;
            if (campaignValues != null) {
                count++;
            }
            if (creatorValues != null) {
                count++;
            }
            if (campaignCreatorValues != null) {
                count++;
            }
            return count;
        }
    }

    private static class ResolvedValues extends LinkedHashMap<String, Object> {
        private UUID resolvedId;

        private ResolvedValues(Map<String, Object> values) {
            super(values);
            Object id = values.get("id");
            if (id != null) {
                this.resolvedId = UUID.fromString(String.valueOf(id));
            }
        }

        private UUID getResolvedId() {
            return resolvedId;
        }
    }

    private static class HydratedEntity<T> {
        private final T entity;
        private final boolean created;

        private HydratedEntity(T entity, boolean created) {
            this.entity = entity;
            this.created = created;
        }

        private T getEntity() {
            return entity;
        }

        private boolean isCreated() {
            return created;
        }
    }
}