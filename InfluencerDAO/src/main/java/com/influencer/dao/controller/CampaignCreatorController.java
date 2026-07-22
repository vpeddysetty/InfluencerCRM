package com.influencer.dao.controller;

import com.influencer.dao.model.Campaign;
import com.influencer.dao.model.CampaignCreator;
import com.influencer.dao.model.CampaignTypeWorkflowStage;
import com.influencer.dao.repository.CampaignCreatorRepository;
import com.influencer.dao.repository.CampaignRepository;
import com.influencer.dao.repository.CampaignTypeWorkflowStageRepository;
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
@RequestMapping("/campaign-creators")
public class CampaignCreatorController {
    private final CampaignCreatorRepository repository;
    private final CampaignRepository campaignRepository;
    private final CampaignTypeWorkflowStageRepository workflowStageRepository;

    public CampaignCreatorController(CampaignCreatorRepository repository,
                                     CampaignRepository campaignRepository,
                                     CampaignTypeWorkflowStageRepository workflowStageRepository) {
        this.repository = repository;
        this.campaignRepository = campaignRepository;
        this.workflowStageRepository = workflowStageRepository;
    }

    @GetMapping
    public List<CampaignCreator> findAll(@RequestParam(required = false) UUID userId,
                                         @RequestParam(required = false) String stage) {
        if (userId == null) {
            return repository.findAll();
        }

        boolean hasStage = stage != null && !stage.isBlank();
        if (hasStage) {
            return repository.findByUserIdAndStage(userId, stage);
        }
        return repository.findByUserId(userId);
    }

    @GetMapping("/{id}")
    public CampaignCreator findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("CampaignCreator not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignCreator create(@RequestBody CampaignCreator campaignCreator) {
        applyDefaults(campaignCreator);
        campaignCreator.setStage(resolveAndValidateStage(campaignCreator.getUserId(), campaignCreator.getCampaignId(), campaignCreator.getStage()));
        return repository.save(campaignCreator);
    }

    @PutMapping("/{id}")
    public CampaignCreator update(@PathVariable UUID id, @RequestBody CampaignCreator campaignCreator) {
        CampaignCreator existing = repository.findById(id).orElseThrow(() -> new RuntimeException("CampaignCreator not found"));
        existing.setUserId(campaignCreator.getUserId());
        existing.setCampaignId(campaignCreator.getCampaignId());
        existing.setCreatorId(campaignCreator.getCreatorId());
        existing.setImportBatchId(campaignCreator.getImportBatchId());
        existing.setStage(campaignCreator.getStage());
        existing.setNotes(campaignCreator.getNotes());
        existing.setTags(campaignCreator.getTags());
        existing.setDiscountCode(campaignCreator.getDiscountCode());
        existing.setLink(campaignCreator.getLink());
        existing.setAgreedFee(campaignCreator.getAgreedFee());
        existing.setPostUrl(campaignCreator.getPostUrl());
        existing.setOutreachStatus(campaignCreator.getOutreachStatus());
        existing.setContractStatus(campaignCreator.getContractStatus());
        existing.setDeliverableStatus(campaignCreator.getDeliverableStatus());
        existing.setPaymentStatus(campaignCreator.getPaymentStatus());
        existing.setNextFollowUpAt(campaignCreator.getNextFollowUpAt());
        existing.setLastContactedAt(campaignCreator.getLastContactedAt());
        existing.setContractSentAt(campaignCreator.getContractSentAt());
        existing.setContractSignedAt(campaignCreator.getContractSignedAt());
        existing.setContentDueAt(campaignCreator.getContentDueAt());
        existing.setContentReviewStatus(campaignCreator.getContentReviewStatus());
        existing.setContentReviewRequestedAt(campaignCreator.getContentReviewRequestedAt());
        existing.setContentReviewCompletedAt(campaignCreator.getContentReviewCompletedAt());
        existing.setContentReviewNotes(campaignCreator.getContentReviewNotes());
        existing.setContentReviewedBy(campaignCreator.getContentReviewedBy());
        existing.setContentSubmittedAt(campaignCreator.getContentSubmittedAt());
        existing.setContentApprovedAt(campaignCreator.getContentApprovedAt());
        existing.setPostedAt(campaignCreator.getPostedAt());
        existing.setPaidAt(campaignCreator.getPaidAt());
        existing.setFeeCurrency(campaignCreator.getFeeCurrency());
        existing.setPaymentAmount(campaignCreator.getPaymentAmount());
        existing.setPerformanceMetrics(campaignCreator.getPerformanceMetrics());
        existing.setCustomAttributes(campaignCreator.getCustomAttributes());
        applyDefaults(existing);
        existing.setStage(resolveAndValidateStage(existing.getUserId(), existing.getCampaignId(), existing.getStage()));
        return repository.save(existing);
    }

