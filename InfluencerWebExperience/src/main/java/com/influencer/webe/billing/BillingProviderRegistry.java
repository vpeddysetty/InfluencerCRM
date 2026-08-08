package com.influencer.webe.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auto-discovers {@link BillingProvider} beans, keyed by {@code key()}.
 *
 * <p>Mirrors {@code PayoutProviderRegistry}, which is already proven drop-in via {@code List<T>}
 * injection. Adding Stripe is a new {@code @Component} and a config change — no call site moves.
 */
@Component
public class BillingProviderRegistry {

    private final Map<String, BillingProvider> byKey = new LinkedHashMap<>();
    private final String activeKey;

    public BillingProviderRegistry(List<BillingProvider> providers,
                                   @Value("${web-experience.billing.provider:manual}") String activeKey) {
        for (BillingProvider provider : providers) {
            byKey.put(provider.key(), provider);
        }
        this.activeKey = activeKey == null || activeKey.isBlank() ? "manual" : activeKey.trim();
    }

    public Optional<BillingProvider> find(String key) {
        return Optional.ofNullable(key == null ? null : byKey.get(key));
    }

    /**
     * The provider configured to handle new subscriptions.
     *
     * <p>Falls back to {@code manual} rather than throwing when the configured key is unknown. A
     * typo in {@code WEBE_BILLING_PROVIDER} should degrade to the implementation that takes no
     * money and says so — not to whichever bean happens to be first, and not to a dead application.
     */
    public BillingProvider active() {
        BillingProvider provider = byKey.get(activeKey);
        if (provider != null) {
            return provider;
        }
        BillingProvider manual = byKey.get("manual");
        if (manual != null) {
            return manual;
        }
        throw new IllegalStateException("No billing provider is available, not even manual");
    }

    public List<BillingProvider> all() {
        return List.copyOf(byKey.values());
    }
}
