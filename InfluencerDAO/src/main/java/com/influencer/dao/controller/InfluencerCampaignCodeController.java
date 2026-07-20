package com.influencer.dao.controller;

import com.influencer.dao.model.InfluencerCampaignCode;
import com.influencer.dao.repository.InfluencerCampaignCodeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/influencer-campaign-codes")
public class InfluencerCampaignCodeController {
    private final InfluencerCampaignCodeRepository repository;

    public InfluencerCampaignCodeController(InfluencerCampaignCodeRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<InfluencerCampaignCode> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) UUID creatorId) {
        if (userId != null && campaignId != null) {
            return repository.findByUserIdAndCampaignId(userId, campaignId);
        }
        if (userId != null) {
            return repository.findByUserId(userId);
        }
        if (campaignId != null) {
            return repository.findByCampaignId(campaignId);
        }
        if (creatorId != null) {
            return repository.findByCreatorId(creatorId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public InfluencerCampaignCode findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("InfluencerCampaignCode not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InfluencerCampaignCode create(@RequestBody InfluencerCampaignCode campaignCode) {
        return repository.save(campaignCode);
    }

    @PutMapping("/{id}")
    public InfluencerCampaignCode update(@PathVariable UUID id, @RequestBody InfluencerCampaignCode campaignCode) {
        InfluencerCampaignCode existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("InfluencerCampaignCode not found"));
        existing.setUserId(campaignCode.getUserId());
        existing.setCampaignId(campaignCode.getCampaignId());
        existing.setCreatorId(campaignCode.getCreatorId());
        existing.setCampaignCreatorId(campaignCode.getCampaignCreatorId());
        existing.setCode(campaignCode.getCode());
        existing.setCodeType(campaignCode.getCodeType());
        existing.setLandingUrl(campaignCode.getLandingUrl());
        existing.setStartsAt(campaignCode.getStartsAt());
        existing.setEndsAt(campaignCode.getEndsAt());
        existing.setIsActive(campaignCode.getIsActive());
        existing.setMetadata(campaignCode.getMetadata());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
