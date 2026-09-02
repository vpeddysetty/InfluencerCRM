package com.influencer.webe.content.application;

import java.util.List;

/**
 * Generates landing-page drafts from a campaign brief (roadmap PR-35).
 *
 * <p><b>A port, for the same reason as {@code EmailPort} and {@code AssetStoragePort}:</b> the
 * generator is an operational decision that changes. There was no LLM capability anywhere in this
 * codebase before this — no SDK, no API key, no egress to a model provider — so the first thing
 * built has to be the seam, not a hardcoded vendor call. The fallback implementation is a pure
 * template with no network at all, which is what makes this feature runnable in a local dev
 * environment and in CI without credentials.
 *
 * <p><b>Never throws.</b> Implementations that cannot produce a draft return
 * {@link Result#unavailable}, and the service substitutes the template generator. The design spec
 * is explicit that a failed generation must never dead-end the user: they keep their brief and get
 * an editable page. An exception escaping to the controller would turn "the model was busy" into a
 * 500 on a flow whose whole promise is that it always yields something to edit.
 *
 * <p><b>Output is structurally validated, not trusted.</b> {@link CampaignPageGenerationService}
 * re-checks every returned variant against the required section set before it is persisted or
 * shown, because a model that returns prose where a schema was requested is a normal outcome, not
 * an exceptional one.
 */
public interface PageGenerationPort {

    /**
     * The user's campaign brief — the structured input the design spec collects on screens 1 and 2.
     *
     * <p>Only {@code goal} is mandatory. Every other field is genuinely optional and the generators
     * degrade rather than reject: the edge-case checklist requires that a missing creator handle
     * omits the creator section and a missing offer falls back to a generic CTA, which is only
     * expressible if the model permits absence instead of demanding placeholders.
     */
    record Brief(
            String campaignType,
            String goal,
            String audience,
            String offer,
            String creatorHandle,
            String brandTone,
            String ctaPreference,
            List<String> proofPoints,
            // ---- context resolved from real records (BriefEnricher) ----
            // Separated from the fields above because these are not things the user typed: they
            // are the brand, campaign and creator the page actually belongs to. They shape the
            // copy without being copy — a page that recites its own campaign name reads like an
            // internal memo, but a model that does not know whose page it is writes copy that
            // could belong to anyone.
            String brandName,
            String campaignName,
            String creatorName,
            String creatorPlatform,
            String disclosure) {

        /** The eight-field form, for callers with no resolved records. Kept so tests stay legible. */
        public Brief(String campaignType, String goal, String audience, String offer,
                     String creatorHandle, String brandTone, String ctaPreference,
                     List<String> proofPoints) {
            this(campaignType, goal, audience, offer, creatorHandle, brandTone, ctaPreference,
                    proofPoints, null, null, null, null, null);
        }

        /** Normalizes null collections so generators never null-check the list. */
        public Brief {
            proofPoints = proofPoints == null ? List.of() : List.copyOf(proofPoints);
        }

        public boolean has(String value) {
            return value != null && !value.isBlank();
        }

        public boolean hasCreator() {
            return has(creatorHandle);
        }

        public boolean hasOffer() {
            return has(offer);
        }
    }

    /**
     * One section of a generated page.
     *
     * <p>{@code type} deliberately reuses the vocabulary the renderer already understands
     * ({@code hero}, {@code richText}, {@code couponBlock}, {@code productCta}, {@code legal}) so a
     * generated draft can be written straight into the existing {@code blocks} column and rendered
     * by {@code LandingService.renderBlock} with no new render path. Inventing a parallel section
     * taxonomy would have required a second renderer and a migration.
     */
    /**
     * One section of a generated page.
     *
     * <p>{@code type} reuses the renderer's block vocabulary so a draft writes straight into
     * {@code landing_templates.blocks}. {@code mediaUrl} is set only on the media types, and is a
     * PLACEHOLDER the brand replaces from its asset library — the model is never asked to invent an
     * image URL, because a plausible-looking one that 404s is worse on a public page than an
     * obvious empty frame.
     */
    record Section(String type, String title, String body, String mediaUrl, String variant) {

        /** The common case: a text section with no media and the type's default arrangement. */
        public Section(String type, String title, String body) {
            this(type, title, body, null, null);
        }

