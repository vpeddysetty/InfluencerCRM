package com.influencer.dao.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mapping_examples")
public class MappingExample {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "source_signature", nullable = false)
    private String sourceSignature;

    @Column(name = "source_tab_names", columnDefinition = "text[]")
    private String[] sourceTabNames;

    @Column(name = "source_columns", columnDefinition = "text[]")
    private String[] sourceColumns;

    @Column(name = "sample_values_json", columnDefinition = "jsonb")
    private String sampleValuesJson;

    @Column(name = "mappings_json", columnDefinition = "jsonb")
    private String mappingsJson;

    @Column(name = "quality_score")
    private BigDecimal qualityScore;

    @Column(name = "usage_count")
    private Integer usageCount;

    @Column(name = "is_active")
    private Boolean isActive;

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
        if (isActive == null) {
            isActive = true;
        }
        if (usageCount == null) {
            usageCount = 0;
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

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getSourceSignature() {
        return sourceSignature;
    }

    public void setSourceSignature(String sourceSignature) {
        this.sourceSignature = sourceSignature;
    }

    public String[] getSourceTabNames() {
        return sourceTabNames;
    }

    public void setSourceTabNames(String[] sourceTabNames) {
        this.sourceTabNames = sourceTabNames;
    }

    public String[] getSourceColumns() {
        return sourceColumns;
    }

    public void setSourceColumns(String[] sourceColumns) {
        this.sourceColumns = sourceColumns;
    }

    public String getSampleValuesJson() {
        return sampleValuesJson;
    }

    public void setSampleValuesJson(String sampleValuesJson) {
        this.sampleValuesJson = sampleValuesJson;
    }

    public String getMappingsJson() {
        return mappingsJson;
    }

    public void setMappingsJson(String mappingsJson) {
        this.mappingsJson = mappingsJson;
    }

    public BigDecimal getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(BigDecimal qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Integer getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(Integer usageCount) {
        this.usageCount = usageCount;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean active) {
        isActive = active;
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
