package com.influencer.dao.content.api;

import com.influencer.dao.content.domain.Asset;
import com.influencer.dao.content.infrastructure.AssetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Asset metadata CRUD (roadmap Phase B).
 *
 * Metadata only — bytes are handled by the BFF's AssetStoragePort and never pass through
 * here. Tenancy is enforced one layer up by the BFF, matching how every other controller
 * in this service works.
 */
@RestController
@RequestMapping("/assets")
public class AssetController {
    private final AssetRepository repository;

    public AssetController(AssetRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Asset> findAll(@RequestParam(required = false) UUID brandId) {
        // Unlike some sibling controllers, an unfiltered call returns nothing rather than
        // every tenant's rows. The listing has exactly one legitimate caller and it always
        // knows its brand, so "no brand" is a bug in the caller, not a request for all rows.
        if (brandId == null) {
            return List.of();
        }
        return repository.findByBrandIdOrderByCreatedAtDesc(brandId);
    }

    @GetMapping("/{id}")
    public Asset findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Asset not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Asset create(@RequestBody Asset asset) {
        return repository.save(asset);
    }

    // No PUT. An asset's bytes are immutable once written — replacing an image means
    // uploading a new one, which keeps storage_key stable for anything already referencing it.

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
