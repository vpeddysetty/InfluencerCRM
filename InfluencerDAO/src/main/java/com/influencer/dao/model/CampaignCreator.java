package com.influencer.dao.model;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "campaign_creators")
public class CampaignCreator {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "tags", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> tags;

    @Column(name = "discount_code")
    private String discountCode;

    @Column(name = "link")
    private String link;

    @Column(name = "agreed_fee")
    private BigDecimal agreedFee;

    @Column(name = "post_url")
    private String postUrl;

    @Column(name = "outreach_status")
    private String outreachStatus;

    @Column(name = "contract_status")
    private String contractStatus;

    @Column(name = "deliverable_status")
    private String deliverableStatus;

    @Column(name = "payment_status")
    private String paymentStatus;

    @Column(name = "next_follow_up_at")
    private Instant nextFollowUpAt;

    @Column(name = "last_contacted_at")
    private Instant lastContactedAt;

    @Column(name = "contract_sent_at")
    private Instant contractSentAt;

    @Column(name = "contract_signed_at")
    private Instant contractSignedAt;

    @Column(name = "content_due_at")
    private Instant contentDueAt;

    @Column(name = "content_review_status")
    @ColumnTransformer(write = "?::content_review_status")
    private String contentReviewStatus;

    @Column(name = "content_review_requested_at")
    private Instant contentReviewRequestedAt;

    @Column(name = "content_review_completed_at")
    private Instant contentReviewCompletedAt;

    @Column(name = "content_review_notes")
    private String contentReviewNotes;

    @Column(name = "content_reviewed_by")
    private String contentReviewedBy;

    @Column(name = "content_submitted_at")
    private Instant contentSubmittedAt;

    @Column(name = "content_approved_at")
    private Instant contentApprovedAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "fee_currency")
    private String feeCurrency;

    @Column(name = "payment_amount")
    private BigDecimal paymentAmount;

    @Column(name = "performance_metrics", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String performanceMetrics;

    @Column(name = "custom_attributes", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String customAttributes;

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

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID campaignId) {
        this.campaignId = campaignId;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(UUID creatorId) {
        this.creatorId = creatorId;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(UUID importBatchId) {
        this.importBatchId = importBatchId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getDiscountCode() {
        return discountCode;
    }

    public void setDiscountCode(String discountCode) {
        this.discountCode = discountCode;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public BigDecimal getAgreedFee() {
        return agreedFee;
    }

    public void setAgreedFee(BigDecimal agreedFee) {
        this.agreedFee = agreedFee;
    }

    public String getPostUrl() {
        return postUrl;
    }

    public void setPostUrl(String postUrl) {
        this.postUrl = postUrl;
    }

    public String getOutreachStatus() {
        return outreachStatus;
    }

    public void setOutreachStatus(String outreachStatus) {
        this.outreachStatus = outreachStatus;
    }

    public String getContractStatus() {
        return contractStatus;
    }

    public void setContractStatus(String contractStatus) {
        this.contractStatus = contractStatus;
    }

    public String getDeliverableStatus() {
        return deliverableStatus;
    }

    public void setDeliverableStatus(String deliverableStatus) {
        this.deliverableStatus = deliverableStatus;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public Instant getNextFollowUpAt() {
        return nextFollowUpAt;
    }

    public void setNextFollowUpAt(Instant nextFollowUpAt) {
        this.nextFollowUpAt = nextFollowUpAt;
    }

    public Instant getLastContactedAt() {
        return lastContactedAt;
    }

    public void setLastContactedAt(Instant lastContactedAt) {
        this.lastContactedAt = lastContactedAt;
    }

    public Instant getContractSentAt() {
        return contractSentAt;
    }

    public void setContractSentAt(Instant contractSentAt) {
        this.contractSentAt = contractSentAt;
    }

    public Instant getContractSignedAt() {
        return contractSignedAt;
    }

    public void setContractSignedAt(Instant contractSignedAt) {
        this.contractSignedAt = contractSignedAt;
    }

    public Instant getContentDueAt() {
        return contentDueAt;
    }

    public void setContentDueAt(Instant contentDueAt) {
        this.contentDueAt = contentDueAt;
    }

    public String getContentReviewStatus() {
        return contentReviewStatus;
    }

    public void setContentReviewStatus(String contentReviewStatus) {
        this.contentReviewStatus = contentReviewStatus;
    }

    public Instant getContentReviewRequestedAt() {
        return contentReviewRequestedAt;
    }

    public void setContentReviewRequestedAt(Instant contentReviewRequestedAt) {
        this.contentReviewRequestedAt = contentReviewRequestedAt;
    }

    public Instant getContentReviewCompletedAt() {
        return contentReviewCompletedAt;
    }

    public void setContentReviewCompletedAt(Instant contentReviewCompletedAt) {
        this.contentReviewCompletedAt = contentReviewCompletedAt;
    }

    public String getContentReviewNotes() {
        return contentReviewNotes;
    }

    public void setContentReviewNotes(String contentReviewNotes) {
        this.contentReviewNotes = contentReviewNotes;
    }

    public String getContentReviewedBy() {
        return contentReviewedBy;
    }

    public void setContentReviewedBy(String contentReviewedBy) {
        this.contentReviewedBy = contentReviewedBy;
    }

    public Instant getContentSubmittedAt() {
        return contentSubmittedAt;
    }

    public void setContentSubmittedAt(Instant contentSubmittedAt) {
        this.contentSubmittedAt = contentSubmittedAt;
    }

    public Instant getContentApprovedAt() {
        return contentApprovedAt;
    }

    public void setContentApprovedAt(Instant contentApprovedAt) {
        this.contentApprovedAt = contentApprovedAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public String getFeeCurrency() {
        return feeCurrency;
    }

    public void setFeeCurrency(String feeCurrency) {
        this.feeCurrency = feeCurrency;
    }

    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }

    public void setPaymentAmount(BigDecimal paymentAmount) {
        this.paymentAmount = paymentAmount;
    }

    public String getPerformanceMetrics() {
        return performanceMetrics;
    }

    public void setPerformanceMetrics(String performanceMetrics) {
        this.performanceMetrics = performanceMetrics;
    }

    public String getCustomAttributes() {
        return customAttributes;
    }

    public void setCustomAttributes(String customAttributes) {
        this.customAttributes = customAttributes;
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
