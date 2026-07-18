package com.influencer.dao.controller;

import com.influencer.dao.model.CampaignCreator;
import com.influencer.dao.repository.CampaignCreatorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
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
    public List<CampaignCreator> findAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public CampaignCreator findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("CampaignCreator not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignCreator create(@RequestBody CampaignCreator campaignCreator) {
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
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
