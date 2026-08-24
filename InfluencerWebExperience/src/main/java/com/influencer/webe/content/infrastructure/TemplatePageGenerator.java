package com.influencer.webe.content.infrastructure;

import com.influencer.webe.content.application.PageGenerationPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, network-free page generation (roadmap PR-35).
 *
 * <p><b>This is the default provider and the failure path at once</b>, which is deliberate. Every
 * other integration in this codebase defaults to a no-op — email writes a log line, billing takes
 * no money — but a no-op is the wrong default *here*: a page builder that returns nothing leaves
 * the user staring at the blank canvas the whole feature exists to remove. So the safe default is a
 * real, publishable page built from the brief by string composition rather than a silent nothing.
 *
 * <p><b>It is genuinely useful, not a stub.</b> The variants differ in section ORDER and emphasis —
 * offer-led, story-led, proof-led — which is the axis that actually changes how a landing page
 * reads. Three orderings of good campaign copy is a weak-but-honest version of the feature;
 * three copies of identical copy would be a fake one.
 *
 * <p>No {@code @ConditionalOnProperty}: this bean must always exist, because the registry falls
 * back to it when the configured provider is unknown or unavailable.
 */
@Component
public class TemplatePageGenerator implements PageGenerationPort {

    /** Used when the brief names no CTA. Imperative and specific beats "Learn more". */
    private static final String DEFAULT_CTA = "Shop now";

    @Override
    public String key() {
        return "template";
    }

    @Override
    public Result generate(Brief brief, int count) {
        List<Variant> variants = new ArrayList<>();
        // Three fixed emphases. Capped at whatever the caller asked for so the contract
        // ("2-3 variants") is honoured by the caller's number, not by this class's opinion.
        List<String> emphases = List.of("offer", "story", "proof");
        for (int i = 0; i < Math.min(count, emphases.size()); i++) {
            variants.add(build(brief, emphases.get(i), i));
        }
        return Result.of(variants, key());
    }

    private Variant build(Brief brief, String emphasis, int index) {
        String cta = brief.has(brief.ctaPreference()) ? brief.ctaPreference() : DEFAULT_CTA;
        String offerText = brief.hasOffer() ? brief.offer() : null;
        String headline = headline(brief, emphasis);
        String subheadline = subheadline(brief);

        List<Section> sections = new ArrayList<>();
        sections.add(new Section("hero", "Hero", headline));

        // The ordering IS the variant. Each emphasis promotes a different block to the position
        // directly under the hero, which is the only slot most visitors read.
        switch (emphasis) {
            case "offer" -> {
                addOffer(sections, brief);
                addCreator(sections, brief);
                addProof(sections, brief);
            }
            case "story" -> {
                addCreator(sections, brief);
                addProof(sections, brief);
                addOffer(sections, brief);
            }
            default -> {
                addProof(sections, brief);
                addOffer(sections, brief);
                addCreator(sections, brief);
            }
        }

        // CTA last in the section list but rendered above the fold by the hero too — the
        // conversion guideline in the design spec is "CTA above the fold", which the renderer
        // satisfies; this is the closing repeat.
        sections.add(new Section("productCta", "Call to action", cta));
        // The campaign's own disclosure when it has one: on a paid-partnership page this is an
        // FTC/ASA requirement, and the generic terms line does not satisfy it.
        sections.add(new Section("legal", "Legal", brief.has(brief.disclosure())
                ? brief.disclosure().trim()
                : "Terms apply. Offer valid while stocks last."));

        return new Variant(
                "variant_" + (char) ('a' + index),
                ConversionScore.score(brief, sections),
                headline,
                subheadline,
                offerText,
                cta,
                sections);
    }

    /**
     * Mechanical rewrites only, and it says so when it cannot help.
     *
     * <p>The template generator writes no prose, so it cannot honour "make this punchier". What it
     * <em>can</em> do is the handful of transformations that are string operations rather than
     * writing — shortening to the first sentence, and re-deriving a section from the brief. Trying
     * to fake the rest would produce a rewrite button that returns subtly-mangled copy, which is
     * worse than one that reports honestly that the model is unavailable.
     */
    @Override
    public RewriteResult rewriteSection(Brief brief, Section section, String instruction) {
        if (section == null) {
            return RewriteResult.unavailable(key(), "no section to rewrite");
        }
        String ask = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);

