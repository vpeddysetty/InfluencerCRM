package com.influencer.content.api;

import com.influencer.content.domain.BrandDomain;
import com.influencer.content.infrastructure.BrandDomainRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Brand-owned domains (roadmap Phase E). Tenancy is enforced by the BFF. */
@RestController
@RequestMapping("/brand-domains")
public class BrandDomainController {
    private final BrandDomainRepository repository;

    public BrandDomainController(BrandDomainRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<BrandDomain> findAll(@RequestParam(required = false) UUID brandId,
                                     @RequestParam(required = false) UUID landingTemplateId,
                                     @RequestParam(required = false) String domainName) {
        if (domainName != null) {
            return repository.findByDomainName(domainName).map(List::of).orElseGet(List::of);
        }
        if (landingTemplateId != null) {
            return repository.findByLandingTemplateId(landingTemplateId);
        }
        if (brandId != null) {
            return repository.findByBrandIdOrderByCreatedAtDesc(brandId);
        }
        return List.of();
    }

    @GetMapping("/{id}")
    public BrandDomain findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("BrandDomain not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BrandDomain create(@RequestBody BrandDomain domain) {
        return repository.save(domain);
    }

    @PutMapping("/{id}")
    public BrandDomain update(@PathVariable UUID id, @RequestBody BrandDomain domain) {
        BrandDomain existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("BrandDomain not found"));
        // domain_name and verification_token are immutable: changing either after the brand has
        // published DNS records would silently invalidate a verification they already completed.
        existing.setDnsStatus(domain.getDnsStatus());
        existing.setVerifiedAt(domain.getVerifiedAt());
        existing.setSslStatus(domain.getSslStatus());
        existing.setSslIssuedAt(domain.getSslIssuedAt());
        existing.setLandingTemplateId(domain.getLandingTemplateId());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
