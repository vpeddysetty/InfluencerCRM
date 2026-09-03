package com.influencer.dao.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A visitor asking a brand to get in touch, from a public landing page (roadmap PR-61, V51).
 *
 * <p><b>Not a creator.</b> Someone who fills in a form on a page has not joined that brand's
 * roster, has no handle, and must never reach a vetting queue — putting a member of the public into
 * a workflow built for commercial partners would be a category error with real consequences.
 * {@code creator.creators} carries its own {@code lead_source} for creators who applied; this is a
 * different kind of lead and lives apart from it deliberately.
 *
 * <p><b>Consent lives elsewhere.</b> There is no account to attach it to, so it is recorded through
 * {@code ConsentService} keyed by email, with the version, document URL and immutable snapshot that
 * system already provides. A boolean column here would be a claim; that is evidence.
 */
@Entity
@Table(name = "page_leads")
public class PageLead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "brand_id", nullable = false)
    private UUID brandId;

    @Column(name = "landing_template_id", nullable = false)
    private UUID landingTemplateId;

    /** Which creator's personalised page it came from. Null on the brand's own page. */
    @Column(name = "campaign_code_id")
    private UUID campaignCodeId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "name")
    private String name;

    @Column(name = "message")
    private String message;

    /** Set by the server from the request, never from the form — see V51. */
    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBrandId() { return brandId; }
    public void setBrandId(UUID brandId) { this.brandId = brandId; }
    public UUID getLandingTemplateId() { return landingTemplateId; }
    public void setLandingTemplateId(UUID landingTemplateId) { this.landingTemplateId = landingTemplateId; }
    public UUID getCampaignCodeId() { return campaignCodeId; }
    public void setCampaignCodeId(UUID campaignCodeId) { this.campaignCodeId = campaignCodeId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
