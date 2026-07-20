package com.influencer.dao.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "creator_workflow_events")
public class CreatorWorkflowEvent {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "campaign_creator_id", nullable = false)
    private UUID campaignCreatorId;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "actor_creator_id")
    private UUID actorCreatorId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_body")
    private String eventBody;

    @Column(name = "event_data", columnDefinition = "jsonb")
    private String eventData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (actor == null) {
            actor = "system";
        }
        if (eventData == null) {
            eventData = "{}";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
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

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public UUID getActorCreatorId() {
        return actorCreatorId;
    }

    public void setActorCreatorId(UUID actorCreatorId) {
        this.actorCreatorId = actorCreatorId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventBody() {
        return eventBody;
    }

    public void setEventBody(String eventBody) {
        this.eventBody = eventBody;
    }

    public String getEventData() {
        return eventData;
    }

    public void setEventData(String eventData) {
        this.eventData = eventData;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
