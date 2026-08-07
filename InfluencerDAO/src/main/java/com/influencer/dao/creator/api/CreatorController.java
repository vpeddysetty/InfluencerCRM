package com.influencer.dao.creator.api;

import com.influencer.dao.creator.domain.Creator;
import com.influencer.dao.creator.infrastructure.CreatorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/creators")
public class CreatorController {
    private static final Set<String> ALLOWED_PLATFORMS = Set.of("instagram", "tiktok", "youtube", "other");

    private final CreatorRepository repository;

    public CreatorController(CreatorRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Creator> findAll(@RequestParam(required = false) UUID brandId,
                                 @RequestParam(required = false) String vettingStatus) {
        if (brandId != null && vettingStatus != null) {
            return repository.findByBrandIdAndVettingStatus(brandId, vettingStatus);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public Creator findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Creator not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Creator create(@RequestBody Creator creator) {
        applyDefaults(creator);
        return repository.save(creator);
    }

    @PutMapping("/{id}")
    public Creator update(@PathVariable UUID id, @RequestBody Creator creator) {
        Creator existing = repository.findById(id).orElseThrow(() -> new RuntimeException("Creator not found"));
        existing.setBrandId(creator.getBrandId());
        existing.setImportBatchId(creator.getImportBatchId());
        existing.setHandle(creator.getHandle());
        existing.setName(creator.getName());
        existing.setEmail(creator.getEmail());
        existing.setPlatform(creator.getPlatform());
        existing.setFollowerCount(creator.getFollowerCount());
        existing.setEngagementRate(creator.getEngagementRate());
        existing.setTags(creator.getTags());
        existing.setNotes(creator.getNotes());
        existing.setStatus(creator.getStatus());
        existing.setCountry(creator.getCountry());
        existing.setCity(creator.getCity());
        existing.setTimezone(creator.getTimezone());
        existing.setLanguages(creator.getLanguages());
        existing.setNiche(creator.getNiche());
        existing.setContentCategories(creator.getContentCategories());
        existing.setAudienceDemographics(creator.getAudienceDemographics());
        existing.setAudienceSizeEstimate(creator.getAudienceSizeEstimate());
        existing.setAverageViews(creator.getAverageViews());
        existing.setLastActiveAt(creator.getLastActiveAt());
        existing.setSource(creator.getSource());
        existing.setBrandSafetyScore(creator.getBrandSafetyScore());
        existing.setSafetyNotes(creator.getSafetyNotes());
        existing.setPreferredRate(creator.getPreferredRate());
        existing.setMinimumFee(creator.getMinimumFee());
        existing.setCurrency(creator.getCurrency());
        existing.setCustomAttributes(creator.getCustomAttributes());
        // Phase C. Provenance moves with the values it describes: updating a follower count
        // without updating metrics_source/metrics_fetched_at would leave the row claiming a
        // fresh number came from wherever the previous one did.
        existing.setMetricsSource(creator.getMetricsSource());
        existing.setMetricsFetchedAt(creator.getMetricsFetchedAt());
        existing.setMetricsPlatformVerified(creator.getMetricsPlatformVerified());
        existing.setClassificationSource(creator.getClassificationSource());
        existing.setClassificationAt(creator.getClassificationAt());
        existing.setContentThemes(creator.getContentThemes());
        existing.setRiskFlags(creator.getRiskFlags());
        // Phase C2. Written by the vetting service; carried here so a PUT does not reset them.
        if (creator.getVettingStatus() != null) {
            existing.setVettingStatus(creator.getVettingStatus());
        }
        existing.setVettingDecidedAt(creator.getVettingDecidedAt());
        existing.setVettingDecidedByUserId(creator.getVettingDecidedByUserId());
        // lead_source / lead_landing_template_id are deliberately NOT updatable: how a creator
        // entered the system is a historical fact, and rewriting it would destroy the record of
        // which landing page produced the lead.
        applyDefaults(existing);
        return repository.save(existing);
    }

    private void applyDefaults(Creator creator) {
        creator.setPlatform(normalizePlatform(creator.getPlatform()));

        if (creator.getTags() == null) {
            creator.setTags(new String[0]);
        }
        if (creator.getLanguages() == null) {
            creator.setLanguages(new String[0]);
        }
        if (creator.getContentCategories() == null) {
            creator.setContentCategories(new String[0]);
        }
        // NOT NULL with a default in the schema, so a null here would fail the insert.
        if (creator.getContentThemes() == null) {
            creator.setContentThemes(new String[0]);
        }
        if (creator.getRiskFlags() == null) {
            creator.setRiskFlags(new String[0]);
        }
        // NOT NULL with a default in the schema; a null here would fail the insert.
        if (creator.getVettingStatus() == null || creator.getVettingStatus().isBlank()) {
            creator.setVettingStatus("lead");
        }
        if (creator.getAudienceDemographics() == null || creator.getAudienceDemographics().isBlank()) {
            creator.setAudienceDemographics("{}");
        }
        if (creator.getStatus() == null || creator.getStatus().isBlank()) {
            creator.setStatus("active");
        }
        if (creator.getSource() == null || creator.getSource().isBlank()) {
            creator.setSource("manual");
        }
        if (creator.getCurrency() == null || creator.getCurrency().isBlank()) {
            creator.setCurrency("USD");
        }
        if (creator.getCustomAttributes() == null || creator.getCustomAttributes().isBlank()) {
            creator.setCustomAttributes("{}");
        }
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "instagram";
        }

        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PLATFORMS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported platform: " + platform);
        }
        return normalized;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
