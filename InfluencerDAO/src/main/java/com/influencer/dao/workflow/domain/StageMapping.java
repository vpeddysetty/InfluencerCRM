package com.influencer.dao.workflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps a landing page stage to a board stage, per board (roadmap D.6).
 *
 * Keyed on stage_id, which is only a safe key because replace/ now preserves stage identity
 * — before that fix a rename minted new ids and every mapping would have dangled.
 */
@Entity
@Table(name = "stage_mappings")
public class StageMapping {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "page_stage", nullable = false)
    private String pageStage;

    /** Nullable: a brand may map a page stage to nothing rather than invent a column for it. */
    @Column(name = "stage_id")
    private UUID stageId;

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
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }
    public UUID getBoardId() { return boardId; }
    public void setBoardId(UUID boardId) { this.boardId = boardId; }
    public String getPageStage() { return pageStage; }
    public void setPageStage(String pageStage) { this.pageStage = pageStage; }
    public UUID getStageId() { return stageId; }
    public void setStageId(UUID stageId) { this.stageId = stageId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
