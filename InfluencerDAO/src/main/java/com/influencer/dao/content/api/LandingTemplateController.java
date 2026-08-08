package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.LandingTemplate;
import com.influencer.dao.content.infrastructure.LandingTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/landing-templates")
public class LandingTemplateController {
    private final LandingTemplateRepository repository;

    public LandingTemplateController(LandingTemplateRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<LandingTemplate> findAll(
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) String publicSlug) {
        if (publicSlug != null) {
            return repository.findByPublicSlug(publicSlug).map(List::of).orElseGet(List::of);
        }
        if (brandId != null && campaignId != null) {
            return repository.findByBrandIdAndCampaignId(brandId, campaignId).map(List::of).orElseGet(List::of);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
        }
        if (campaignId != null) {
            return repository.findByCampaignId(campaignId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public LandingTemplate findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("LandingTemplate not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LandingTemplate create(@RequestBody LandingTemplate template) {
        return repository.save(template);
    }

    @PutMapping("/{id}")
    public LandingTemplate update(@PathVariable UUID id, @RequestBody LandingTemplate template) {
        LandingTemplate existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("LandingTemplate not found"));
        existing.setBrandId(template.getBrandId());
        existing.setCampaignId(template.getCampaignId());
        existing.setPublicSlug(template.getPublicSlug());
        existing.setName(template.getName());
        // blocks/theme are NOT NULL jsonb; a PUT that omits them must not null the
        // column — keep the incoming value, else the existing one, else the default.
        existing.setBlocks(firstNonNull(template.getBlocks(), existing.getBlocks(), "[]"));
        existing.setTheme(firstNonNull(template.getTheme(), existing.getTheme(), "{}"));
        // `document` is nullable by design (NULL = never opened in the visual builder), so
        // there is no default to fall back to — but a PUT that omits it must still not
        // erase a document the builder has already written.
        existing.setDocument(firstNonNull(template.getDocument(), existing.getDocument()));
        existing.setStatus(template.getStatus());
        existing.setStage(firstNonNull(template.getStage(), existing.getStage(), "draft"));
        // Phase E. Null-guarded: a PUT that omits these must not clear a hosting window that
        // has already started, or a published page would silently become free forever.
        if (template.getHostingExpiresAt() != null) {
            existing.setHostingExpiresAt(template.getHostingExpiresAt());
        }
        if (template.getFirstPublishedAt() != null) {
            existing.setFirstPublishedAt(template.getFirstPublishedAt());
        }
        // M5.6. Deliberately NOT null-guarded like the two above: clearing this is a meaningful
        // operation. Extending hosting must reset it so the new deadline gets its own warnings,
        // and a guard would make that reset unexpressible — every extended page would then stay
        // permanently silent. The cost is that a PUT omitting the field clears it, which at worst
        // re-sends one warning; the guarded alternative loses them all.
        existing.setHostingWarningSentAtDays(template.getHostingWarningSentAtDays());
        return repository.save(existing);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
