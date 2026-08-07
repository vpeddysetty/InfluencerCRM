package com.influencer.creator.api;

import com.influencer.creator.domain.CreatorQualityReport;
import com.influencer.creator.infrastructure.CreatorQualityReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Brand disputes about creator audience quality (roadmap C2.8). */
@RestController
@RequestMapping("/creator-quality-reports")
public class CreatorQualityReportController {
    private final CreatorQualityReportRepository repository;

    public CreatorQualityReportController(CreatorQualityReportRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CreatorQualityReport> findAll(@RequestParam(required = false) UUID brandId,
                                              @RequestParam(required = false) UUID creatorId) {
        if (creatorId != null) {
            return repository.findByCreatorIdOrderByCreatedAtDesc(creatorId);
        }
        if (brandId != null) {
            return repository.findByBrandIdOrderByCreatedAtDesc(brandId);
        }
        return List.of();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorQualityReport create(@RequestBody CreatorQualityReport report) {
        return repository.save(report);
    }

    @PutMapping("/{id}")
    public CreatorQualityReport update(@PathVariable UUID id, @RequestBody CreatorQualityReport report) {
        CreatorQualityReport existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CreatorQualityReport not found"));
        existing.setStatus(report.getStatus());
        existing.setDetail(report.getDetail());
        existing.setResolvedAt(report.getResolvedAt());
        // signal_snapshot is deliberately NOT updatable: it records what we believed at the
        // time of the complaint, and rewriting it would destroy the labelled example that makes
        // the dispute useful for tuning.
        return repository.save(existing);
    }
}