        // "Shorter" is genuinely mechanical: keep the first sentence. Anything already one
        // sentence is returned unavailable rather than truncated mid-thought.
        if (ask.contains("short") || ask.contains("trim") || ask.contains("concise")) {
            String shortened = firstSentence(section.body());
            if (shortened != null && !shortened.equals(section.body())) {
                return RewriteResult.of(new Section(section.type(), section.title(), shortened), key());
            }
            return RewriteResult.unavailable(key(), "this text is already a single sentence");
        }

        // "Reset" re-derives the section from the brief, which is the one rewrite this generator
        // does as well as any model — the brief is its only source of truth either way.
        if (ask.contains("reset") || ask.contains("start over") || ask.contains("revert")) {
            String rebuilt = fromBrief(brief, section.type());
            if (rebuilt != null) {
                return RewriteResult.of(new Section(section.type(), section.title(), rebuilt), key());
            }
        }

        return RewriteResult.unavailable(key(),
                "template drafts cannot be reworded — connect a model to rewrite sections");
    }

    /** The first sentence, or null when the text is already one (or empty). */
    private String firstSentence(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        int end = -1;
        for (int i = 0; i < trimmed.length() - 1; i++) {
            char c = trimmed.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(trimmed.charAt(i + 1))) {
                end = i;
                break;
            }
        }
        return end < 0 ? null : trimmed.substring(0, end + 1);
    }

    /** Re-derive a section's body from the brief, or null if the brief cannot support it. */
    private String fromBrief(Brief brief, String type) {
        return switch (type) {
            case "hero" -> headline(brief, "offer");
            case "productCta" -> brief.has(brief.ctaPreference()) ? brief.ctaPreference() : DEFAULT_CTA;
            case "richText" -> brief.hasOffer() ? brief.offer()
                    : brief.has(brief.goal()) ? brief.goal() : null;
            default -> null;
        };
    }

    private void addOffer(List<Section> sections, Brief brief) {
        if (brief.hasOffer()) {
            // couponBlock renders the creator's real code at request time. The brief's offer text
            // is the human description around it, which is why both exist rather than one.
            sections.add(new Section("richText", "Offer", brief.offer()));
            sections.add(new Section("couponBlock", "Coupon", ""));
        } else {
            // Edge case from the checklist: missing offer must not leave a hole where the
            // strongest block should be. A benefit restatement is a weaker but valid substitute.
            sections.add(new Section("richText", "Offer",
                    brief.has(brief.goal()) ? brief.goal() : "Discover the collection."));
        }
    }

    private void addCreator(List<Section> sections, Brief brief) {
        // Checklist edge case: no creator handle means NO creator section, rather than a section
        // addressed to nobody.
        if (brief.hasCreator()) {
            // The resolved name reads better than the handle when we have it — "Hand-picked by
            // Sam Okonjo (@northbound)" rather than a bare handle a visitor may not recognise.
            String who = brief.has(brief.creatorName())
                    ? brief.creatorName().trim() + " (" + brief.creatorHandle().trim() + ")"
                    : brief.creatorHandle().trim();
            sections.add(new Section("richText", "Creator", "Hand-picked by " + who + "."));
        }
    }

    private void addProof(List<Section> sections, Brief brief) {
        if (!brief.proofPoints().isEmpty()) {
            sections.add(new Section("richText", "Proof",
                    String.join(" · ", brief.proofPoints())));
        }
    }

    private String headline(Brief brief, String emphasis) {
        String goal = brief.has(brief.goal()) ? brief.goal().trim() : "Something new";
        return switch (emphasis) {
            case "offer" -> brief.hasOffer() ? brief.offer().trim() + " — " + goal : goal;
            case "story" -> brief.hasCreator() ? goal + ", with " + brief.creatorHandle().trim() : goal;
            default -> goal;
        };
    }

    private String subheadline(Brief brief) {
        if (brief.has(brief.audience())) {
            return "Made for " + brief.audience().trim() + ".";
        }
        if (brief.has(brief.brandName())) {
            return "From " + brief.brandName().trim() + ".";
        }
        if (brief.has(brief.brandTone())) {
            return brief.brandTone().trim().substring(0, 1).toUpperCase(Locale.ROOT)
                    + brief.brandTone().trim().substring(1) + " by design.";
        }
        return "";
    }
}
