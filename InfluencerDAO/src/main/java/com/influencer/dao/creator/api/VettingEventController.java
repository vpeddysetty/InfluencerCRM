package com.influencer.dao.creator.api;

import com.influencer.dao.creator.domain.VettingEvent;
import com.influencer.dao.creator.infrastructure.VettingEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The vetting audit trail (roadmap C2.5).
 *
 * Read and create only. No PUT, no DELETE: a record of a decision that can be edited is not a
 * record. This is what answers a creator asking why they were rejected, months later.
 */
@RestController
@RequestMapping("/vetting-events")
public class VettingEventController {
    private final VettingEventRepository repository;

    public VettingEventController(VettingEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<VettingEvent> findAll(@RequestParam(required = false) UUID creatorId,
                                      @RequestParam(required = false) UUID brandId) {
        if (creatorId != null) {
            return repository.findByCreatorIdOrderByOccurredAtDesc(creatorId);
        }
        if (brandId != null) {
            return repository.findByBrandIdOrderByOccurredAtDesc(brandId);
        }
        return List.of();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VettingEvent create(@RequestBody VettingEvent event) {
        return repository.save(event);
    }
}
