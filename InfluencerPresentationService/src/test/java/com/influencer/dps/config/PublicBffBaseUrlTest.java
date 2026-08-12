package com.influencer.dps.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OAuth start leg emits a {@code Location} header, so the URL it is built from must be one a
 * BROWSER can resolve. Using the internal service address instead is what took Google sign-in down:
 * the browser was redirected to {@code http://web-experience:8081}, a compose-network name that
 * exists only inside the task, and the sign-in silently never started.
 */
class PublicBffBaseUrlTest {

    @Test
    @DisplayName("the public URL is returned when set")
    void returnsConfiguredValue() {
        DpsProperties properties = new DpsProperties();
        properties.setPublicBffBaseUrl("https://api.tejdux.com");

        assertThat(properties.requirePublicBffBaseUrl()).isEqualTo("https://api.tejdux.com");
    }

    @Test
    @DisplayName("it does NOT silently fall back to the internal address")
    void doesNotFallBackToInternalUrl() {
        // The whole point. A fallback would look correct in development, where both values are
        // localhost, and emit an unreachable container hostname in production — reproducing the
        // original bug rather than preventing it.
        DpsProperties properties = new DpsProperties();
        properties.setBffBaseUrl("http://web-experience:8081");

        assertThatThrownBy(properties::requirePublicBffBaseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dps.public-bff-base-url");

        assertThat(properties.getBffBaseUrl())
                .as("the internal address is still available for server-to-server calls")
                .isEqualTo("http://web-experience:8081");
    }

    @Test
    @DisplayName("a blank value is as unset as a missing one")
    void blankIsRejected() {
        DpsProperties properties = new DpsProperties();
        properties.setPublicBffBaseUrl("   ");

        assertThatThrownBy(properties::requirePublicBffBaseUrl)
                .isInstanceOf(IllegalStateException.class);
    }
}
