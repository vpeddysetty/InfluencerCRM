package com.influencer.webe.marketplace;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auto-discovers every {@link MarketplaceProvider} bean and keys them by
 * {@link MarketplaceProvider#key()}. Adding a marketplace requires no wiring here
 * — just a new {@code @Component} implementing the SPI.
 */
@Component
public class MarketplaceProviderRegistry {
    private final Map<String, MarketplaceProvider> byKey = new LinkedHashMap<>();

    public MarketplaceProviderRegistry(List<MarketplaceProvider> providers) {
        for (MarketplaceProvider provider : providers) {
            byKey.put(provider.key(), provider);
        }
    }

    public Optional<MarketplaceProvider> find(String key) {
        return Optional.ofNullable(key == null ? null : byKey.get(key));
    }

    public MarketplaceProvider require(String key) {
        return find(key).orElseThrow(() ->
                new IllegalArgumentException("Unknown marketplace provider: " + key));
    }

    public List<MarketplaceProvider> all() {
        return List.copyOf(byKey.values());
    }
}
