package com.influencer.dao.controller;

import com.influencer.dao.model.MarketplaceConnection;
import com.influencer.dao.repository.MarketplaceConnectionRepository;
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
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String providerKey) {
        if (userId != null && providerKey != null) {
            return repository.findByUserIdAndProviderKey(userId, providerKey);
        }
        if (userId != null) {
            return repository.findByUserId(userId);
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
        existing.setUserId(connection.getUserId());
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