    @PatchMapping("/{id}/stage")
    public CampaignCreator updateStage(@PathVariable UUID id, @RequestBody CampaignCreator payload) {
        CampaignCreator existing = repository.findById(id).orElseThrow(() -> new RuntimeException("CampaignCreator not found"));
        if (payload.getStage() != null && !payload.getStage().isBlank()) {
            existing.setStage(payload.getStage());
        }
        applyDefaults(existing);
        existing.setStage(resolveAndValidateStage(existing.getUserId(), existing.getCampaignId(), existing.getStage()));
        return repository.save(existing);
    }

    private String resolveAndValidateStage(UUID userId, UUID campaignId, String requestedStage) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required to validate workflow stage.");
        }
        if (campaignId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId is required to validate workflow stage.");
        }

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaign not found for campaignId: " + campaignId));
        if (!userId.equals(campaign.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId does not belong to userId.");
        }

        String campaignType = normalizeCampaignType(campaign.getCampaignType());
        List<CampaignTypeWorkflowStage> activeStages = workflowStageRepository
                .findByUserIdAndCampaignTypeOrderByPositionAsc(userId, campaignType)
                .stream()
                .filter(stage -> stage != null && Boolean.TRUE.equals(stage.getIsActive()))
                .sorted(Comparator.comparingInt(stage -> Optional.ofNullable(stage.getPosition()).orElse(Integer.MAX_VALUE)))
                .toList();

        if (activeStages.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No active workflow stages configured for campaign type \"" + campaignType + "\". Configure workflow setup first."
            );
        }

        String normalizedRequestedStage = normalizeStageKey(requestedStage);
        if (normalizedRequestedStage == null) {
            return activeStages.get(0).getStageKey();
        }

        boolean isAllowed = activeStages.stream().anyMatch(stage -> normalizedRequestedStage.equals(normalizeStageKey(stage.getStageKey())));
        if (!isAllowed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Stage \"" + requestedStage + "\" is not active for campaign type \"" + campaignType + "\"."
            );
        }

        return normalizedRequestedStage;
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

    private void applyDefaults(CampaignCreator campaignCreator) {
        if (campaignCreator.getStage() == null || campaignCreator.getStage().isBlank()) {
            campaignCreator.setStage("outreach");
        }
        if (campaignCreator.getTags() == null) {
            campaignCreator.setTags(new ArrayList<>());
        }
        if (campaignCreator.getOutreachStatus() == null || campaignCreator.getOutreachStatus().isBlank()) {
            campaignCreator.setOutreachStatus("new");
        }
        if (campaignCreator.getContractStatus() == null || campaignCreator.getContractStatus().isBlank()) {
            campaignCreator.setContractStatus("not_sent");
        }
        if (campaignCreator.getDeliverableStatus() == null || campaignCreator.getDeliverableStatus().isBlank()) {
            campaignCreator.setDeliverableStatus("pending");
        }
        if (campaignCreator.getPaymentStatus() == null || campaignCreator.getPaymentStatus().isBlank()) {
            campaignCreator.setPaymentStatus("pending");
        }
        if (campaignCreator.getContentReviewStatus() == null || campaignCreator.getContentReviewStatus().isBlank()) {
            campaignCreator.setContentReviewStatus("not_requested");
        }
        if (campaignCreator.getFeeCurrency() == null || campaignCreator.getFeeCurrency().isBlank()) {
            campaignCreator.setFeeCurrency("USD");
        }
        if (campaignCreator.getPerformanceMetrics() == null || campaignCreator.getPerformanceMetrics().isBlank()) {
            campaignCreator.setPerformanceMetrics("{}");
        }
        if (campaignCreator.getCustomAttributes() == null || campaignCreator.getCustomAttributes().isBlank()) {
            campaignCreator.setCustomAttributes("{}");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
