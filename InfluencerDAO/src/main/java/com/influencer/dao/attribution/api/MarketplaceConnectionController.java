package com.influencer.dao.attribution.api;

import com.influencer.dao.attribution.domain.MarketplaceConnection;
import com.influencer.dao.attribution.infrastructure.MarketplaceConnectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/marketplace-connections")
public class MarketplaceConnectionController {
    private final MarketplaceConnectionRepository repository;

    public MarketplaceConnectionController(MarketplaceConnectionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<MarketplaceConnection> findAll(
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) String providerKey,
            @RequestParam(required = false) String externalAccountRef) {
        // Store lookup for webhook authentication. Intentionally reachable without a brand — the
        // caller is an inbound webhook that does not know its brand yet, and this is what tells it.
        // Safe because it returns at most the one connection matching a provider's own store id,
        // and the BFF then verifies the signature against that row before trusting it.
        if (providerKey != null && externalAccountRef != null) {
            return repository.findByProviderKeyAndExternalAccountRef(providerKey, externalAccountRef);
        }
        if (brandId != null && providerKey != null) {
            return repository.findByBrandIdAndProviderKey(brandId, providerKey);
        }
        if (brandId != null) {
            return repository.findByBrandId(brandId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public MarketplaceConnection findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("MarketplaceConnection not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MarketplaceConnection create(@RequestBody MarketplaceConnection connection) {
        return repository.save(connection);
    }

    @PutMapping("/{id}")
    public MarketplaceConnection update(@PathVariable UUID id, @RequestBody MarketplaceConnection connection) {
        MarketplaceConnection existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("MarketplaceConnection not found"));
        existing.setBrandId(connection.getBrandId());
        existing.setProviderKey(connection.getProviderKey());
        existing.setDisplayName(connection.getDisplayName());
        existing.setStatus(connection.getStatus());
        existing.setCredentialsEncrypted(connection.getCredentialsEncrypted());
        existing.setExternalAccountRef(connection.getExternalAccountRef());
        existing.setSyncCursor(connection.getSyncCursor());
        existing.setMetadata(connection.getMetadata());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
