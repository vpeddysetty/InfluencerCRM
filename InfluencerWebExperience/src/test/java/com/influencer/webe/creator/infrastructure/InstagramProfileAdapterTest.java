package com.influencer.webe.creator.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.creator.application.SocialProfileGateway;
import com.influencer.webe.shared.infrastructure.OutboundHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Instagram Graph API read (roadmap M6.4).
 *
 * <p>These test the parsing and the refusals, not the network. The failure modes worth catching are
 * all silent: a handle that does not resolve becoming a zeroed profile, a half-configured
 * deployment returning nulls instead of falling back, or invented demographics appearing on a row
 * labelled {@code platform_api}.
 */
class InstagramProfileAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** An OutboundHttpClient that returns a canned body and records the URL it was asked for. */
    private OutboundHttpClient clientReturning(String json, AtomicReference<String> capturedUrl) {
        return new OutboundHttpClient(mapper, 1000, 1000) {
            @Override
            public Optional<JsonNode> getJson(String url, Map<String, String> headers) {
                if (capturedUrl != null) {
                    capturedUrl.set(url);
                }
                if (json == null) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(mapper.readTree(json));
                } catch (Exception e) {
                    return Optional.empty();
                }
            }
        };
    }

    private InstagramProfileAdapter adapterReturning(String json) {
        return new InstagramProfileAdapter(
                clientReturning(json, null), mapper, "test-token", "17841400000000000", "");
    }

    private static String discoveryBody(String inner) {
        return "{\"business_discovery\":" + inner + ",\"id\":\"17841400000000000\"}";
    }

    @Test
    @DisplayName("a resolved handle returns a profile labelled platform_api")
    void resolvesHandle() {
        InstagramProfileAdapter adapter = adapterReturning(discoveryBody("""
                {"username":"creator","name":"A Creator","followers_count":10000,"media_count":42,
                 "media":{"data":[
                   {"like_count":400,"comments_count":100,"timestamp":"2026-08-01T10:00:00+0000"},
                   {"like_count":600,"comments_count":100,"timestamp":"2026-07-01T10:00:00+0000"}]}}"""));

        SocialProfileGateway.Profile profile = adapter.fetch("@creator");

        assertNotNull(profile);
        assertEquals("platform_api", profile.source(), "a real read must say so");
        assertEquals("instagram", profile.platform());
        assertEquals(10_000L, profile.followerCount());
        assertEquals("A Creator", profile.displayName());
        assertEquals("@creator", profile.handle());
    }

    @Test
    @DisplayName("engagement averages interactions per post against followers")
    void computesEngagement() {
        // (500 + 700) / 2 posts = 600 mean interactions; 600/10000 = 6.00%.
        InstagramProfileAdapter adapter = adapterReturning(discoveryBody("""
                {"username":"creator","followers_count":10000,
                 "media":{"data":[
                   {"like_count":400,"comments_count":100},
                   {"like_count":600,"comments_count":100}]}}"""));

        SocialProfileGateway.Profile profile = adapter.fetch("creator");

        assertEquals(0, BigDecimal.valueOf(6.00).compareTo(profile.engagementRate()),
                "engagement must be per-post, not a sum that scales with sample size");
        assertEquals(600L, profile.averageViews());
    }

    @Test
    @DisplayName("posts with hidden counts are skipped, not counted as zero")
    void hiddenLikeCountsAreSkipped() {
        // Hidden like counts are a user setting. Counting them as zero would drag the average down
        // and understate a creator a brand would otherwise pick.
        InstagramProfileAdapter adapter = adapterReturning(discoveryBody("""
                {"username":"creator","followers_count":1000,
                 "media":{"data":[
                   {"like_count":100,"comments_count":0},
                   {"timestamp":"2026-08-01T10:00:00+0000"}]}}"""));

        SocialProfileGateway.Profile profile = adapter.fetch("creator");

        assertEquals(0, BigDecimal.valueOf(10.00).compareTo(profile.engagementRate()),
                "the post with no counts must be excluded from the average entirely");
    }

    @Test
    @DisplayName("an unresolvable handle returns null, never a zeroed profile")
    void unresolvableHandleIsNull() {
        // A typo, a private account, or a personal account business_discovery cannot see. Writing 0
        // would make "we could not check" indistinguishable from "they have no audience", and would
        // silently pass every vetting rule written as `followers < 5000`.
        assertNull(adapterReturning("{\"id\":\"17841400000000000\"}").fetch("@ghost"));
        assertNull(adapterReturning(null).fetch("@ghost"), "a failed call is also null");
    }

    @Test
    @DisplayName("demographics are left null rather than invented")
    void demographicsAreNotFabricated() {
        // business_discovery exposes no audience breakdown — the insights edge answers only for the
        // connected account itself. Inventing one here would put fabricated data on a row the UI
        // labels platform_api, which is exactly what `source` exists to prevent.
        SocialProfileGateway.Profile profile = adapterReturning(discoveryBody("""
                {"username":"creator","followers_count":10000,"media":{"data":[]}}""")).fetch("creator");

        assertNull(profile.demographics());
        assertNull(profile.verified(), "no verification flag on this edge means unknown, not false");
    }

    @Test
    @DisplayName("last-active takes the newest post, not the first in the list")
    void lastActiveTakesMaximum() {
        // Meta returns media newest-first, but that is ordering rather than a documented guarantee.
        // Taking the first element would turn "last active" into "first ever posted" if that
        // changed — a silent, plausible, wrong answer of the kind health monitoring must catch.
        SocialProfileGateway.Profile profile = adapterReturning(discoveryBody("""
                {"username":"creator","followers_count":100,
                 "media":{"data":[
                   {"like_count":1,"timestamp":"2026-01-01T10:00:00+0000"},
                   {"like_count":1,"timestamp":"2026-08-01T10:00:00+0000"}]}}""")).fetch("creator");

        assertEquals("2026-08-01T10:00:00+0000", profile.lastActiveAt());
    }

    @Test
    @DisplayName("captions are collected for the agent service to classify")
    void collectsCaptions() {
        SocialProfileGateway.Profile profile = adapterReturning(discoveryBody("""
                {"username":"creator","followers_count":100,
                 "media":{"data":[{"caption":"first post"},{"caption":"second post"}]}}""")).fetch("creator");

        assertTrue(profile.recentCaptions().contains("first post"));
        assertTrue(profile.recentCaptions().contains("second post"));
    }

    @Test
    @DisplayName("both a token and an account id are required to be configured")
    void needsBothCredentials() {
        // Neither implies the other: the token authorises the call, the account id is whose
        // connection it travels through. Half-configured must fall back to the simulation rather
        // than return nulls for every Instagram creator.
        OutboundHttpClient http = clientReturning("{}", null);

        assertFalse(new InstagramProfileAdapter(http, mapper, "", "17841400000000000", "").isConfigured());
        assertFalse(new InstagramProfileAdapter(http, mapper, "token", "", "").isConfigured());
        assertFalse(new InstagramProfileAdapter(http, mapper, "replace-me", "17841", "").isConfigured(),
                "the placeholder convention must read as absent, not as a credential");
        assertTrue(new InstagramProfileAdapter(http, mapper, "token", "17841", "").isConfigured());
    }

    @Test
    @DisplayName("an unconfigured adapter never calls the API")
    void unconfiguredDoesNotCall() {
        AtomicReference<String> url = new AtomicReference<>();
        InstagramProfileAdapter adapter =
                new InstagramProfileAdapter(clientReturning("{}", url), mapper, "", "", "");

        assertNull(adapter.fetch("@creator"));
        assertNull(url.get(), "a call with no credentials would burn a request to learn it fails");
    }

    @Test
    @DisplayName("the handle is sent without its leading @")
    void stripsAtFromHandle() {
        // business_discovery.username() takes a bare username. Sending "@creator" resolves nothing,
        // which would look identical to a creator who does not exist.
        AtomicReference<String> url = new AtomicReference<>();
        new InstagramProfileAdapter(clientReturning(discoveryBody("""
                {"username":"creator","followers_count":1}"""), url), mapper, "t", "17841", "")
                .fetch("@creator");

        assertNotNull(url.get());
        assertFalse(url.get().contains("%40creator"), "the @ must be stripped before encoding");
        assertTrue(url.get().contains("creator"));
    }

    @Test
    @DisplayName("counts parse whether Meta sends numbers or strings")
    void tolerantOfNumericStrings() {
        // Meta returns these as JSON numbers today, and has returned them as strings before.
        SocialProfileGateway.Profile profile = adapterReturning(discoveryBody("""
                {"username":"creator","followers_count":"2500","media_count":"10"}""")).fetch("creator");

        assertEquals(2_500L, profile.followerCount());
    }

    @Test
    @DisplayName("a creator with no recent posts has unknown engagement, not zero")
    void noPostsMeansUnknownEngagement() {
        SocialProfileGateway.Profile profile = adapterReturning(discoveryBody("""
                {"username":"creator","followers_count":5000,"media":{"data":[]}}""")).fetch("creator");

        assertNotNull(profile, "the account exists; only its engagement is unknown");
        assertEquals(5_000L, profile.followerCount());
        assertNull(profile.engagementRate(), "zero would be a claim we cannot support");
    }

    @Test
    @DisplayName("our own handle reads the account directly, not through business_discovery")
    void ownHandleUsesDirectRead() {
        // business_discovery is gated on Advanced Access and answers (#10) for EVERY target while
        // the app is in review — including the account whose token we hold. Routing a self-lookup
        // through it would return null for the one account we are authorised to read, leaving no
        // way to demonstrate the integration to the reviewer who would grant the access.
        AtomicReference<String> url = new AtomicReference<>();
        OutboundHttpClient http = clientReturning("""
                {"username":"tejduxtest","name":"Tejdux","followers_count":2,"media_count":1,
                 "media":{"data":[{"like_count":0,"comments_count":0,
                                   "timestamp":"2026-08-16T19:30:09+0000"}]}}""", url);

        SocialProfileGateway.Profile profile =
                new InstagramProfileAdapter(http, mapper, "t", "17841", "tejduxtest")
                        .fetch("@tejduxtest");

        assertNotNull(profile);
        assertFalse(url.get().contains("business_discovery"),
                "a self-lookup must not go through the endpoint for reading strangers");
        assertEquals(2L, profile.followerCount());
        assertEquals("platform_api", profile.source(),
                "the direct read is no less real than discovery; only the endpoint differs");
    }

    @Test
    @DisplayName("any other handle still goes through business_discovery")
    void otherHandlesStillDiscover() {
        AtomicReference<String> url = new AtomicReference<>();
        OutboundHttpClient http = clientReturning(discoveryBody("""
                {"username":"someone","followers_count":900}"""), url);

        new InstagramProfileAdapter(http, mapper, "t", "17841", "tejduxtest").fetch("@someone");

        assertTrue(url.get().contains("business_discovery"),
                "reading a creator we do not own is exactly what discovery is for");
    }

    @Test
    @DisplayName("the self-match ignores case and a leading @ on either side")
    void selfMatchIsForgiving() {
        // The configured value and the typed handle are written by different people at different
        // times. A case difference silently routing to discovery would present as "(#10)" — an
        // error about permissions, for what is really a spelling mismatch.
        AtomicReference<String> url = new AtomicReference<>();
        OutboundHttpClient http = clientReturning("""
                {"username":"tejduxtest","followers_count":2}""", url);

        new InstagramProfileAdapter(http, mapper, "t", "17841", "@TejduxTest").fetch("tejduxtest");

        assertFalse(url.get().contains("business_discovery"));
    }

    @Test
    @DisplayName("with no self-username configured, everything goes through discovery")
    void blankSelfUsernameChangesNothing() {
        // The safe default. A wrong match here would read OUR metrics and label them as another
        // creator's — worse than a failed lookup, because it is plausible and wrong.
        AtomicReference<String> url = new AtomicReference<>();
        OutboundHttpClient http = clientReturning(discoveryBody("""
                {"username":"anyone","followers_count":5}"""), url);

        new InstagramProfileAdapter(http, mapper, "t", "17841", "").fetch("anyone");

        assertTrue(url.get().contains("business_discovery"));
    }
}
