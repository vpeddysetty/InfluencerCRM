package com.influencer.dao.content.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "landing_templates")
public class LandingTemplate {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;


    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "public_slug", nullable = false)
    private String publicSlug;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "blocks", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String blocks;

    @Column(name = "theme", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String theme;

    /**
     * GrapesJS document { html, css }. Deliberately nullable with no default: NULL is
     * the signal that this page has never been opened in the visual builder, which is
     * what the renderer branches on to fall back to the typed-block `blocks` path.
     */
    @Column(name = "document", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String document;

    /**
     * Ordered typed sections [{type,variant,fields}] from the curated editor (PR-39).
     *
     * <p>Nullable with no default and NOT initialized in {@code @PrePersist} — unlike
     * {@code blocks}, which defaults to {@code []}. It follows {@code document}: NULL is the
     * signal that this page has never been authored in the section editor, and it is what the
     * renderer branches on. Defaulting it to an empty array would promote every existing page
     * onto the section path with nothing in it, i.e. render blank.
     */
    @Column(name = "sections", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String sections;

    @Column(name = "status", nullable = false)
    private String status;

    /** Eight-value page lifecycle; `status` stays the two-value publish gate. */
    @Column(name = "stage", nullable = false)
    private String stage;

    // ---- Phase E: the hosting window (decision #11) --------------------
    // Two months of free hosting, measured from FIRST publish rather than signup, so a brand
    // that explores before publishing gets the full window on the thing being trialled.

    /** NULL until first publish — the clock has not started, which is not the same as expired. */
    @Column(name = "hosting_expires_at")
    private Instant hostingExpiresAt;

    /** Set once. updated_at moves on every edit and so cannot answer when the trial began. */
    @Column(name = "first_published_at")
    private Instant firstPublishedAt;

    /**
     * Smallest expiry-warning threshold already emailed for the current window (30, 7 or 1).
     *
     * <p>NULL means none sent. Makes the daily warning sweep idempotent — without it the job
     * either re-sends every day or fires only on an exact day-count match, which silently skips a
     * warning forever if one run is missed. Reset to NULL when hosting is extended (M5.6).
     */
    @Column(name = "hosting_warning_sent_at_days")
    private Integer hostingWarningSentAtDays;

    /**
     * When this page should publish automatically (PR-35), in UTC.
     *
     * <p>NULL both before scheduling and after the publish fires — the column records what is
     * still owed, never what happened. The audit trail of an actual publish lives in
     * landing_page_transitions, which is where a question about history belongs.
     */
    @Column(name = "scheduled_publish_at")
    private Instant scheduledPublishAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Whose move it is: {@code brand}, {@code creator}, or NULL (PR-40, {@code V45}).
     *
     * <p>Deliberately separate from {@link #stage}, which records how far along the page is. The
     * two change for different reasons and at different rates — a page sits at
     * {@code content_needed} while the turn bounces brand → creator → brand three times over — so
     * deriving one from the other breaks the first time work goes round the loop twice.
     *
     * <p>NULL is a real state and not "unknown": nobody owes anything. A solo draft nobody has
     * been invited to, or a published page where the work is done. Defaulting it to {@code brand}
     * would put every page a brand ever made into their "waiting on you" list.
     */
    @Column(name = "turn")
    private String turn;

    /**
     * When the turn last moved.
     *
     * <p>Distinct from {@link #updatedAt}, which any edit moves. This answers "how long has this
     * been sitting with someone", which is what the abandonment sweep needs — ghosting is the
     * modal outcome in creator marketing, so that question has to be answerable.
     */
    @Column(name = "turn_changed_at")
    private Instant turnChangedAt;

    /**
     * Optimistic-lock counter (OP-18, {@code V44}).
     *
     * <p>This is the one row in the system with two editors by design: a brand and an invited
     * creator can hold the same page open at once, which is the entire point of the collaboration
     * feature. Without this, the second save wins completely and the first person's work is gone
     * with no error and nothing on screen to notice. The snapshot in {@code
     * landing_template_versions} makes that loss recoverable, but only if somebody realises it
     * happened — this makes it not happen.
     *
     * <p>No setter, deliberately. Hibernate manages the value through the field, and a public
     * setter would let a caller send its own number and defeat the check it exists to perform.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Long getVersion() {
        return version;
    }

    public String getTurn() {
        return turn;
    }

    public void setTurn(String turn) {
        this.turn = turn;
    }

    public Instant getTurnChangedAt() {
        return turnChangedAt;
    }

    public void setTurnChangedAt(Instant turnChangedAt) {
        this.turnChangedAt = turnChangedAt;
    }

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (name == null) {
            name = "Landing page";
        }
        if (blocks == null) {
            blocks = "[]";
        }
        if (theme == null) {
            theme = "{}";
        }
        if (status == null) {
            status = "draft";
        }
        if (stage == null) {
            stage = "draft";
        }
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

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID campaignId) {
        this.campaignId = campaignId;
    }

    public String getPublicSlug() {
        return publicSlug;
    }

    public void setPublicSlug(String publicSlug) {
        this.publicSlug = publicSlug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBlocks() {
        return blocks;
    }

    public void setBlocks(String blocks) {
        this.blocks = blocks;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public String getSections() {
        return sections;
    }

    public void setSections(String sections) {
        this.sections = sections;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public Instant getScheduledPublishAt() {
        return scheduledPublishAt;
    }

    public void setScheduledPublishAt(Instant scheduledPublishAt) {
        this.scheduledPublishAt = scheduledPublishAt;
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

    public Instant getHostingExpiresAt() {
        return hostingExpiresAt;
    }

    public void setHostingExpiresAt(Instant hostingExpiresAt) {
        this.hostingExpiresAt = hostingExpiresAt;
    }

    public Instant getFirstPublishedAt() {
        return firstPublishedAt;
    }

    public void setFirstPublishedAt(Instant firstPublishedAt) {
        this.firstPublishedAt = firstPublishedAt;
    }

    public Integer getHostingWarningSentAtDays() {
        return hostingWarningSentAtDays;
    }

    public void setHostingWarningSentAtDays(Integer hostingWarningSentAtDays) {
        this.hostingWarningSentAtDays = hostingWarningSentAtDays;
    }
}
