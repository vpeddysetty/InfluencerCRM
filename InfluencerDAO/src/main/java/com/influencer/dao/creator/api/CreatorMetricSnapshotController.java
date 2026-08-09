package com.influencer.dao.creator.api;

import com.influencer.dao.creator.domain.CreatorMetricSnapshot;
import com.influencer.dao.creator.infrastructure.CreatorMetricSnapshotRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Append-only metric history (roadmap C3.2).
 *
 * Read and create only. A snapshot records what was true at a moment; updating one would
 * destroy the trend it exists to support.
 */
@RestController
@RequestMapping("/creator-metric-snapshots")
public class CreatorMetricSnapshotController {
    private final CreatorMetricSnapshotRepository repository;

    public CreatorMetricSnapshotController(CreatorMetricSnapshotRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CreatorMetricSnapshot> findAll(@RequestParam(required = false) UUID creatorId) {
        if (creatorId == null) {
            return List.of();
        }
        return repository.findByCreatorIdOrderByCapturedAtDesc(creatorId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorMetricSnapshot create(@RequestBody CreatorMetricSnapshot snapshot) {
        return repository.save(snapshot);
    }
}
