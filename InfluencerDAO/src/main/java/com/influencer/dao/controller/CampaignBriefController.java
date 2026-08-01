package com.influencer.dao.controller;

import com.influencer.dao.model.CampaignBrief;
import com.influencer.dao.repository.CampaignBriefRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/campaign-briefs")
public class CampaignBriefController {
    private final CampaignBriefRepository repository;

    public CampaignBriefController(CampaignBriefRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CampaignBrief> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignId) {
        if (userId != null && campaignId != null) {
            return repository.findByUserIdAndCampaignId(userId, campaignId)
                    .map(List::of).orElseGet(List::of);
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
    public CampaignBrief findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("CampaignBrief not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignBrief create(@RequestBody CampaignBrief brief) {
        return repository.save(brief);
    }

    @PutMapping("/{id}")
    public CampaignBrief update(@PathVariable UUID id, @RequestBody CampaignBrief brief) {
        CampaignBrief existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CampaignBrief not found"));
        existing.setUserId(brief.getUserId());
        existing.setCampaignId(brief.getCampaignId());
        existing.setContent(brief.getContent());
        existing.setAssets(brief.getAssets());
        existing.setHashtags(brief.getHashtags());
        existing.setDisclosureText(brief.getDisclosureText());
        existing.setStatus(brief.getStatus());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
