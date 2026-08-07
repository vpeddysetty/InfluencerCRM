package com.influencer.workflow.api;

import com.influencer.workflow.domain.StageTransition;
import com.influencer.workflow.infrastructure.StageTransitionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Landing page stage transitions (roadmap §4 rule 4).
 *
 * Append-only: read and create, no update and no delete. A transition is a record of
 * something that happened, and an editable audit trail is not an audit trail.
 */
@RestController
@RequestMapping("/stage-transitions")
public class StageTransitionController {
    private final StageTransitionRepository repository;

    public StageTransitionController(StageTransitionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<StageTransition> findAll(@RequestParam(required = false) UUID landingTemplateId,
                                         @RequestParam(required = false) String idempotencyKey) {
        if (idempotencyKey != null) {
            return repository.findByIdempotencyKey(idempotencyKey).map(List::of).orElseGet(List::of);
        }
        if (landingTemplateId != null) {
            return repository.findByLandingTemplateIdOrderByOccurredAtDesc(landingTemplateId);
        }
        return List.of();
    }

    /**
     * Record a transition.
     *
     * A duplicate idempotency key returns the EXISTING row with 200 rather than inserting a
     * second one or failing. That is what makes a retried command a no-op: the caller gets the
     * same answer it would have got the first time.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StageTransition create(@RequestBody StageTransition transition) {
        if (transition.getIdempotencyKey() != null) {
            var existing = repository.findByIdempotencyKey(transition.getIdempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return repository.save(transition);
    }
}
