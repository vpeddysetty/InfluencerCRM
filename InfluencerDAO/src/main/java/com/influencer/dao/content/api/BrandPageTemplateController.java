package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.BrandPageTemplate;
import com.influencer.dao.content.infrastructure.BrandPageTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Storage for brand-saved page templates (roadmap PR-39, piece D).
 *
 * <p>Like every other DAO controller this trusts the workload token and the brandId the BFF
 * passes; tenant enforcement is the BFF's job. What this layer does guarantee is that a read or
 * delete by id is ALSO scoped by brand, so a guessed UUID reaches nothing.
 */
@RestController
@RequestMapping("/brand-page-templates")
public class BrandPageTemplateController {

    private final BrandPageTemplateRepository repository;

    public BrandPageTemplateController(BrandPageTemplateRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<BrandPageTemplate> list(@RequestParam UUID brandId) {
        return repository.findByBrandIdOrderByCreatedAtDesc(brandId);
    }

    @GetMapping("/{id}")
    public BrandPageTemplate findById(@PathVariable UUID id, @RequestParam UUID brandId) {
        return repository.findByIdAndBrandId(id, brandId)
                .orElseThrow(() -> new RuntimeException("BrandPageTemplate not found"));
    }

    /**
     * Create, or overwrite the brand's template of the same name.
     *
     * <p>Upsert rather than a hard failure on the unique index: "save as template" with a name
     * that already exists reads as "replace that one", and surfacing a constraint violation would
     * make the user delete the old one first to do what they already asked for. The BFF confirms
     * the overwrite before it gets here.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BrandPageTemplate create(@RequestBody BrandPageTemplate template) {
        return repository.findByBrandIdAndNameIgnoreCase(template.getBrandId(), template.getName())
                .map(existing -> {
                    existing.setSections(template.getSections());
                    existing.setCreatedByUserId(template.getCreatedByUserId());
                    return repository.save(existing);
                })
                .orElseGet(() -> repository.save(template));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam UUID brandId) {
        repository.findByIdAndBrandId(id, brandId).ifPresent(repository::delete);
    }

    /** Used by the BFF's plan-limit check. */
    @GetMapping("/count")
    public long count(@RequestParam UUID brandId) {
        return repository.countByBrandId(brandId);
    }
}
