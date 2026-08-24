package com.influencer.webe.content.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-discovers {@link PageGenerationPort} beans, keyed by {@code key()} (roadmap PR-35).
 *
 * <p>Deliberately the same shape as {@code BillingProviderRegistry} and {@code PayoutProviderRegistry}
 * — {@code List<T>} injection plus a property naming the active key — so adding a second model
 * provider is a new {@code @Component} and a config change, and no call site moves.
 *
 * <p><b>Falls back to {@code template} rather than throwing on an unknown key</b>, for the reason
 * spelled out in the billing registry: a typo in the environment variable should degrade to the
 * implementation that is always safe, not to whichever bean happens to be first and not to an
 * application that will not start.
 */
@Component
public class PageGeneratorRegistry {

    /** The provider that always exists. See {@code TemplatePageGenerator}. */
    private static final String FALLBACK_KEY = "template";

    private final Map<String, PageGenerationPort> byKey = new LinkedHashMap<>();
    private final String activeKey;

    public PageGeneratorRegistry(
            List<PageGenerationPort> generators,
            @Value("${web-experience.landing.generation.provider:template}") String activeKey) {
        for (PageGenerationPort generator : generators) {
            byKey.put(generator.key(), generator);
        }
        this.activeKey = activeKey == null || activeKey.isBlank() ? FALLBACK_KEY : activeKey.trim();
    }

    /**
     * The generator configured to produce drafts.
     *
     * <p>Note this can return the fallback even when the property names something else: the
     * Anthropic bean is {@code @ConditionalOnProperty}-gated, so an environment that selects it
     * without supplying the rest of its configuration resolves here rather than at call time.
     */
    public PageGenerationPort active() {
        PageGenerationPort generator = byKey.get(activeKey);
        return generator != null ? generator : fallback();
    }

    /**
     * The generator used when the preferred one produces nothing.
     *
     * <p>Never null in a running application — {@code TemplatePageGenerator} carries no
     * {@code @ConditionalOnProperty}, so the only way to lose it is to delete the bean.
     */
    public PageGenerationPort fallback() {
        PageGenerationPort template = byKey.get(FALLBACK_KEY);
        if (template == null) {
            throw new IllegalStateException("No page generator is available, not even the template generator");
        }
        return template;
    }
}
