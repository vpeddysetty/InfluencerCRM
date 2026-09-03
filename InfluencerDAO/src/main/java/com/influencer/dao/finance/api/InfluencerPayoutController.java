package com.influencer.dao.finance.api;

import com.influencer.dao.finance.domain.InfluencerPayout;
import com.influencer.dao.finance.infrastructure.InfluencerPayoutRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
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
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID creatorId,
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
        existing.setBrandId(payout.getBrandId());
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

    /**
     * What this brand has PAID this creator in a calendar year (roadmap PR-49).
     *
     * <p>Serves the 1099-NEC threshold check. Calendar year rather than rolling twelve months
     * because the IRS figure is a calendar-year one -- a rolling window would withhold payment from
     * someone under the actual limit.
     */
    @GetMapping("/paid-total")
    public Map<String, Object> paidTotal(@RequestParam UUID creatorId,
                                         @RequestParam UUID brandId,
                                         @RequestParam int year) {
        Instant from = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal total = repository.sumPaidBetween(creatorId, brandId, from, until);
        // Coalesced HERE rather than in the query, so "no payouts" and "zero paid" stay
        // distinguishable at the repository boundary.
        return Map.of("creatorId", creatorId, "brandId", brandId, "year", year,
                "paidTotal", total == null ? BigDecimal.ZERO : total);
    }
}
