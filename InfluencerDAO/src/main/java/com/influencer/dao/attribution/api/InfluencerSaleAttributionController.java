package com.influencer.dao.attribution.api;

import com.influencer.dao.attribution.domain.InfluencerSaleAttribution;
import com.influencer.dao.attribution.infrastructure.InfluencerSaleAttributionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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
            @RequestParam(required = false) UUID campaignCreatorId,
            // Half-open window (roadmap OP-39). `until` is the start of the day AFTER the inclusive
            // end date -- see the repository note. Both must be present to narrow: one alone is
            // ambiguous, and guessing the missing half would silently change what a caller asked
            // for. Absent, the behaviour is exactly what it was before this parameter existed.
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant until) {
        if (brandId != null && from != null && until != null) {
            return repository
                    .findByBrandIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(brandId, from, until);
        }
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
