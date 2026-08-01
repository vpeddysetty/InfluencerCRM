package com.influencer.dao.controller;

import com.influencer.dao.model.LandingTemplate;
import com.influencer.dao.repository.LandingTemplateRepository;
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
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) String publicSlug) {
        if (publicSlug != null) {
            return repository.findByPublicSlug(publicSlug).map(List::of).orElseGet(List::of);
        }
        if (userId != null && campaignId != null) {
            return repository.findByUserIdAndCampaignId(userId, campaignId).map(List::of).orElseGet(List::of);
        }
        if (userId != null) {
            return repository.findByUserId(userId);
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
        existing.setUserId(template.getUserId());
        existing.setCampaignId(template.getCampaignId());
        existing.setPublicSlug(template.getPublicSlug());
        existing.setName(template.getName());
        existing.setBlocks(template.getBlocks());
        existing.setTheme(template.getTheme());
        existing.setStatus(template.getStatus());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
