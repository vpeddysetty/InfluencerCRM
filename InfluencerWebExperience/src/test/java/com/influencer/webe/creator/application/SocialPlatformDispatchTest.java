package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.creator.infrastructure.DispatchingSocialProfileGateway;
import com.influencer.webe.creator.infrastructure.MockSocialProfileGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards per-platform routing and the fallback rules (roadmap M6.2).
 *
 * <p>Before this, {@code SocialProfileGateway.fetch(platform, handle)} took the platform as an
 * argument and the only implementation ignored it — every creator on every platform got the same
 * hash-derived number from the same code path. These tests exist because the failure mode of
 * getting routing wrong is silent: a number still appears, it is simply the wrong one, or a real
 * number gets replaced by an invented one.
 */
class SocialPlatformDispatchTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MockSocialProfileGateway simulation = new MockSocialProfileGateway(mapper);

    /** An adapter that answers with a fixed, obviously-real-looking profile. */
    private static SocialPlatformAdapter adapter(String platform, boolean configured,
                                                 SocialProfileGateway.Profile answer) {
        return new SocialPlatformAdapter() {
            @Override public String platform() { return platform; }
            @Override public boolean isConfigured() { return configured; }
            @Override public SocialProfileGateway.Profile fetch(String handle) { return answer; }
        };
    }

    private static SocialProfileGateway.Profile realProfile(String platform, long followers) {
        return new SocialProfileGateway.Profile("@real", platform, "platform_api", followers,
                BigDecimal.valueOf(4.2), 1000L, true, null, null, "Real Creator", null);
    }

    private DispatchingSocialProfileGateway gatewayWith(SocialPlatformAdapter... adapters) {
        return new DispatchingSocialProfileGateway(
                new SocialPlatformRegistry(List.of(adapters)), simulation);
    }

    @Test
    @DisplayName("a lookup routes to the adapter for its platform")
    void routesByPlatform() {
        DispatchingSocialProfileGateway gateway = gatewayWith(
                adapter("youtube", true, realProfile("youtube", 50_000L)),
                adapter("instagram", true, realProfile("instagram", 1_234L)));

        assertEquals(50_000L, gateway.fetch("youtube", "@someone").followerCount());
        assertEquals(1_234L, gateway.fetch("instagram", "@someone").followerCount());
    }

    @Test
    @DisplayName("platform matching ignores case and surrounding whitespace")
    void platformMatchingIsForgiving() {
        // The column is written from a fixed select today, but an import or an API caller can put
        // "YouTube" in it, and silently falling back to the simulation for that row would replace
        // a real number with an invented one.
        DispatchingSocialProfileGateway gateway =
                gatewayWith(adapter("youtube", true, realProfile("youtube", 50_000L)));

        assertEquals("platform_api", gateway.fetch("YouTube", "@someone").source());
        assertEquals("platform_api", gateway.fetch("  youtube  ", "@someone").source());
    }

    @Test
    @DisplayName("a platform with no adapter falls back to the simulation")
    void unknownPlatformFallsBack() {
        DispatchingSocialProfileGateway gateway =
                gatewayWith(adapter("youtube", true, realProfile("youtube", 50_000L)));

        SocialProfileGateway.Profile profile = gateway.fetch("tiktok", "@someone");

        assertNotNull(profile, "an unrouted platform must still return a usable number (rule C.6)");
        assertEquals("mock", profile.source(), "and must be labelled as simulated");
    }

    @Test
    @DisplayName("an unconfigured adapter does not shadow the simulation")
    void unconfiguredAdapterFallsBack() {
        // The trap this closes: adding a YouTube adapter to a deployment with no API key would
        // otherwise turn working simulated numbers into nulls. A null reads as "this creator has
        // no audience" and silently fails every vetting rule written as `followers < 5000`.
        DispatchingSocialProfileGateway gateway =
                gatewayWith(adapter("youtube", false, realProfile("youtube", 50_000L)));

        SocialProfileGateway.Profile profile = gateway.fetch("youtube", "@someone");

        assertNotNull(profile);
        assertEquals("mock", profile.source());
    }

    @Test
    @DisplayName("a configured adapter answering 'no such handle' is believed, not overridden")
    void nullFromRealAdapterIsNotReplacedBySimulation() {
        // The inverse trap, and the more damaging one. A real platform saying "this handle does
        // not exist" is information. Substituting an invented follower count would turn a correct
        // negative into a plausible fabrication on a row the UI labels platform_api.
        DispatchingSocialProfileGateway gateway = gatewayWith(adapter("youtube", true, null));

        assertNull(gateway.fetch("youtube", "@does-not-exist"),
                "a real adapter's negative answer must not be replaced by a simulated positive");
    }

    @Test
    @DisplayName("an adapter that throws falls back rather than failing the caller")
    void throwingAdapterFallsBack() {
        // An adapter bug must not fail a signup. Rule C.6 again.
        SocialPlatformAdapter broken = new SocialPlatformAdapter() {
            @Override public String platform() { return "youtube"; }
            @Override public SocialProfileGateway.Profile fetch(String handle) {
                throw new IllegalStateException("adapter is broken");
            }
        };

        SocialProfileGateway.Profile profile = gatewayWith(broken).fetch("youtube", "@someone");

        assertNotNull(profile);
        assertEquals("mock", profile.source());
    }

    @Test
    @DisplayName("the registry offers only adapters that can currently answer")
    void registryHidesUnconfiguredAdapters() {
        SocialPlatformRegistry registry = new SocialPlatformRegistry(List.of(
                adapter("youtube", true, null),
                adapter("instagram", false, null)));

        assertTrue(registry.find("youtube").isPresent());
        assertTrue(registry.find("instagram").isEmpty(), "unconfigured adapters must not be offered");
        assertTrue(registry.find("tiktok").isEmpty());
        // all() still reports both, so a diagnostics screen can state the truth rather than imply
        // that an unconfigured platform is simply absent.
        assertEquals(2, registry.all().size());
    }

    @Test
    @DisplayName("no simulated adapter ever reports platform_api")
    void simulationNeverClaimsToBeReal() {
        // The single invariant this whole design protects: a brand, and their client, must always
        // be able to tell a measured number from an invented one.
        DispatchingSocialProfileGateway gateway = gatewayWith();

        for (String platform : List.of("instagram", "tiktok", "youtube", "other")) {
            SocialProfileGateway.Profile profile = gateway.fetch(platform, "@someone");
            assertEquals("mock", profile.source(),
                    platform + " fell back to the simulation and must say so");
        }
    }
}
