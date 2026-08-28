package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.PageHandoff;
import com.influencer.dao.content.infrastructure.PageHandoffRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Records who passed a page to whom, and when (roadmap PR-40). */
@RestController
@RequestMapping("/page-handoffs")
public class PageHandoffController {

    private final PageHandoffRepository repository;

    public PageHandoffController(PageHandoffRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PageHandoff record(@RequestBody PageHandoff handoff) {
        if (handoff.getLandingTemplateId() == null || handoff.getBrandId() == null
                || handoff.getToTurn() == null || handoff.getIdempotencyKey() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "landingTemplateId, brandId, toTurn and idempotencyKey are required");
        }
        // A retry returns the row it already wrote rather than failing on the unique index. The
        // caller asked for this handoff to be recorded; it is recorded, and saying so is the
        // truthful answer to a repeated request.
        Optional<PageHandoff> existing = repository.findByIdempotencyKey(handoff.getIdempotencyKey());
        return existing.orElseGet(() -> repository.save(handoff));
    }

    @GetMapping
    public List<PageHandoff> list(@RequestParam UUID landingTemplateId) {
        return repository.findByLandingTemplateIdOrderByCreatedAtDesc(landingTemplateId);
    }
}
