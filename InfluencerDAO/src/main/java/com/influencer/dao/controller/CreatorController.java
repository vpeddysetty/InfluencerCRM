package com.influencer.dao.controller;

import com.influencer.dao.model.Creator;
import com.influencer.dao.repository.CreatorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/creators")
public class CreatorController {
    private final CreatorRepository repository;

    public CreatorController(CreatorRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Creator> findAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public Creator findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Creator not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Creator create(@RequestBody Creator creator) {
        return repository.save(creator);
    }

    @PutMapping("/{id}")
    public Creator update(@PathVariable UUID id, @RequestBody Creator creator) {
        Creator existing = repository.findById(id).orElseThrow(() -> new RuntimeException("Creator not found"));
        existing.setUserId(creator.getUserId());
        existing.setImportBatchId(creator.getImportBatchId());
        existing.setHandle(creator.getHandle());
        existing.setName(creator.getName());
        existing.setEmail(creator.getEmail());
        existing.setPlatform(creator.getPlatform());
        existing.setFollowerCount(creator.getFollowerCount());
        existing.setEngagementRate(creator.getEngagementRate());
        existing.setTags(creator.getTags());
        existing.setNotes(creator.getNotes());
        existing.setStatus(creator.getStatus());
        existing.setCountry(creator.getCountry());
        existing.setCity(creator.getCity());
        existing.setTimezone(creator.getTimezone());
        existing.setLanguages(creator.getLanguages());
        existing.setNiche(creator.getNiche());
        existing.setContentCategories(creator.getContentCategories());
        existing.setAudienceDemographics(creator.getAudienceDemographics());
        existing.setAudienceSizeEstimate(creator.getAudienceSizeEstimate());
        existing.setAverageViews(creator.getAverageViews());
        existing.setLastActiveAt(creator.getLastActiveAt());
        existing.setSource(creator.getSource());
        existing.setBrandSafetyScore(creator.getBrandSafetyScore());
        existing.setSafetyNotes(creator.getSafetyNotes());
        existing.setPreferredRate(creator.getPreferredRate());
        existing.setMinimumFee(creator.getMinimumFee());
        existing.setCurrency(creator.getCurrency());
        existing.setCustomAttributes(creator.getCustomAttributes());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
