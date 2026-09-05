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
import java.util.Map;
import java.time.Instant;

@RestController
@RequestMapping("/creators")
public class CreatorController {
    private static final Set<String> ALLOWED_PLATFORMS = Set.of("instagram", "tiktok", "youtube", "other");

    private final CreatorRepository repository;

    public CreatorController(CreatorRepository repository) {
        this.repository = repository;
    }

    /**
     * The roster, optionally searched and filtered (roadmap PR-67).
     *
     * <p>The filters take effect only WITH a brandId. Searching every brand on the platform at once
     * is not a question this endpoint should answer, and letting the filters apply without a tenant
     * scope would make it one.
     */
    @GetMapping
    public List<Creator> findAll(@RequestParam(required = false) UUID brandId,
                                 @RequestParam(required = false) String vettingStatus,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(required = false) String niche,
                                 @RequestParam(required = false) String platform,
                                 @RequestParam(required = false) Integer minFollowers,
                                 @RequestParam(required = false) Integer maxFollowers) {
        boolean filtered = q != null || niche != null || platform != null
                || minFollowers != null || maxFollowers != null;
        if (brandId != null && filtered) {
            // Blank is not a filter. An empty search box submits "" and must return the whole
            // roster, not the rows whose handle contains the empty string by accident of LIKE.
            return repository.search(brandId, blankToNull(q), blankToNull(niche),
                    blankToNull(platform), blankToNull(vettingStatus), minFollowers, maxFollowers);
        }
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

    /**
     * Payout onboarding state only (roadmap PR-47).
     *
     * <p><b>Why not PUT.</b> The update above overwrites every field from the body, so sending it a
     * two-field payload would blank a creator's handle, metrics and notes. A partial update needs
     * its own route, and this one is deliberately NARROW rather than a general PATCH: it can set
     * the six payout- and tax-state columns and nothing else, so it cannot become the back door
     * through which anything on a creator is writable without going past the checks the full update
     * runs. Extend the list only for another column of the same kind.
     */
    @PatchMapping("/{id}/payout-account")
    public Creator updatePayoutAccount(@PathVariable UUID id, @RequestBody Map<String, Object> payload) {
        Creator existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator not found"));

        if (payload.containsKey("stripeAccountId")) {
            existing.setStripeAccountId(text(payload.get("stripeAccountId")));
        }
        if (payload.containsKey("payoutsEnabled")) {
            existing.setPayoutsEnabled(Boolean.TRUE.equals(payload.get("payoutsEnabled")));
        }
        if (payload.containsKey("payoutStatusCheckedAt")) {
            String at = text(payload.get("payoutStatusCheckedAt"));
            existing.setPayoutStatusCheckedAt(at == null ? null : Instant.parse(at));
        }
        if (payload.containsKey("taxFormRequiredAt")) {
            String at = text(payload.get("taxFormRequiredAt"));
            existing.setTaxFormRequiredAt(at == null ? null : Instant.parse(at));
        }
        if (payload.containsKey("taxFormOnFileAt")) {
            String at = text(payload.get("taxFormOnFileAt"));
            existing.setTaxFormOnFileAt(at == null ? null : Instant.parse(at));
        }
        if (payload.containsKey("taxFormKind")) {
            existing.setTaxFormKind(text(payload.get("taxFormKind")));
        }
        return repository.save(existing);
    }

    /** Treats a blank parameter as absent — see the note on the search branch above. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
