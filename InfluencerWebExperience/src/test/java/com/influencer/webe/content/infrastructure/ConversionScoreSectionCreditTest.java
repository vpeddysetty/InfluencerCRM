package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.PageGenerationPort.Brief;
import com.influencer.webe.content.application.PageGenerationPort.Section;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The badge credits what is on the page, not only what was in the brief (roadmap PR-58).
 *
 * <p><b>Why this needed changing at all.</b> Before PR-58 the model could not emit `proof` or
 * `creator` — they were absent from its tool schema — so scoring the BRIEF for those was the only
 * evidence available. Widening the vocabulary without touching the score would have handed every
 * draft the same points whether it used the material or ignored it: the number would have risen
 * across the board with no copy improving, which is exactly how a heuristic starts lying.
 *
 * <p>The section is worth more than the raw material, because having the reasons and using them are
 * different things. The brief still earns something, because a page that omits a proof section is
 * thinner rather than worthless.
 */
class ConversionScoreSectionCreditTest {

    private Brief briefWithMaterial() {
        return new Brief("launch", "Sell the winter collection", "runners", "20% off",
                "@maya", "warm", "shop", List.of("Woven in Portugal", "Ships in a day"));
    }

    private List<Section> withTypes(String... types) {
        return java.util.Arrays.stream(types)
                .map(t -> new Section(t, t, "Some words that are long enough to read"))
                .toList();
    }

    @Test
    @DisplayName("a page that USES its proof points scores above one that merely had them")
    void sectionBeatsBrief() {
        Brief brief = briefWithMaterial();

        int used = ConversionScore.score(brief, withTypes("hero", "productCta", "proof"));
        int unused = ConversionScore.score(brief, withTypes("hero", "productCta"));

        assertThat(used).isGreaterThan(unused);
    }

    @Test
    @DisplayName("the same holds for the creator section")
    void creatorSectionBeatsBrief() {
        Brief brief = briefWithMaterial();

        int used = ConversionScore.score(brief, withTypes("hero", "productCta", "creator"));
        int unused = ConversionScore.score(brief, withTypes("hero", "productCta"));

        assertThat(used).isGreaterThan(unused);
    }

    @Test
    @DisplayName("a brief with the material still scores something when the page omits it")
    void briefStillCounts() {
        // Thinner, not worthless. A floor of evidence the writer had is fair, and dropping it to
        // zero would make an otherwise good page look broken.
        Brief withMaterial = briefWithMaterial();
        Brief without = new Brief("launch", "Sell the winter collection", "runners", "20% off",
                null, "warm", "shop", List.of());

        int hadIt = ConversionScore.score(withMaterial, withTypes("hero", "productCta"));
        int neverHadIt = ConversionScore.score(without, withTypes("hero", "productCta"));

        assertThat(hadIt).isGreaterThan(neverHadIt);
    }

    @Test
    @DisplayName("widening the vocabulary cannot push a page past 100")
    void stillClamped() {
        int score = ConversionScore.score(briefWithMaterial(),
                withTypes("hero", "productCta", "couponBlock", "proof", "creator", "legal"));

        assertThat(score).isBetween(0, 100);
    }
}
