package com.influencer.webe.creator.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.creator.application.SocialProfileGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Simulated platform reads, used until the real app registrations are approved.
 *
 * <p>The roadmap calls app registration the longest lead time in the plan and entirely
 * non-code: Meta review is 2-4 weeks and resets if a reviewer requests changes, TikTok is
 * 5-10 business days. This adapter lets Phase C and everything downstream of it be built and
 * tested meanwhile, and is replaced by adding a real adapter and flipping one property.
 *
 * <p><b>It reports {@code source = "mock"}.</b> Never {@code platform_api}. Every number below
 * is invented, the creator row records that it was invented, and a brand can therefore always
 * distinguish a measured metric from a simulated one. A mock that lied about provenance would
 * be worse than no mock at all — the figures would look authoritative.
 *
 * <p><b>Deterministic.</b> The same handle always produces the same figures, so tests can
 * assert exact values and a demo does not shift between page loads.
 */
@Component
@ConditionalOnProperty(name = "web-experience.creators.social-provider", havingValue = "mock", matchIfMissing = true)
public class MockSocialProfileGateway implements SocialProfileGateway {

    /** A handle containing this resolves to nothing, so the manual fallback stays testable. */
    private static final String NOT_FOUND_MARKER = "unknown";

    private final ObjectMapper objectMapper;

    public MockSocialProfileGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Profile fetch(String platform, String handle) {
        if (handle == null || handle.isBlank()) {
            return null;
        }
        String normalized = handle.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
        if (normalized.contains(NOT_FOUND_MARKER)) {
            return null;
        }

        long seed = stableSeed(normalized);

        // 1k-500k, the tiers brands actually shop in.
        long followers = 1_000 + Math.floorMod(seed, 499_000L);

        // Engagement falls as audience grows, matching the real inverse relationship — so a
        // vetting rule written against mock data behaves the same way against a live read.
        double base = followers < 10_000 ? 6.5 : followers < 100_000 ? 3.8 : 1.9;
        double jitter = (Math.floorMod(seed >> 8, 200) - 100) / 100.0;
        BigDecimal engagement = BigDecimal.valueOf(Math.max(0.1, base + jitter))
                .setScale(2, RoundingMode.HALF_UP);

        long averageViews = Math.round(followers * (0.18 + (Math.floorMod(seed >> 16, 25) / 100.0)));
        boolean verified = followers > 100_000 && Math.floorMod(seed >> 24, 3) == 0;
        String lastActive = Instant.now().minus(Math.floorMod(seed >> 32, 60), ChronoUnit.DAYS).toString();

        ObjectNode demographics = objectMapper.createObjectNode();
        ObjectNode age = demographics.putObject("age");
        age.put("13-17", 0.05); age.put("18-24", 0.38); age.put("25-34", 0.34);
        age.put("35-44", 0.15); age.put("45+", 0.08);
        ObjectNode gender = demographics.putObject("gender");
        gender.put("female", 0.62); gender.put("male", 0.36); gender.put("other", 0.02);
        ObjectNode geo = demographics.putObject("geo");
        geo.put("US", 0.44); geo.put("GB", 0.12); geo.put("CA", 0.09); geo.put("other", 0.35);

        return new Profile(
                normalized,
                platform == null || platform.isBlank() ? "instagram" : platform,
                "mock",
                followers,
                engagement,
                averageViews,
                verified,
                lastActive,
                demographics,
                displayNameFor(normalized),
                captionsFor(normalized)
        );
    }

    /**
     * FNV-1a rather than {@code String.hashCode}: the latter is not guaranteed stable across
     * JVM versions, and test fixtures asserting exact values would shift under an upgrade.
     */
    private long stableSeed(String handle) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : handle.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash == Long.MIN_VALUE ? 0 : Math.abs(hash);
    }

    private String displayNameFor(String handle) {
        String base = handle.replaceAll("[._-]+", " ").trim();
        if (base.isEmpty()) {
            return handle;
        }
        StringBuilder sb = new StringBuilder();
        for (String word : base.split("\\s+")) {
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Captions for the classifier to read.
     *
     * <p>Handles carrying a risky marker get captions that should raise a brand-safety flag, so
     * the flagging branch is exercised rather than every fixture coming back clean.
     */
    private String captionsFor(String handle) {
        for (String marker : new String[]{"casino", "bet", "vape", "adult"}) {
            if (handle.contains(marker)) {
                return "Big win at the tables tonight. 18+ only, gamble responsibly. "
                        + "Use my code for a deposit bonus. Late night session with drinks.";
            }
        }
        if (handle.contains("fit") || handle.contains("gym")) {
            return "Morning workout done. New protein routine, 12-week progress update. "
                    + "Gym haul and the trainers I actually recommend.";
        }
        if (handle.contains("beauty") || handle.contains("glow") || handle.contains("skin")) {
            return "My evening skincare routine, the serum everyone keeps asking about. "
                    + "Testing this new foundation for a week. Honest review, not sponsored.";
        }
        return "Weekend recap and a few things I have been loving lately. "
                + "New video up now — thanks for all the support on the last one.";
    }
}
