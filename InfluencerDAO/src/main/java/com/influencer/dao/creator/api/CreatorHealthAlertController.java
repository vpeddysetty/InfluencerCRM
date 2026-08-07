package com.influencer.dao.creator.api;

import com.influencer.dao.creator.domain.CreatorHealthAlert;
import com.influencer.dao.creator.infrastructure.CreatorHealthAlertRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Creator health alerts (roadmap C3.4, C3.5).
 *
 * The update path deliberately only moves status, snooze and resolution. Nothing here can
 * change a creator's vetting status or access: the alert informs a decision, a human takes it
 * (roadmap #13).
 */
@RestController
@RequestMapping("/creator-health-alerts")
public class CreatorHealthAlertController {
    private final CreatorHealthAlertRepository repository;

    public CreatorHealthAlertController(CreatorHealthAlertRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CreatorHealthAlert> findAll(@RequestParam(required = false) UUID brandId,
                                            @RequestParam(required = false) UUID creatorId,
                                            @RequestParam(required = false) String status) {
        if (creatorId != null) {
            return repository.findByCreatorIdOrderByCreatedAtDesc(creatorId);
        }
        if (brandId != null && status != null) {
            return repository.findByBrandIdAndStatusOrderByCreatedAtDesc(brandId, status);
        }
        if (brandId != null) {
            return repository.findByBrandIdOrderByCreatedAtDesc(brandId);
        }
        return List.of();
    }

    @GetMapping("/{id}")
    public CreatorHealthAlert findById(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CreatorHealthAlert not found"));
    }

    /**
     * Raise an alert.
     *
     * Returns the EXISTING open alert of the same type rather than inserting a second one. The
     * partial unique index enforces this at the database, but returning the existing row means a
     * weekly refresh is a quiet no-op instead of a constraint violation the caller has to handle.
     * Re-raising the same warning every week is exactly the alert fatigue this phase designs
     * against.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorHealthAlert create(@RequestBody CreatorHealthAlert alert) {
        return repository.findByCreatorIdAndAlertTypeAndStatus(
                        alert.getCreatorId(), alert.getAlertType(), "open")
                .orElseGet(() -> repository.save(alert));
    }

    @PutMapping("/{id}")
    public CreatorHealthAlert update(@PathVariable UUID id, @RequestBody CreatorHealthAlert alert) {
        CreatorHealthAlert existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CreatorHealthAlert not found"));
        existing.setStatus(alert.getStatus());
        existing.setSnoozedUntil(alert.getSnoozedUntil());
        existing.setResolutionNote(alert.getResolutionNote());
        existing.setResolvedByUserId(alert.getResolvedByUserId());
        existing.setResolvedAt(alert.getResolvedAt());
        // alert_type, summary and the values are NOT updatable: they record what was observed,
        // and editing them would make the alert unfalsifiable after the fact.
        return repository.save(existing);
    }
}
