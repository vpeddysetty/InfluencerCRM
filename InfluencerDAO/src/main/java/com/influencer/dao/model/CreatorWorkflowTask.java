package com.influencer.dao.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "creator_workflow_tasks")
public class CreatorWorkflowTask {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "campaign_creator_id", nullable = false)
    private UUID campaignCreatorId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "assignee_actor", nullable = false)
    private String assigneeActor;

    @Column(name = "assignee_creator_id")
    private UUID assigneeCreatorId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_by_actor", nullable = false)
    private String createdByActor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (assigneeActor == null) {
            assigneeActor = "brand_owner";
        }
        if (status == null) {
            status = "todo";
        }
        if (priority == null) {
            priority = "medium";
        }
        if (createdByActor == null) {
            createdByActor = "brand_owner";
        }
        if (metadata == null) {
            metadata = "{}";
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAssigneeActor() {
        return assigneeActor;
    }

    public void setAssigneeActor(String assigneeActor) {
        this.assigneeActor = assigneeActor;
    }

    public UUID getAssigneeCreatorId() {
        return assigneeCreatorId;
    }

    public void setAssigneeCreatorId(UUID assigneeCreatorId) {
        this.assigneeCreatorId = assigneeCreatorId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getCreatedByActor() {
        return createdByActor;
    }

    public void setCreatedByActor(String createdByActor) {
        this.createdByActor = createdByActor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
