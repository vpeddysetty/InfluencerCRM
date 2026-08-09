package com.influencer.dao.creator.api;

import com.influencer.dao.creator.domain.VettingRule;
import com.influencer.dao.creator.infrastructure.VettingRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Per-brand vetting rules (roadmap C2.2). Tenancy is enforced by the BFF. */
@RestController
@RequestMapping("/vetting-rules")
public class VettingRuleController {
    private final VettingRuleRepository repository;

    public VettingRuleController(VettingRuleRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<VettingRule> findAll(@RequestParam(required = false) UUID brandId) {
        // Unfiltered returns nothing rather than every tenant's rules: the only legitimate
        // caller always knows its brand.
        if (brandId == null) {
            return List.of();
        }
        return repository.findByBrandIdOrderByPositionAsc(brandId);
    }

    @GetMapping("/{id}")
    public VettingRule findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("VettingRule not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VettingRule create(@RequestBody VettingRule rule) {
        return repository.save(rule);
    }

    @PutMapping("/{id}")
    public VettingRule update(@PathVariable UUID id, @RequestBody VettingRule rule) {
        VettingRule existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("VettingRule not found"));
        existing.setName(rule.getName());
        existing.setPosition(rule.getPosition());
        existing.setEnabled(rule.getEnabled());
        existing.setAction(rule.getAction());
        existing.setReason(rule.getReason());
        if (rule.getCondition() != null) {
            existing.setCondition(rule.getCondition());
        }
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
