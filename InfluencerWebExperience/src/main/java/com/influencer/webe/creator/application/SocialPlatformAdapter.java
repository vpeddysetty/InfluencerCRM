package com.influencer.webe.creator.application;

/**
 * Reads profiles from <b>one</b> social platform (roadmap M6.2).
 *
 * <p>The SPI {@link SocialProfileGateway} lacked. That interface takes {@code platform} as an
 * argument, but the only implementation ignored it except to echo it back — so "which platform"
 * was a parameter the system carried around and never acted on. Every platform's quirks would
 * otherwise have to live in one class: YouTube keys off a channel handle and returns view counts,
 * Instagram needs a Business account and a separate endpoint for audience insights, TikTok returns
 * neither in the same shape.
 *
 * <p>Adapters are auto-discovered by {@link SocialPlatformRegistry} through {@code List<T>}
 * injection, the same mechanism {@code MarketplaceProviderRegistry} already proves is drop-in.
 * Adding a platform is a new {@code @Component}; nothing else changes.
 *
 * <p><b>Report {@link SocialProfileGateway.Profile#source()} honestly.</b> A real platform answer
 * is {@code platform_api}; anything simulated is {@code mock}. The value is written to the creator
 * row and exported in CSV, so a brand — and their client — can always tell measured from invented.
 * An adapter that lied here would be worse than no adapter.
 */
public interface SocialPlatformAdapter {

    /**
     * The platform this adapter serves, lowercase: {@code instagram}, {@code tiktok},
     * {@code youtube}.
     *
     * <p>Matched against the {@code platform} column on a creator, which the UI writes from a
     * fixed select, so the vocabulary is closed.
     */
    String platform();

    /**
     * Whether this adapter can currently reach its platform.
     *
     * <p>Separate from existence because a registered adapter may be unusable: no API key, an app
     * still in review, a revoked token. A false answer routes the lookup to the mock rather than
     * failing, so a brand keeps a usable number and the {@code source} field records that it is
     * simulated. That is the C.6 rule — degrade to the manual path, never fail the signup.
     */
    default boolean isConfigured() {
        return true;
    }

    /**
     * Look up a handle on this platform.
     *
     * @return the profile, or null when it cannot be resolved. Null is expected and ordinary:
     *         private accounts, typos, deleted profiles, rate limits.
     */
    SocialProfileGateway.Profile fetch(String handle);
}
