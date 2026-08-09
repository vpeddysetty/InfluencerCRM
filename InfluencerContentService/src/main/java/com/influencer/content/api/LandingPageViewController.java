package com.influencer.content.api;

import com.influencer.content.domain.LandingPageView;
import com.influencer.content.infrastructure.LandingPageViewRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/landing-page-views")
public class LandingPageViewController {
    private final LandingPageViewRepository repository;

    public LandingPageViewController(LandingPageViewRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<LandingPageView> findAll(
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID campaignCodeId) {
        if (campaignCodeId != null) {
            return repository.findByCampaignCodeId(campaignCodeId);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
        }
        return repository.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LandingPageView create(@RequestBody LandingPageView view) {
        return repository.save(view);
    }
}
