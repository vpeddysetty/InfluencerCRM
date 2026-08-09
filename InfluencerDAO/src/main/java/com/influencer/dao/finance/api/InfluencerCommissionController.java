package com.influencer.dao.finance.api;

import com.influencer.dao.finance.domain.InfluencerCommission;
import com.influencer.dao.finance.application.CommissionService;
import com.influencer.dao.finance.infrastructure.InfluencerCommissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/influencer-commissions")
public class InfluencerCommissionController {
    private final InfluencerCommissionRepository repository;
    private final CommissionService commissionService;

    public InfluencerCommissionController(InfluencerCommissionRepository repository,
                                          CommissionService commissionService) {
        this.repository = repository;
        this.commissionService = commissionService;
    }

    @GetMapping
    public List<InfluencerCommission> findAll(
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID creatorId,
            @RequestParam(required = false) UUID payoutId,
            @RequestParam(required = false) String status) {
        if (brandId != null && status != null) {
            return repository.findByBrandIdAndStatus(brandId, status);
        }
        if (brandId != null && creatorId != null) {
            return repository.findByBrandIdAndCreatorId(brandId, creatorId);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
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
        return commissionService.accrue(commission);
    }

    @PutMapping("/{id}")
    public InfluencerCommission update(@PathVariable UUID id, @RequestBody InfluencerCommission commission) {
        InfluencerCommission existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("InfluencerCommission not found"));
        existing.setBrandId(commission.getBrandId());
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


    /**
     * Approves a commission.
     *
     * <p>A behavioral endpoint rather than a PUT of the whole row: "approve" is the thing the
     * caller means, and expressing it directly lets the Finance context enforce the transition
     * (an already-paid commission cannot be re-approved) instead of trusting whatever status the
     * caller happened to send.
     */
    @PostMapping("/{id}/approve")
    public InfluencerCommission approve(@PathVariable UUID id,
                                        @RequestParam(required = false) UUID approvedByUserId) {
        return commissionService.approve(id, approvedByUserId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
