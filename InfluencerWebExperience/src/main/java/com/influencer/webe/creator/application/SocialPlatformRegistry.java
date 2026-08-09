package com.influencer.webe.creator.application;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Auto-discovers every {@link SocialPlatformAdapter} and keys them by platform (roadmap M6.2).
 *
 * <p>Mirrors {@code MarketplaceProviderRegistry}, which already proves the pattern is genuinely
 * drop-in: adding a platform is one {@code @Component}, with no wiring here and no change at any
 * call site.
 *
 * <p>Only adapters reporting {@link SocialPlatformAdapter#isConfigured()} are offered. An adapter
 * that exists but has no API key must not shadow the mock — otherwise adding a class for a
 * platform still in app review would silently turn working simulated numbers into nulls.
 */
@Component
public class SocialPlatformRegistry {

    private final Map<String, SocialPlatformAdapter> byPlatform = new LinkedHashMap<>();

    public SocialPlatformRegistry(List<SocialPlatformAdapter> adapters) {
        for (SocialPlatformAdapter adapter : adapters) {
            String key = normalize(adapter.platform());
            if (!key.isEmpty()) {
                byPlatform.put(key, adapter);
            }
        }
    }

    /**
     * The adapter that can serve this platform right now, if any.
     *
     * <p>Empty means "fall back to the mock" — either no adapter is registered for the platform,
     * or the one that is cannot currently reach it.
     */
    public Optional<SocialPlatformAdapter> find(String platform) {
        SocialPlatformAdapter adapter = byPlatform.get(normalize(platform));
        if (adapter == null || !adapter.isConfigured()) {
            return Optional.empty();
        }
        return Optional.of(adapter);
    }

    /** Every registered adapter, configured or not — for diagnostics that must state the truth. */
    public List<SocialPlatformAdapter> all() {
        return List.copyOf(byPlatform.values());
    }

    private static String normalize(String platform) {
        return platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
    }
}
