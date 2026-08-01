package com.influencer.dao.controller;

import com.influencer.dao.model.InfluencerPayout;
import com.influencer.dao.repository.InfluencerPayoutRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/influencer-payouts")
public class InfluencerPayoutController {
    private final InfluencerPayoutRepository repository;

    public InfluencerPayoutController(InfluencerPayoutRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<InfluencerPayout> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID creatorId,
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
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public InfluencerPayout findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("InfluencerPayout not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InfluencerPayout create(@RequestBody InfluencerPayout payout) {
        return repository.save(payout);
    }

    @PutMapping("/{id}")
    public InfluencerPayout update(@PathVariable UUID id, @RequestBody InfluencerPayout payout) {
        InfluencerPayout existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("InfluencerPayout not found"));
        existing.setUserId(payout.getUserId());
        existing.setCreatorId(payout.getCreatorId());
        existing.setPeriodStart(payout.getPeriodStart());
        existing.setPeriodEnd(payout.getPeriodEnd());
        existing.setTotalAmount(payout.getTotalAmount());
        existing.setCurrency(payout.getCurrency());
        existing.setMethod(payout.getMethod());
        existing.setProviderKey(payout.getProviderKey());
        existing.setProviderRef(payout.getProviderRef());
        existing.setStatus(payout.getStatus());
        existing.setNotes(payout.getNotes());
        existing.setPaidAt(payout.getPaidAt());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
