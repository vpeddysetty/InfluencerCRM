package com.influencer.webe.creator.infrastructure;

import com.influencer.webe.creator.application.SocialPlatformAdapter;
import com.influencer.webe.creator.application.SocialProfileGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TikTok's adapter, simulated until the platform approves the app (roadmap M6.4).
 *
 * <p><b>Why this exists rather than simply leaving the platform unregistered.</b> With no adapter,
 * "TikTok is not wired up yet" is invisible — the registry finds nothing, the gateway quietly falls
 * back, and the only record of the gap is a roadmap line. With one, the seam is a real class.
 *
 * <p><b>Instagram used to live here and no longer does.</b> {@link InstagramProfileAdapter} is now
 * a real Graph API read, which is what this slot was holding open for. The replacement was exactly
 * the promised shape — a new class implementing the same interface, with no change at any call site
 * — so the pattern is worth keeping for TikTok rather than treating this file as scaffolding to
 * delete.
 *
 * <p><b>They delegate to {@link MockSocialProfileGateway} rather than inventing their own numbers.</b>
 * One simulation, so a creator's mock follower count does not change depending on which layer
 * answered — and so the engagement-falls-as-audience-grows relationship holds identically across
 * platforms.
 *
 * <p><b>They report {@code source = "mock"}.</b> Never {@code platform_api}. A brand can always
 * tell which numbers were measured; the UI badge (U4) reads exactly this field.
 */
public final class MockedPlatformAdapters {

    private MockedPlatformAdapters() {}

    /** Shared behaviour: defer to the existing mock, and never claim to be a platform read. */
    abstract static class SimulatedAdapter implements SocialPlatformAdapter {

        private final MockSocialProfileGateway simulation;
        private final boolean enabled;

        SimulatedAdapter(MockSocialProfileGateway simulation, boolean enabled) {
            this.simulation = simulation;
            this.enabled = enabled;
        }

        /**
         * Simulated adapters are "configured" only while their real counterpart is unavailable.
         *
         * <p>Flipping the flag off is what hands the platform over to a real adapter without
         * deleting this one, so a rollback is a config change rather than a revert.
         */
        @Override
        public boolean isConfigured() {
            return enabled;
        }

        @Override
        public SocialProfileGateway.Profile fetch(String handle) {
            // Delegating with this adapter's own platform string, so the returned profile is
            // labelled with the platform actually asked for rather than a generic one.
            return simulation.fetch(platform(), handle);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[simulated, enabled=" + enabled + "]";
        }
    }

    /**
     * TikTok — registration deferred by decision, not by rejection.
     *
     * TikTok — registration deferred by decision, not by rejection.
     *
     * <p>docs/platform-app-registration.md §2.1 holds a paste-ready package; the decision was to
     * not spend the review window yet. This adapter keeps the slot warm either way.
     */
    @Component
    public static class TikTokProfileAdapter extends SimulatedAdapter {
        public TikTokProfileAdapter(
                MockSocialProfileGateway simulation,
                @Value("${web-experience.creators.tiktok-simulated:true}") boolean simulated) {
            super(simulation, simulated);
        }

        @Override
        public String platform() {
            return "tiktok";
        }
    }
}
