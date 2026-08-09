package com.influencer.webe.payout;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Auto-discovers {@link PayoutProvider} beans, keyed by {@code key()}. */
@Component
public class PayoutProviderRegistry {
    private final Map<String, PayoutProvider> byKey = new LinkedHashMap<>();

    public PayoutProviderRegistry(List<PayoutProvider> providers) {
        for (PayoutProvider provider : providers) {
            byKey.put(provider.key(), provider);
        }
    }

    public Optional<PayoutProvider> find(String key) {
        return Optional.ofNullable(key == null ? null : byKey.get(key));
    }

    public List<PayoutProvider> all() {
        return List.copyOf(byKey.values());
    }
}
