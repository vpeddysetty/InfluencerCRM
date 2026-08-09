package com.influencer.webe.creator.infrastructure;

import com.influencer.webe.creator.application.SocialPlatformAdapter;
import com.influencer.webe.creator.application.SocialProfileGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Instagram and TikTok adapters, simulated until the platforms approve the app (roadmap M6.4).
 *
 * <p><b>Why these exist at all rather than simply leaving the platforms unregistered.</b> With no
 * adapter, "Instagram is not wired up yet" is invisible — the registry finds nothing, the gateway
 * quietly falls back, and the only record of the gap is a roadmap line. With them, the seam is a
 * real class: when Meta approves, {@link InstagramProfileAdapter} gets a body and an
 * {@code isConfigured()} that checks for a token, and nothing else in the system changes. That is
 * the difference between "start a project" and "fill in one method".
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
     * Instagram — blocked on Meta app review.
     *
     * <p>When approved this needs a Business account token and a second call for audience
     * insights, which is why the real version is 3 days rather than the 1 the roadmap implies for
     * "an adapter". See docs/platform-app-registration.md.
     */
    @Component
    public static class InstagramProfileAdapter extends SimulatedAdapter {
        public InstagramProfileAdapter(
                MockSocialProfileGateway simulation,
                @Value("${web-experience.creators.instagram-simulated:true}") boolean simulated) {
            super(simulation, simulated);
        }

        @Override
        public String platform() {
            return "instagram";
        }
    }

    /**
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