        /** Kept for callers that set media but no variant. */
        public Section(String type, String title, String body, String mediaUrl) {
            this(type, title, body, mediaUrl, null);
        }

        public boolean isMedia() {
            return "image".equals(type) || "video".equals(type);
        }

        /**
         * Whether the model chose a designed arrangement for this section (roadmap PR-58).
         *
         * <p>{@code variant} names one of the layouts the stylesheet already implements — it is a
         * choice among finished designs, never a layout instruction. The model still cannot express
         * a colour, font, size or position, because those fields do not exist anywhere in this
         * contract. That is the curated-editor line, and widening the vocabulary must not cross it.
         *
         * <p>Absent is the normal case and means "use the type's default", exactly as a hand-added
         * section does in the editor.
         */
        public boolean hasVariant() {
            return variant != null && !variant.isBlank();
        }
    }

    /**
     * One generated page option.
     *
     * @param score a 0-100 confidence figure shown as the conversion badge. It is a heuristic over
     *              structure — offer present, CTA present, proof points present — and NOT a
     *              prediction of conversion rate. Named {@code score} rather than
     *              {@code conversionRate} so nobody reads it as measured performance.
     */
    record Variant(
            String id,
            int score,
            String headline,
            String subheadline,
            String offerText,
            String ctaText,
            List<Section> sections) {

        public Variant {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    /**
     * The outcome of a generation attempt.
     *
     * @param generator which implementation produced these — {@code "template"}, {@code "anthropic"}.
     *                  Surfaced to the UI for the same reason {@code metrics_source} is surfaced on
     *                  creator metrics: a fallback draft presented as an AI draft is worse than one
     *                  that says plainly what it is.
     * @param fallback  true when this is the safe template rather than a model result, so the UI can
     *                  show the FallbackTemplateBanner the design calls for
     * @param detail    why the preferred generator did not run, when {@code fallback} is true
     */
    record Result(List<Variant> variants, String generator, boolean fallback, String detail) {

        public Result {
            variants = variants == null ? List.of() : List.copyOf(variants);
        }

        public static Result of(List<Variant> variants, String generator) {
            return new Result(variants, generator, false, null);
        }

        /** No draft was produced. The caller substitutes the template generator. */
        public static Result unavailable(String generator, String reason) {
            return new Result(List.of(), generator, true, reason);
        }

        public boolean isEmpty() {
            return variants.isEmpty();
        }
    }

    /**
     * Produce {@code count} distinct drafts for the brief.
     *
     * <p>Must not throw — see the class comment. {@code count} is a request, not a guarantee; the
     * service accepts fewer and only falls back when zero valid variants survive validation.
     */
    Result generate(Brief brief, int count);

    /**
     * Rewrite one section of an existing draft (roadmap PR-35, screen 5).
     *
     * <p><b>Why one section and not the page.</b> The design spec's editing model is section-level
     * precisely so refining the offer does not silently reword a headline the user had settled on.
     * Regenerating the whole page to change one block would discard edits the user made by hand.
     *
     * <p>{@code instruction} is the user's own words ("make it shorter", "lead with the discount").
     * It is free text by design — an enum of rewrite actions would have to guess the vocabulary,
     * and the tone controls in the UI already map onto this.
     *
     * <p><b>Default is honest refusal.</b> A generator with no rewrite capability returns empty
     * rather than echoing the section back unchanged: a rewrite button that silently does nothing
     * is worse than one that says it is unavailable. {@code TemplatePageGenerator} overrides this
     * only for the mechanical instructions it can actually honour.
     */
    default RewriteResult rewriteSection(Brief brief, Section section, String instruction) {
        return RewriteResult.unavailable(key(), "this generator cannot rewrite sections");
    }

    /**
     * The outcome of a rewrite.
     *
     * @param section the rewritten section, or null when none was produced
     */
    record RewriteResult(Section section, String generator, String detail) {

        public static RewriteResult of(Section section, String generator) {
            return new RewriteResult(section, generator, null);
        }

        public static RewriteResult unavailable(String generator, String reason) {
            return new RewriteResult(null, generator, reason);
        }

        public boolean isEmpty() {
            return section == null;
        }
    }

    /** Stable key for the provider registry, matching the {@code BillingProvider} convention. */
    String key();
}
