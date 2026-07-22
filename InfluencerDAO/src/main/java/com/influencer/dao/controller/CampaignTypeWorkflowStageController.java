package com.influencer.dao.controller;

import com.influencer.dao.model.CampaignTypeWorkflowStage;
import com.influencer.dao.repository.CampaignTypeWorkflowStageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/campaign-type-workflow-stages")
public class CampaignTypeWorkflowStageController {
    private final CampaignTypeWorkflowStageRepository repository;

    public CampaignTypeWorkflowStageController(CampaignTypeWorkflowStageRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CampaignTypeWorkflowStage> findAll(@RequestParam(required = false) UUID userId,
                                                   @RequestParam(required = false) String campaignType) {
        if (userId != null && campaignType != null && !campaignType.isBlank()) {
            return repository.findByUserIdAndCampaignTypeOrderByPositionAsc(userId, campaignType.trim().toLowerCase());
        }
        if (userId != null) {
            return repository.findByUserIdOrderByCampaignTypeAscPositionAsc(userId);
        }
        return repository.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignTypeWorkflowStage create(@RequestBody CampaignTypeWorkflowStage stage) {
        applyDefaults(stage);
        return repository.save(stage);
    }

    @PutMapping("/{id}")
    public CampaignTypeWorkflowStage update(@PathVariable UUID id, @RequestBody CampaignTypeWorkflowStage stage) {
        CampaignTypeWorkflowStage existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CampaignTypeWorkflowStage not found"));
        existing.setUserId(stage.getUserId());
        existing.setCampaignType(stage.getCampaignType());
        existing.setStageKey(stage.getStageKey());
        existing.setStageLabel(stage.getStageLabel());
        existing.setPosition(stage.getPosition());
        existing.setIsActive(stage.getIsActive());
        applyDefaults(existing);
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }

    @PutMapping("/replace")
    @Transactional
    public List<CampaignTypeWorkflowStage> replace(@RequestBody ReplaceRequest request) {
        if (request == null || request.userId == null || request.campaignType == null || request.campaignType.isBlank()) {
            throw new IllegalArgumentException("userId and campaignType are required.");
        }

        String normalizedCampaignType = request.campaignType.trim().toLowerCase();
        repository.deleteByUserIdAndCampaignType(request.userId, normalizedCampaignType);

        List<CampaignTypeWorkflowStage> payloadStages = request.stages == null ? new ArrayList<>() : request.stages;
        if (payloadStages.isEmpty()) {
            throw new IllegalArgumentException("At least one workflow stage is required.");
        }

        boolean hasAnyActive = payloadStages.stream().anyMatch(stage -> stage != null && stage.getIsActive() != null && stage.getIsActive());
        if (!hasAnyActive) {
            throw new IllegalArgumentException("At least one active workflow stage is required.");
        }

        Set<String> stageKeys = new LinkedHashSet<>();
        List<CampaignTypeWorkflowStage> saved = new ArrayList<>();
        for (int i = 0; i < payloadStages.size(); i++) {
            CampaignTypeWorkflowStage stage = payloadStages.get(i);
            if (stage == null) {
                continue;
            }
            stage.setId(null);
            stage.setUserId(request.userId);
            stage.setCampaignType(normalizedCampaignType);
            if (stage.getPosition() == null) {
                stage.setPosition(i);
            }
            applyDefaults(stage);
            if (!stageKeys.add(stage.getStageKey())) {
                throw new IllegalArgumentException("Duplicate stage_key values are not allowed in workflow setup.");
            }
            saved.add(repository.save(stage));
        }

        if (saved.isEmpty()) {
            throw new IllegalArgumentException("At least one valid workflow stage is required.");
        }

        return saved;
    }

    private void applyDefaults(CampaignTypeWorkflowStage stage) {
        if (stage.getCampaignType() == null || stage.getCampaignType().isBlank()) {
            stage.setCampaignType("paid");
        } else {
            stage.setCampaignType(stage.getCampaignType().trim().toLowerCase());
        }
        if (stage.getStageKey() == null || stage.getStageKey().isBlank()) {
            stage.setStageKey("outreach");
        } else {
            stage.setStageKey(stage.getStageKey().trim().toLowerCase());
        }
        if (stage.getStageLabel() == null || stage.getStageLabel().isBlank()) {
            stage.setStageLabel(stage.getStageKey());
        }
        if (stage.getPosition() == null || stage.getPosition() < 0) {
            stage.setPosition(0);
        }
        if (stage.getIsActive() == null) {
            stage.setIsActive(Boolean.TRUE);
        }
    }

    public static class ReplaceRequest {
        public UUID userId;
        public String campaignType;
        public List<CampaignTypeWorkflowStage> stages;
    }
}
