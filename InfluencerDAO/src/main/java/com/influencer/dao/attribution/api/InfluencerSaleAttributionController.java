package com.influencer.dao.attribution.api;

import com.influencer.dao.attribution.domain.InfluencerSaleAttribution;
import com.influencer.dao.attribution.infrastructure.InfluencerSaleAttributionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/influencer-sale-attributions")
public class InfluencerSaleAttributionController {
    private final InfluencerSaleAttributionRepository repository;

    public InfluencerSaleAttributionController(InfluencerSaleAttributionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<InfluencerSaleAttribution> findAll(
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID campaignCodeId,
            @RequestParam(required = false) UUID campaignCreatorId) {
        if (brandId != null && campaignCreatorId != null) {
            return repository.findByBrandIdAndCampaignCreatorId(brandId, campaignCreatorId);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
        }
        if (campaignCodeId != null) {
            return repository.findByCampaignCodeId(campaignCodeId);
        }
        if (campaignCreatorId != null) {
            return repository.findByCampaignCreatorId(campaignCreatorId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public InfluencerSaleAttribution findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("InfluencerSaleAttribution not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InfluencerSaleAttribution create(@RequestBody InfluencerSaleAttribution attribution) {
        return repository.save(attribution);
    }

    @PutMapping("/{id}")
    public InfluencerSaleAttribution update(@PathVariable UUID id, @RequestBody InfluencerSaleAttribution attribution) {
        InfluencerSaleAttribution existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("InfluencerSaleAttribution not found"));
        existing.setBrandId(attribution.getBrandId());
        existing.setCampaignCodeId(attribution.getCampaignCodeId());
        existing.setCampaignId(attribution.getCampaignId());
        existing.setCreatorId(attribution.getCreatorId());
        existing.setCampaignCreatorId(attribution.getCampaignCreatorId());
        existing.setPlatform(attribution.getPlatform());
        existing.setStatus(attribution.getStatus());
        existing.setOrderId(attribution.getOrderId());
        existing.setOrderLineId(attribution.getOrderLineId());
        existing.setCustomerExternalId(attribution.getCustomerExternalId());
        existing.setSaleAmount(attribution.getSaleAmount());
        existing.setDiscountAmount(attribution.getDiscountAmount());
        existing.setNetAmount(attribution.getNetAmount());
        existing.setCommissionAmount(attribution.getCommissionAmount());
        existing.setCurrency(attribution.getCurrency());
        existing.setOccurredAt(attribution.getOccurredAt());
        existing.setTrackedAt(attribution.getTrackedAt());
        existing.setRawPayload(attribution.getRawPayload());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
