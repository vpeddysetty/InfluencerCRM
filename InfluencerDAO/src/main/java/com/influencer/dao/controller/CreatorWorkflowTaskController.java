package com.influencer.dao.controller;

import com.influencer.dao.model.Campaign;
import com.influencer.dao.model.CampaignCreator;
import com.influencer.dao.model.CampaignTypeWorkflowStage;
import com.influencer.dao.model.CreatorWorkflowTask;
import com.influencer.dao.repository.CampaignCreatorRepository;
import com.influencer.dao.repository.CampaignRepository;
import com.influencer.dao.repository.CampaignTypeWorkflowStageRepository;
import com.influencer.dao.repository.CreatorWorkflowTaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/creator-workflow-tasks")
public class CreatorWorkflowTaskController {
    private final CreatorWorkflowTaskRepository repository;
    private final CampaignCreatorRepository campaignCreatorRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignTypeWorkflowStageRepository workflowStageRepository;

    public CreatorWorkflowTaskController(CreatorWorkflowTaskRepository repository,
                                         CampaignCreatorRepository campaignCreatorRepository,
                                         CampaignRepository campaignRepository,
                                         CampaignTypeWorkflowStageRepository workflowStageRepository) {
        this.repository = repository;
        this.campaignCreatorRepository = campaignCreatorRepository;
        this.campaignRepository = campaignRepository;
        this.workflowStageRepository = workflowStageRepository;
    }

    @GetMapping
    public List<CreatorWorkflowTask> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignCreatorId,
            @RequestParam(required = false) String taskType) {
        boolean hasTaskType = taskType != null && !taskType.isBlank();
        if (userId != null && campaignCreatorId != null && hasTaskType) {
            return repository.findByUserIdAndCampaignCreatorIdAndTaskType(userId, campaignCreatorId, taskType.trim().toLowerCase(Locale.ROOT));
        }
        if (userId != null && campaignCreatorId != null) {
            return repository.findByUserIdAndCampaignCreatorId(userId, campaignCreatorId);
        }
        if (userId != null && hasTaskType) {
            return repository.findByUserIdAndTaskType(userId, taskType.trim().toLowerCase(Locale.ROOT));
        }
        if (userId != null) {
            return repository.findByUserId(userId);
        }
        if (campaignCreatorId != null && hasTaskType) {
            return repository.findByCampaignCreatorIdAndTaskType(campaignCreatorId, taskType.trim().toLowerCase(Locale.ROOT));
        }
        if (campaignCreatorId != null) {
            return repository.findByCampaignCreatorId(campaignCreatorId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public CreatorWorkflowTask findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("CreatorWorkflowTask not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorWorkflowTask create(@RequestBody CreatorWorkflowTask task) {
        applyDefaults(task);
        validateWorkflowItemStage(task);
        return repository.save(task);
    }

    @PutMapping("/{id}")
    public CreatorWorkflowTask update(@PathVariable UUID id, @RequestBody CreatorWorkflowTask task) {
        CreatorWorkflowTask existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CreatorWorkflowTask not found"));
        existing.setUserId(task.getUserId());
        existing.setCampaignCreatorId(task.getCampaignCreatorId());
        existing.setTaskType(task.getTaskType());
        existing.setStageKey(task.getStageKey());
        existing.setTitle(task.getTitle());
        existing.setDescription(task.getDescription());
        existing.setAssigneeActor(task.getAssigneeActor());
        existing.setAssigneeCreatorId(task.getAssigneeCreatorId());
        existing.setAgreedFee(task.getAgreedFee());
        existing.setTags(task.getTags());
        existing.setStatus(task.getStatus());
        existing.setPriority(task.getPriority());
        existing.setDueAt(task.getDueAt());
        existing.setStartedAt(task.getStartedAt());
        existing.setCompletedAt(task.getCompletedAt());
        existing.setMetadata(task.getMetadata());
        existing.setCreatedByActor(task.getCreatedByActor());
        applyDefaults(existing);
        validateWorkflowItemStage(existing);
        return repository.save(existing);
    }

    private void applyDefaults(CreatorWorkflowTask task) {
        if (task.getTaskType() == null || task.getTaskType().isBlank()) {
            task.setTaskType("task");
        } else {
            task.setTaskType(task.getTaskType().trim().toLowerCase(Locale.ROOT));
        }
        if (task.getTags() == null) {
            task.setTags(new ArrayList<>());
        }
        if (task.getMetadata() == null || task.getMetadata().isBlank()) {
            task.setMetadata("{}");
        }
        if (task.getTaskType().equals("workflow_item") && (task.getTitle() == null || task.getTitle().isBlank())) {
            task.setTitle("Workflow item");
        }
    }

    private void validateWorkflowItemStage(CreatorWorkflowTask task) {
        if (task == null || !"workflow_item".equals(task.getTaskType())) {
            return;
        }
        if (task.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required to validate workflow task stage.");
        }
        if (task.getCampaignCreatorId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignCreatorId is required to validate workflow task stage.");
        }

        CampaignCreator campaignCreator = campaignCreatorRepository.findById(task.getCampaignCreatorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "CampaignCreator not found for campaignCreatorId: " + task.getCampaignCreatorId()));
        Campaign campaign = campaignRepository.findById(campaignCreator.getCampaignId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaign not found for campaignCreatorId: " + task.getCampaignCreatorId()));
        if (!task.getUserId().equals(campaign.getUserId()) || !task.getUserId().equals(campaignCreator.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignCreatorId does not belong to userId.");
        }

        String campaignType = normalizeCampaignType(campaign.getCampaignType());
        List<CampaignTypeWorkflowStage> activeStages = workflowStageRepository
                .findByUserIdAndCampaignTypeOrderByPositionAsc(task.getUserId(), campaignType)
                .stream()
                .filter(stage -> stage != null && Boolean.TRUE.equals(stage.getIsActive()))
                .sorted(Comparator.comparingInt(stage -> Optional.ofNullable(stage.getPosition()).orElse(Integer.MAX_VALUE)))
                .toList();

        if (activeStages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No active workflow stages configured for campaign type \"" + campaignType + "\". Configure workflow setup first.");
        }

        String normalizedStageKey = normalizeStageKey(task.getStageKey());
        if (normalizedStageKey == null) {
            task.setStageKey(activeStages.get(0).getStageKey());
            return;
        }

        boolean isAllowed = activeStages.stream().anyMatch(stage -> normalizedStageKey.equals(normalizeStageKey(stage.getStageKey())));
        if (!isAllowed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stage \"" + task.getStageKey() + "\" is not active for campaign type \"" + campaignType + "\".");
        }
        task.setStageKey(normalizedStageKey);
    }

    private String normalizeCampaignType(String campaignType) {
        String normalized = campaignType == null ? "" : campaignType.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "paid" : normalized;
    }

    private String normalizeStageKey(String stageKey) {
        if (stageKey == null) {
            return null;
        }
        String normalized = stageKey.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
