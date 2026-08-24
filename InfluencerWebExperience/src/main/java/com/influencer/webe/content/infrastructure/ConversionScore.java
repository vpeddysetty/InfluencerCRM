package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.PageGenerationPort.Brief;
import com.influencer.webe.content.application.PageGenerationPort.Section;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The badge number on a variant card (roadmap PR-35).
 *
 * <p><b>Computed here, never taken from the model.</b> Asking an LLM to score its own output
 * produces a number that is confident, stable-looking and meaningless — and one that would differ
 * between the AI and template paths, making the two incomparable on the very screen whose job is
 * comparison. Scoring structurally in one place means variant A from the template generator and
 * variant B from the model are measured by the same ruler.
 *
 * <p><b>What it actually measures: presence of the things that are known to matter on a campaign
 * landing page</b> — a clear offer, a single CTA, proof, a creator voice, and a headline that is
 * not filler. It is a completeness checklist, not a prediction. The UI must describe it as such;
 * the design spec's "conversion score" label is fine as long as nobody reads it as a forecast.
 */
final class ConversionScore {

    private ConversionScore() {
    }

    /** Headlines that score nothing — the checklist's "headline is not generic filler" rule. */
    private static final Set<String> FILLER = Set.of(
            "welcome", "hello", "our products", "shop", "landing page", "untitled", "new page");

    /**
     * Score a variant 0-100.
     *
     * <p>Starts at a floor rather than zero: a page with a hero and a CTA and nothing else is a
     * thin page, not a broken one, and showing it as 20/100 would push users to regenerate
     * something that is merely sparse.
     */
    static int score(Brief brief, List<Section> sections) {
        Set<String> types = sections.stream().map(Section::type).collect(Collectors.toSet());
        int score = 40;

        if (types.contains("productCta")) {
            score += 15;
        }
        // An offer block AND a live coupon block together are worth more than either alone: the
        // description tells the visitor what they get, the coupon gives them the means.
        if (types.contains("couponBlock")) {
            score += 15;
        } else if (brief.hasOffer()) {
            score += 8;
        }
        if (!brief.proofPoints().isEmpty()) {
            score += 10;
        }
        if (brief.hasCreator()) {
            score += 8;
        }
        if (brief.has(brief.audience())) {
            score += 5;
        }
        if (hasStrongHeadline(sections)) {
            score += 7;
        }

        return Math.max(0, Math.min(100, score));
    }

    private static boolean hasStrongHeadline(List<Section> sections) {
        return sections.stream()
                .filter(s -> "hero".equals(s.type()))
                .map(Section::body)
                .filter(b -> b != null && !b.isBlank())
                // Short headlines are usually filler ("Shop"); the floor is deliberately low
                // because a genuinely punchy three-word headline exists and should not be punished.
                .anyMatch(b -> b.trim().length() >= 12 && !FILLER.contains(b.trim().toLowerCase()));
    }
}
