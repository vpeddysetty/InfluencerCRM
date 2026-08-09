package com.influencer.webe.creator.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.creator.application.SocialPlatformAdapter;
import com.influencer.webe.creator.application.SocialProfileGateway;
import com.influencer.webe.shared.infrastructure.OutboundHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Real YouTube Data API v3 reads (roadmap M6.4).
 *
 * <p>The one platform that needs no app review: YouTube uses a server API key rather than an OAuth
 * app, and the key is already obtained (M0.1). Instagram and TikTok stay mocked until Meta and
 * TikTok approve, which is why this adapter exists separately rather than as one
 * "social adapters" block — approval for one platform should not gate the others.
 *
 * <p><b>Reports {@code platform_api}</b>, and is the first adapter in this codebase entitled to.
 * Every metric elsewhere is hash-derived and says {@code mock}. That difference is what
 * {@code metricsSource} exists to record, and what the UI badge surfaces.
 *
 * <p><b>Two calls, not one.</b> A handle resolves to a channel through {@code channels?forHandle},
 * which returns statistics in the same response — so the common case is one call. A legacy
 * {@code /user/} name or a channel id needs the fallback. Both paths are quota-charged, which is
 * why {@link #isConfigured()} gates on a key being present rather than letting a keyless call
 * burn a request to discover it will 403.
 */
@Component
public class YouTubeProfileAdapter implements SocialPlatformAdapter {

    private static final String API = "https://www.googleapis.com/youtube/v3";

    private final OutboundHttpClient http;
    private final String apiKey;

    public YouTubeProfileAdapter(
            OutboundHttpClient http,
            @Value("${web-experience.creators.youtube-api-key:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public String platform() {
        return "youtube";
    }

    /**
     * No key means this adapter cannot answer, and the registry must fall through to the mock.
     *
     * <p>Without this check a deployment that forgot the key would return nulls for every YouTube
     * creator — strictly worse than a simulated number, because a null reads as "this creator has
     * no audience" and silently fails every vetting rule written as {@code followers < 5000}.
     */
    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public SocialProfileGateway.Profile fetch(String handle) {
        if (!isConfigured() || handle == null || handle.isBlank()) {
            return null;
        }
        String normalized = handle.trim().replaceFirst("^@", "");
        if (normalized.isEmpty()) {
            return null;
        }

        Optional<JsonNode> response = http.getJson(
                API + "/channels?part=snippet,statistics&forHandle=@"
                        + URLEncoder.encode(normalized, StandardCharsets.UTF_8)
                        + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8),
                Map.of());

        JsonNode items = response.map(node -> node.path("items")).orElse(null);
        if (items == null || !items.isArray() || items.isEmpty()) {
            // Null, never a zeroed profile. A creator whose handle does not resolve has unknown
            // metrics, and writing 0 would make "we could not check" indistinguishable from
            // "they have no audience" — the same reasoning as the unset preferred rate.
            return null;
        }

        JsonNode channel = items.get(0);
        JsonNode stats = channel.path("statistics");
        JsonNode snippet = channel.path("snippet");

        Long subscribers = asLong(stats, "subscriberCount");
        Long totalViews = asLong(stats, "viewCount");
        Long videoCount = asLong(stats, "videoCount");

        // YouTube publishes no engagement rate. Average views per video is the closest honest
        // proxy and is what the mock already models, so vetting rules behave consistently across
        // real and simulated reads. Engagement is left null rather than invented: a fabricated
        // percentage on a row labelled platform_api is exactly the confusion `source` prevents.
        Long averageViews = (totalViews != null && videoCount != null && videoCount > 0)
                ? totalViews / videoCount
                : null;

        BigDecimal engagement = null;
        if (averageViews != null && subscribers != null && subscribers > 0) {
            // Views-per-subscriber, expressed as a percentage. Named honestly in the docs as a
            // proxy, not as the like/comment engagement Instagram reports.
            engagement = BigDecimal.valueOf(averageViews)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(subscribers), 2, RoundingMode.HALF_UP);
        }

        return new SocialProfileGateway.Profile(
                "@" + normalized,
                platform(),
                // The whole point of this class.
                "platform_api",
                subscribers,
                engagement,
                averageViews,
                // The API exposes no verification badge on this endpoint; null means unknown,
                // which is true, rather than false, which would be a claim.
                null,
                snippet.path("publishedAt").asText(null),
                null,
                snippet.path("title").asText(null),
                null);
    }

    /** Statistics arrive as JSON strings, not numbers, and are absent when a channel hides them. */
    private static Long asLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "YouTubeProfileAdapter[configured=" + isConfigured() + "]";
    }

    /** Unused today; kept so a future caller does not reinvent the normalisation. */
    static String normalizeHandle(String handle) {
        return handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
    }
}
