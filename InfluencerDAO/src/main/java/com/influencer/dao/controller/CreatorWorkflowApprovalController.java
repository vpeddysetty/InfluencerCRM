package com.influencer.dao.controller;

import com.influencer.dao.model.CreatorWorkflowApproval;
import com.influencer.dao.repository.CreatorWorkflowApprovalRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/creator-workflow-approvals")
public class CreatorWorkflowApprovalController {
    private final CreatorWorkflowApprovalRepository repository;

    public CreatorWorkflowApprovalController(CreatorWorkflowApprovalRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CreatorWorkflowApproval> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignCreatorId) {
        if (userId != null && campaignCreatorId != null) {
            return repository.findByUserIdAndCampaignCreatorIdOrderByReviewRoundDesc(userId, campaignCreatorId);
        }
        if (userId != null) {
            return repository.findByUserIdOrderBySubmittedAtDesc(userId);
        }
        if (campaignCreatorId != null) {
            return repository.findByCampaignCreatorIdOrderByReviewRoundDesc(campaignCreatorId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public CreatorWorkflowApproval findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("CreatorWorkflowApproval not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorWorkflowApproval create(@RequestBody CreatorWorkflowApproval approval) {
        return repository.save(approval);
    }

    @PutMapping("/{id}")
    public CreatorWorkflowApproval update(@PathVariable UUID id, @RequestBody CreatorWorkflowApproval approval) {
        CreatorWorkflowApproval existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CreatorWorkflowApproval not found"));
        existing.setUserId(approval.getUserId());
        existing.setCampaignCreatorId(approval.getCampaignCreatorId());
        existing.setReviewRound(approval.getReviewRound());
        existing.setSubmissionUrl(approval.getSubmissionUrl());
        existing.setSubmissionNotes(approval.getSubmissionNotes());
        existing.setSubmittedByActor(approval.getSubmittedByActor());
        existing.setSubmittedAt(approval.getSubmittedAt());
        existing.setDecision(approval.getDecision());
        existing.setDecisionNotes(approval.getDecisionNotes());
        existing.setDecidedByActor(approval.getDecidedByActor());
        existing.setDecidedAt(approval.getDecidedAt());
        existing.setMetadata(approval.getMetadata());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
