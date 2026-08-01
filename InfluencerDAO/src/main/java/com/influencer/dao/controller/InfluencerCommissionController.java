package com.influencer.dao.controller;

import com.influencer.dao.model.InfluencerCommission;
import com.influencer.dao.repository.InfluencerCommissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/influencer-commissions")
public class InfluencerCommissionController {
    private final InfluencerCommissionRepository repository;

    public InfluencerCommissionController(InfluencerCommissionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<InfluencerCommission> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID creatorId,
            @RequestParam(required = false) UUID payoutId,
            @RequestParam(required = false) String status) {
        if (userId != null && status != null) {
            return repository.findByUserIdAndStatus(userId, status);
        }
        if (userId != null && creatorId != null) {
            return repository.findByUserIdAndCreatorId(userId, creatorId);
        }
        if (userId != null) {
            return repository.findByUserId(userId);
        }
        if (creatorId != null) {
            return repository.findByCreatorId(creatorId);
        }
        if (payoutId != null) {
            return repository.findByPayoutId(payoutId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public InfluencerCommission findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("InfluencerCommission not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InfluencerCommission create(@RequestBody InfluencerCommission commission) {
        return repository.save(commission);
    }

    @PutMapping("/{id}")
    public InfluencerCommission update(@PathVariable UUID id, @RequestBody InfluencerCommission commission) {
        InfluencerCommission existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("InfluencerCommission not found"));
        existing.setUserId(commission.getUserId());
        existing.setAttributionId(commission.getAttributionId());
        existing.setCreatorId(commission.getCreatorId());
        existing.setCampaignId(commission.getCampaignId());
        existing.setGrossSale(commission.getGrossSale());
        existing.setCommissionAmount(commission.getCommissionAmount());
        existing.setCurrency(commission.getCurrency());
        existing.setStatus(commission.getStatus());
        existing.setApprovedAt(commission.getApprovedAt());
        existing.setPayoutId(commission.getPayoutId());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
