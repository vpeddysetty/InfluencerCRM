package com.influencer.campaign.api;

import com.influencer.campaign.domain.CampaignBrief;
import com.influencer.campaign.infrastructure.CampaignBriefRepository;
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
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID campaignId) {
        if (brandId != null && campaignId != null) {
            return repository.findByBrandIdAndCampaignId(brandId, campaignId)
                    .map(List::of).orElseGet(List::of);
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
        existing.setBrandId(brief.getBrandId());
        existing.setCampaignId(brief.getCampaignId());
        // content/assets/hashtags are NOT NULL jsonb; a PUT that omits them must not
        // null the column — keep incoming, else existing, else the default.
        existing.setContent(firstNonNull(brief.getContent(), existing.getContent(), "{}"));
        existing.setAssets(firstNonNull(brief.getAssets(), existing.getAssets(), "[]"));
        existing.setHashtags(firstNonNull(brief.getHashtags(), existing.getHashtags(), "[]"));
        existing.setDisclosureText(brief.getDisclosureText());
        existing.setStatus(brief.getStatus());
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
