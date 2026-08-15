package com.influencer.webe.creator.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.creator.application.SocialPlatformAdapter;
import com.influencer.webe.creator.application.SocialProfileGateway;
import com.influencer.webe.shared.infrastructure.OutboundHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Real Instagram Graph API reads (roadmap M6.4).
 *
 * <p><b>Reads OTHER people's accounts, which is the unusual part.</b> The YouTube adapter takes a
 * handle and asks a public endpoint about it. Instagram has no such endpoint: the Graph API only
 * answers about accounts that authorised the app. What it does offer is
 * {@code business_discovery} — our own connected Business account asking about another public
 * Business account by username. That is the shape this product needs, because a brand types in a
 * creator's handle rather than the creator connecting their own account.
 *
 * <p>So every lookup is one call against OUR account id, carrying the target handle as a parameter.
 * The consequences are worth stating because they are not obvious:
 *
 * <ul>
 *   <li><b>Only public Business and Creator accounts resolve.</b> A personal account returns an
 *       error, not a profile — indistinguishable from a typo, and both correctly become null.</li>
 *   <li><b>No audience demographics.</b> {@code business_discovery} exposes followers, media count
 *       and recent media, but the {@code insights} edge works only on the connected account itself.
 *       Age bands and country splits for a creator we do not own are not obtainable this way, and
 *       this adapter does not pretend otherwise — see {@link #fetch}.</li>
 *   <li><b>Engagement is computed, not reported.</b> Meta publishes no engagement rate. Likes and
 *       comments on recent media divided by followers is the standard derivation and is what the
 *       mock already models, so vetting rules behave consistently across real and simulated reads.</li>
 * </ul>
 *
 * <p><b>Reports {@code platform_api}</b>, and is the second adapter entitled to — after
 * {@link YouTubeProfileAdapter}. Everything else is hash-derived and says {@code mock}. A brand
 * reads that field to tell measured from invented, so an adapter that lied here would be worse
 * than no adapter at all.
 *
 * <p><b>Unconfigured until a token and an account id are both set.</b> Meta app review gates this:
 * {@code instagram_basic} reaches business_discovery, and until the app is approved for it the
 * token will not carry the scope. {@link #isConfigured()} returning false routes lookups to the
 * simulation rather than turning every Instagram creator's metrics into nulls — see
 * {@code MockedPlatformAdapters} for why that distinction matters.
 */
@Component
public class InstagramProfileAdapter implements SocialPlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(InstagramProfileAdapter.class);

    /**
     * Pinned rather than tracking "latest". Meta deprecates a version roughly every two years and
     * removes fields at the boundary; an unpinned URL turns that into a surprise outage. Bumping is
     * a deliberate edit with the changelog open.
     */
    private static final String API = "https://graph.facebook.com/v20.0";

    /**
     * How many recent posts to average engagement over. Meta's own cap for this edge is 25 and the
     * common industry convention is 12 — recent enough to reflect current performance, wide enough
     * that one viral post does not define the number.
     */
    private static final int MEDIA_SAMPLE = 12;

    private final OutboundHttpClient http;
    private final ObjectMapper objectMapper;
    private final String accessToken;
    private final String businessAccountId;

    public InstagramProfileAdapter(
            OutboundHttpClient http,
            ObjectMapper objectMapper,
            @Value("${web-experience.creators.instagram-access-token:}") String accessToken,
            @Value("${web-experience.creators.instagram-business-account-id:}") String businessAccountId) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.businessAccountId = businessAccountId == null ? "" : businessAccountId.trim();
    }

    @Override
    public String platform() {
        return "instagram";
    }

    /**
     * Both halves are required, and neither implies the other.
     *
     * <p>The token authorises the call; the account id is whose connection it travels through. A
     * deployment with only one is misconfigured rather than partially working, and saying so here
     * routes lookups to the simulation instead of burning a call to discover Meta rejects it.
     *
     * <p>{@code replace-me} is treated as absent because that is the placeholder convention used
     * across this codebase's secrets — see {@code OAuthFlowService.requireConfigured}. A literal
     * placeholder reaching Meta produces an error naming the app rather than the setting, which is
     * the slower diagnosis.
     */
    @Override
    public boolean isConfigured() {
        return isSet(accessToken) && isSet(businessAccountId);
    }

    private static boolean isSet(String value) {
        return !value.isEmpty() && !"replace-me".equalsIgnoreCase(value);
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

        // One call. business_discovery nests the target's fields inside our own account's node, so
        // the profile and the recent media that engagement is derived from arrive together.
        String fields = "business_discovery.username(" + encode(normalized) + "){"
                + "username,name,followers_count,media_count,profile_picture_url,"
                + "media.limit(" + MEDIA_SAMPLE + "){like_count,comments_count,caption,timestamp}"
                + "}";

        Optional<JsonNode> response = http.getJson(
                API + "/" + encode(businessAccountId)
                        + "?fields=" + encode(fields)
                        + "&access_token=" + encode(accessToken),
                Map.of());

        JsonNode discovery = response.map(node -> node.path("business_discovery")).orElse(null);
        if (discovery == null || discovery.isMissingNode() || discovery.isNull()) {
            // Ordinary and expected: a typo, a private account, or a personal account that
            // business_discovery structurally cannot see. Null, never a zeroed profile — writing 0
            // would make "we could not check" indistinguishable from "they have no audience", and
            // would silently pass every vetting rule written as `followers < 5000`.
            return null;
        }

        Long followers = asLong(discovery, "followers_count");
        Long mediaCount = asLong(discovery, "media_count");
        JsonNode media = discovery.path("media").path("data");

        BigDecimal engagement = engagementRate(media, followers);
        Long averageInteractions = averageInteractions(media);

        return new SocialProfileGateway.Profile(
                "@" + normalized,
                platform(),
                // The whole point of this class.
                "platform_api",
                followers,
                engagement,
                // Instagram publishes no view count on this edge. Average interactions per post is
                // the honest analogue of YouTube's average views, and is named as such rather than
                // presented as reach.
                averageInteractions,
                // business_discovery exposes no verification flag. Null means unknown, which is
                // true; false would be a claim we cannot support.
                null,
                mostRecentTimestamp(media),
                // Deliberately null. Audience demographics need the `insights` edge, which answers
                // only for the connected account itself — not for a creator discovered by handle.
                // Fabricating a distribution here would put invented data on a row labelled
                // platform_api, which is exactly the confusion `source` exists to prevent.
                null,
                discovery.path("name").asText(null),
                recentCaptions(media));
    }

    /**
     * Likes plus comments on recent posts, as a percentage of followers.
     *
     * <p>Meta reports no engagement rate, so it is derived — the same convention the simulation
     * models, so a vetting rule threshold means the same thing whether the number was measured or
     * invented. Averaged per post rather than summed, or the result would scale with how many posts
     * happened to be sampled.
     *
     * <p>Null rather than zero when there is nothing to divide: a creator with no recent posts has
     * unknown engagement, not none.
     */
    private BigDecimal engagementRate(JsonNode media, Long followers) {
        if (followers == null || followers <= 0 || !media.isArray() || media.isEmpty()) {
            return null;
        }
        long interactions = 0;
        int counted = 0;
        for (JsonNode post : media) {
            Long likes = asLong(post, "like_count");
            Long comments = asLong(post, "comments_count");
            if (likes == null && comments == null) {
                // A post with neither field is one Meta withheld counts for — hidden like counts
                // are a user setting. Skipped rather than counted as zero, which would drag the
                // average down and understate a creator we would otherwise recommend.
                continue;
            }
            interactions += (likes == null ? 0 : likes) + (comments == null ? 0 : comments);
            counted++;
        }
        if (counted == 0) {
            return null;
        }
        return BigDecimal.valueOf(interactions)
                .divide(BigDecimal.valueOf(counted), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(followers), 2, RoundingMode.HALF_UP);
    }

    /** Mean likes+comments per sampled post, for the column the UI labels "average interactions". */
    private Long averageInteractions(JsonNode media) {
        if (!media.isArray() || media.isEmpty()) {
            return null;
        }
        long total = 0;
        int counted = 0;
        for (JsonNode post : media) {
            Long likes = asLong(post, "like_count");
            Long comments = asLong(post, "comments_count");
            if (likes == null && comments == null) {
                continue;
            }
            total += (likes == null ? 0 : likes) + (comments == null ? 0 : comments);
            counted++;
        }
        return counted == 0 ? null : total / counted;
    }

    /**
     * The newest post's timestamp, as the creator's last-active signal (roadmap C3).
     *
     * <p>Media arrives newest-first, but that is Meta's ordering rather than a documented guarantee,
     * so the maximum is taken rather than the first element. An ordering change would otherwise turn
     * "last active" into "first ever posted" — a silent, plausible, wrong answer of exactly the kind
     * health monitoring is meant to catch.
     */
    private String mostRecentTimestamp(JsonNode media) {
        if (!media.isArray() || media.isEmpty()) {
            return null;
        }
        String newest = null;
        for (JsonNode post : media) {
            String timestamp = post.path("timestamp").asText(null);
            if (timestamp == null || timestamp.isBlank()) {
                continue;
            }
            // ISO-8601 with a fixed offset sorts correctly as text, which avoids parsing a format
            // Meta has changed before.
            if (newest == null || timestamp.compareTo(newest) > 0) {
                newest = timestamp;
            }
        }
        return newest;
    }

    /**
     * Recent captions, joined, for the agent service to classify niche and themes from.
     *
     * <p>Facts only here — this adapter does not interpret them. Keeping classification in the
     * agent service is the Phase C rule: a model asked for a follower count returns a confident,
     * plausible, wrong number, so the two must not share a code path.
     */
    private String recentCaptions(JsonNode media) {
        if (!media.isArray() || media.isEmpty()) {
            return null;
        }
        StringBuilder captions = new StringBuilder();
        for (JsonNode post : media) {
            String caption = post.path("caption").asText(null);
            if (caption == null || caption.isBlank()) {
                continue;
            }
            if (captions.length() > 0) {
                captions.append("\n\n");
            }
            captions.append(caption.trim());
        }
        return captions.length() == 0 ? null : captions.toString();
    }

    /**
     * Counts arrive as JSON numbers here, unlike YouTube's strings — but tolerate both, because a
     * field Meta returns as a number today it has returned as a string before.
     */
    private static Long asLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "InstagramProfileAdapter[configured=" + isConfigured() + "]";
    }
}
