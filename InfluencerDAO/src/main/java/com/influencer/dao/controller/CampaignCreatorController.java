package com.influencer.dao.controller;

import com.influencer.dao.model.CampaignCreator;
import com.influencer.dao.repository.CampaignCreatorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/campaign-creators")
public class CampaignCreatorController {
    private final CampaignCreatorRepository repository;

    public CampaignCreatorController(CampaignCreatorRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CampaignCreator> findAll(@RequestParam(required = false) UUID userId) {
        if (userId == null) {
            return repository.findAll();
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
        return repository.save(campaignCreator);
    }

    @PutMapping("/{id}")
    public CampaignCreator update(@PathVariable UUID id, @RequestBody CampaignCreator campaignCreator) {
        CampaignCreator existing = repository.findById(id).orElseThrow(() -> new RuntimeException("CampaignCreator not found"));
        existing.setUserId(campaignCreator.getUserId());
        existing.setCampaignId(campaignCreator.getCampaignId());
        existing.setCreatorId(campaignCreator.getCreatorId());
        existing.setImportBatchId(campaignCreator.getImportBatchId());
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
        return repository.save(existing);
    }

    private void applyDefaults(CampaignCreator campaignCreator) {
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
