package com.influencer.dao.creator.api;

import com.influencer.dao.creator.domain.HealthThreshold;
import com.influencer.dao.creator.infrastructure.HealthThresholdRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Per-brand alert thresholds (roadmap C3.3). */
@RestController
@RequestMapping("/health-thresholds")
public class HealthThresholdController {
    private final HealthThresholdRepository repository;

    public HealthThresholdController(HealthThresholdRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<HealthThreshold> findAll(@RequestParam(required = false) UUID brandId) {
        if (brandId == null) {
            return List.of();
        }
        return repository.findByBrandId(brandId).map(List::of).orElseGet(List::of);
    }

    /**
     * Upsert on brand. A unique constraint covers brand_id, so a plain insert would fail the
     * second time a brand adjusted its thresholds — and adjusting them is the normal case.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HealthThreshold save(@RequestBody HealthThreshold threshold) {
        return repository.findByBrandId(threshold.getBrandId())
                .map(existing -> {
                    existing.setFollowerDropPct(threshold.getFollowerDropPct());
                    existing.setEngagementDropPct(threshold.getEngagementDropPct());
                    existing.setInactiveDays(threshold.getInactiveDays());
                    existing.setWindowDays(threshold.getWindowDays());
                    existing.setAlertOnNewRiskFlag(threshold.getAlertOnNewRiskFlag());
                    return repository.save(existing);
                })
                .orElseGet(() -> repository.save(threshold));
    }
}
