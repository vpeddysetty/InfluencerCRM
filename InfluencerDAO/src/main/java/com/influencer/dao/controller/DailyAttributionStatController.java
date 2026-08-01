package com.influencer.dao.controller;

import com.influencer.dao.model.DailyAttributionStat;
import com.influencer.dao.repository.DailyAttributionStatRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/daily-attribution-stats")
public class DailyAttributionStatController {
    private final DailyAttributionStatRepository repository;

    public DailyAttributionStatController(DailyAttributionStatRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<DailyAttributionStat> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID creatorId,
            @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (userId != null && from != null && to != null) {
            return repository.findByUserIdAndDayBetween(userId, from, to);
        }
        if (userId != null && creatorId != null) {
            return repository.findByUserIdAndCreatorId(userId, creatorId);
        }
        if (userId != null && campaignId != null) {
            return repository.findByUserIdAndCampaignId(userId, campaignId);
        }
        if (userId != null) {
            return repository.findByUserId(userId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public DailyAttributionStat findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("DailyAttributionStat not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DailyAttributionStat create(@RequestBody DailyAttributionStat stat) {
        return repository.save(stat);
    }

    @PutMapping("/{id}")
    public DailyAttributionStat update(@PathVariable UUID id, @RequestBody DailyAttributionStat stat) {
        DailyAttributionStat existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("DailyAttributionStat not found"));
        existing.setUserId(stat.getUserId());
        existing.setDay(stat.getDay());
        existing.setCreatorId(stat.getCreatorId());
        existing.setCampaignId(stat.getCampaignId());
        existing.setChannel(stat.getChannel());
        existing.setClicks(stat.getClicks());
        existing.setOrders(stat.getOrders());
        existing.setGrossSales(stat.getGrossSales());
        existing.setDiscounts(stat.getDiscounts());
        existing.setCommission(stat.getCommission());
        existing.setRefunds(stat.getRefunds());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
