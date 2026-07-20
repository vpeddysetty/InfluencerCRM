package com.influencer.dao.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "creator_workflow_approvals")
public class CreatorWorkflowApproval {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "campaign_creator_id", nullable = false)
    private UUID campaignCreatorId;

    @Column(name = "review_round", nullable = false)
    private Integer reviewRound;

    @Column(name = "submission_url")
    private String submissionUrl;

    @Column(name = "submission_notes")
    private String submissionNotes;

    @Column(name = "submitted_by_actor", nullable = false)
    private String submittedByActor;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decision")
    private String decision;

    @Column(name = "decision_notes")
    private String decisionNotes;

    @Column(name = "decided_by_actor")
    private String decidedByActor;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (reviewRound == null) {
            reviewRound = 1;
        }
        if (submittedByActor == null) {
            submittedByActor = "creator";
        }
        if (submittedAt == null) {
            submittedAt = now;
        }
        if (metadata == null) {
            metadata = "{}";
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getCampaignCreatorId() {
        return campaignCreatorId;
    }

    public void setCampaignCreatorId(UUID campaignCreatorId) {
        this.campaignCreatorId = campaignCreatorId;
    }

    public Integer getReviewRound() {
        return reviewRound;
    }

    public void setReviewRound(Integer reviewRound) {
        this.reviewRound = reviewRound;
    }

    public String getSubmissionUrl() {
        return submissionUrl;
    }

    public void setSubmissionUrl(String submissionUrl) {
        this.submissionUrl = submissionUrl;
    }

    public String getSubmissionNotes() {
        return submissionNotes;
    }

    public void setSubmissionNotes(String submissionNotes) {
        this.submissionNotes = submissionNotes;
    }

    public String getSubmittedByActor() {
        return submittedByActor;
    }

    public void setSubmittedByActor(String submittedByActor) {
        this.submittedByActor = submittedByActor;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getDecisionNotes() {
        return decisionNotes;
    }

    public void setDecisionNotes(String decisionNotes) {
        this.decisionNotes = decisionNotes;
    }

    public String getDecidedByActor() {
        return decidedByActor;
    }

    public void setDecidedByActor(String decidedByActor) {
        this.decidedByActor = decidedByActor;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
