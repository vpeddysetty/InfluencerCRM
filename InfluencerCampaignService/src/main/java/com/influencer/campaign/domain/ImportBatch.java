package com.influencer.campaign.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_batches")
public class ImportBatch {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;


    @Column(name = "source_filename", nullable = false)
    private String sourceFilename;

    @JsonIgnore
    @Column(name = "source_file")
    private byte[] sourceFile;

    @Column(name = "hydration_status", nullable = false)
    private String status;

    @Column(name = "column_mapping", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String columnMapping;

    @Column(name = "row_count", nullable = false)
    private Integer rowCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
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

    public UUID getBrandId() {
        return brandId;
    }

    public void setBrandId(UUID brandId) {
        this.brandId = brandId;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public void setSourceFilename(String sourceFilename) {
        this.sourceFilename = sourceFilename;
    }

    public byte[] getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(byte[] sourceFile) {
        this.sourceFile = sourceFile;
    }

    @Transient
    public boolean isSourceFileStored() {
        return sourceFile != null && sourceFile.length > 0;
    }

    public String getColumnMapping() {
        return columnMapping;
    }

    public void setColumnMapping(String columnMapping) {
        this.columnMapping = columnMapping;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
