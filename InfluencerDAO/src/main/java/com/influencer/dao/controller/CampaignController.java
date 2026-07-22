package com.influencer.dao.controller;

import com.influencer.dao.model.Campaign;
import com.influencer.dao.repository.CampaignRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/campaigns")
public class CampaignController {
    private final CampaignRepository repository;

    public CampaignController(CampaignRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Campaign> findAll(@RequestParam(required = false) UUID userId,
                                  @RequestParam(required = false) String campaignType) {
        if (userId != null && campaignType != null && !campaignType.isBlank()) {
            return repository.findByUserIdAndCampaignType(userId, campaignType.trim().toLowerCase());
        }
        if (userId != null) {
            return repository.findByUserId(userId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public Campaign findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Campaign not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Campaign create(@RequestBody Campaign campaign) {
        applyDefaults(campaign);
        return repository.save(campaign);
    }

    @PutMapping("/{id}")
    public Campaign update(@PathVariable UUID id, @RequestBody Campaign campaign) {
        Campaign existing = repository.findById(id).orElseThrow(() -> new RuntimeException("Campaign not found"));
        existing.setUserId(campaign.getUserId());
        existing.setName(campaign.getName());
        existing.setGoal(campaign.getGoal());
        existing.setProduct(campaign.getProduct());
        existing.setBudget(campaign.getBudget());
        existing.setStartDate(campaign.getStartDate());
        existing.setEndDate(campaign.getEndDate());
        existing.setStatus(campaign.getStatus());
        existing.setCampaignType(campaign.getCampaignType());
        existing.setObjective(campaign.getObjective());
        existing.setTargetAudience(campaign.getTargetAudience());
        existing.setMarketRegion(campaign.getMarketRegion());
        existing.setGeoTargeting(campaign.getGeoTargeting());
        existing.setDeliverablesRequired(campaign.getDeliverablesRequired());
        existing.setKpiTarget(campaign.getKpiTarget());
        existing.setCurrency(campaign.getCurrency());
        existing.setPriority(campaign.getPriority());
        existing.setBriefUrl(campaign.getBriefUrl());
        existing.setBriefNotes(campaign.getBriefNotes());
        existing.setContentGuidelines(campaign.getContentGuidelines());
        existing.setCampaignOwner(campaign.getCampaignOwner());
        existing.setCustomAttributes(campaign.getCustomAttributes());
        applyDefaults(existing);
        return repository.save(existing);
    }

    private void applyDefaults(Campaign campaign) {
        if (campaign.getStatus() == null || campaign.getStatus().isBlank()) {
            campaign.setStatus("draft");
        }
        if (campaign.getCampaignType() == null || campaign.getCampaignType().isBlank()) {
            campaign.setCampaignType("paid");
        }
        if (campaign.getCurrency() == null || campaign.getCurrency().isBlank()) {
            campaign.setCurrency("USD");
        }
        if (campaign.getPriority() == null || campaign.getPriority().isBlank()) {
            campaign.setPriority("medium");
        }
        if (campaign.getDeliverablesRequired() == null) {
            campaign.setDeliverablesRequired(new String[0]);
        }
        if (campaign.getCustomAttributes() == null || campaign.getCustomAttributes().isBlank()) {
            campaign.setCustomAttributes("{}");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
