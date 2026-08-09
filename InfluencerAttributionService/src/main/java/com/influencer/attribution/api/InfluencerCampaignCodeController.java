package com.influencer.attribution.api;

import com.influencer.attribution.domain.InfluencerCampaignCode;
import com.influencer.attribution.infrastructure.InfluencerCampaignCodeRepository;
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
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) UUID creatorId) {
        if (brandId != null && campaignId != null) {
            return repository.findByBrandIdAndCampaignId(brandId, campaignId);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
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
        existing.setBrandId(campaignCode.getBrandId());
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
        existing.setMarketplaceConnectionId(campaignCode.getMarketplaceConnectionId());
        existing.setDiscountType(campaignCode.getDiscountType());
        existing.setDiscountValue(campaignCode.getDiscountValue());
        existing.setCommissionType(campaignCode.getCommissionType());
        existing.setCommissionValue(campaignCode.getCommissionValue());
        existing.setChannel(campaignCode.getChannel());
        existing.setRefSlug(campaignCode.getRefSlug());
        existing.setExternalCouponId(campaignCode.getExternalCouponId());
        existing.setSyncStatus(campaignCode.getSyncStatus());
        existing.setPublicSlug(campaignCode.getPublicSlug());
        existing.setPersonalBlurb(campaignCode.getPersonalBlurb());
        existing.setEmbedUrl(campaignCode.getEmbedUrl());
        existing.setPersonalizationStatus(campaignCode.getPersonalizationStatus());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
