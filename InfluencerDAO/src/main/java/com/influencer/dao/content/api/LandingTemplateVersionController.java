package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.LandingTemplateVersion;
import com.influencer.dao.content.infrastructure.LandingTemplateVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Append-only landing page version history (roadmap A.5).
 *
 * Read and create only. There is no PUT and no DELETE, and that is the design:
 * history that can be edited is not history. Restoring a version is a create on
 * the template (writing a NEW version), never a mutation of an old one.
 */
@RestController
@RequestMapping("/landing-template-versions")
public class LandingTemplateVersionController {
    private final LandingTemplateVersionRepository repository;

    public LandingTemplateVersionController(LandingTemplateVersionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<LandingTemplateVersion> findAll(@RequestParam(required = false) UUID landingTemplateId) {
        if (landingTemplateId == null) {
            return List.of();
        }
        return repository.findByLandingTemplateIdOrderByVersionNoDesc(landingTemplateId);
    }

    @GetMapping("/{id}")
    public LandingTemplateVersion findById(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("LandingTemplateVersion not found"));
    }

    /** Next version number for a template — the service stamps this on save. */
    @GetMapping("/next-version-no")
    public Integer nextVersionNo(@RequestParam UUID landingTemplateId) {
        return repository.nextVersionNo(landingTemplateId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LandingTemplateVersion create(@RequestBody LandingTemplateVersion version) {
        if (version.getVersionNo() == null) {
            version.setVersionNo(repository.nextVersionNo(version.getLandingTemplateId()));
        }
        return repository.save(version);
    }
}
