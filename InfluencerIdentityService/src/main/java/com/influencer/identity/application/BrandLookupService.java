package com.influencer.identity.application;

import com.influencer.identity.domain.Brand;
import com.influencer.identity.infrastructure.BrandRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/** The Identity context's in-process implementation of {@link BrandLookupPort}. */
@Service
public class BrandLookupService implements BrandLookupPort {

    private final BrandRepository brandRepository;

    public BrandLookupService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    @Override
    public boolean brandExists(UUID brandId) {
        return brandId != null && brandRepository.existsById(brandId);
    }

    @Override
    public Optional<String> brandName(UUID brandId) {
        if (brandId == null) {
            return Optional.empty();
        }
        return brandRepository.findById(brandId).map(Brand::getName);
    }
}
