package com.influencer.webe.creator.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ceiling in front of the only AI spend a non-customer can reach (roadmap OP-25).
 *
 * <p>The behaviour worth pinning is the SHAPE rather than the number: that a page which stays
 * inside the ceiling is never affected, that one which exceeds it stops buying model calls, and
 * that the limit is per page — because a global counter would let one abused page silence
 * enrichment for every other brand on the platform.
 */
class PublicSignupRateLimiterTest {

    private static final int CEILING = 30;

    private PublicSignupRateLimiter limiter() {
        return new PublicSignupRateLimiter(true);
    }

    @Test
    @DisplayName("a page inside the ceiling keeps the model")
    void allowsNormalTraffic() {
        PublicSignupRateLimiter limiter = limiter();
        for (int i = 0; i < CEILING; i++) {
            assertThat(limiter.allowEnrichment("spring-drop"))
                    .as("submission %d should still be enriched", i + 1)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("past the ceiling the same page stops buying model calls")
    void refusesBeyondCeiling() {
        PublicSignupRateLimiter limiter = limiter();
        for (int i = 0; i < CEILING; i++) {
            limiter.allowEnrichment("spring-drop");
        }
        assertThat(limiter.allowEnrichment("spring-drop")).isFalse();
        // Still false, rather than resetting because the count kept climbing past the ceiling.
        assertThat(limiter.allowEnrichment("spring-drop")).isFalse();
    }

    @Test
    @DisplayName("the ceiling is per page, so one abused page cannot silence another brand")
    void limitIsPerSlug() {
        PublicSignupRateLimiter limiter = limiter();
        for (int i = 0; i <= CEILING; i++) {
            limiter.allowEnrichment("abused-page");
        }
        assertThat(limiter.allowEnrichment("abused-page")).isFalse();
        assertThat(limiter.allowEnrichment("someone-elses-page")).isTrue();
    }

    @Test
    @DisplayName("slugs are matched case- and whitespace-insensitively, so padding cannot buy a fresh budget")
    void normalizesTheKey() {
        PublicSignupRateLimiter limiter = limiter();
        for (int i = 0; i < CEILING; i++) {
            limiter.allowEnrichment("spring-drop");
        }
        assertThat(limiter.allowEnrichment("  SPRING-Drop  ")).isFalse();
    }

    @Test
    @DisplayName("disabled by configuration, it never refuses")
    void canBeSwitchedOff() {
        PublicSignupRateLimiter limiter = new PublicSignupRateLimiter(false);
        for (int i = 0; i < CEILING * 3; i++) {
            assertThat(limiter.allowEnrichment("spring-drop")).isTrue();
        }
    }

    @Test
    @DisplayName("a null slug is bounded rather than throwing")
    void toleratesNullSlug() {
        PublicSignupRateLimiter limiter = limiter();
        assertThat(limiter.allowEnrichment(null)).isTrue();
        for (int i = 0; i < CEILING; i++) {
            limiter.allowEnrichment(null);
        }
        assertThat(limiter.allowEnrichment(null)).isFalse();
    }
}
