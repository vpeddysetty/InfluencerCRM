package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.PageGenerationPort.Brief;
import com.influencer.webe.content.application.PageGenerationPort.Result;
import com.influencer.webe.content.application.PageGenerationPort.RewriteResult;
import com.influencer.webe.content.application.PageGenerationPort.Section;
import com.influencer.webe.content.application.PageGenerationPort.Variant;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a campaign brief into landing page drafts (roadmap PR-35).
 *
 * <p><b>Why this sits between the controller and the port.</b> The port's job is to produce drafts;
 * this decides whether what came back is publishable. Those are different questions, and a
 * generator that also judged its own output would have no honest answer — the validation below is
 * what lets the AI and template generators be compared on equal terms.
 *
 * <p><b>Generation never fails the request.</b> Every path returns drafts: the preferred generator,
 * or the template generator when that produces nothing usable. The design spec is explicit that a
 * failed generation must leave the user with an editable page rather than an error, since the whole
 * point of the feature is removing the blank canvas.
 */
@Service
public class CampaignPageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CampaignPageGenerationService.class);

    /** Matches the design spec's "2-3 variants". Three is the most a phone screen compares well. */
    private static final int VARIANT_COUNT = 3;

    /**
     * Regenerating asks for ONE draft, not the full {@link #VARIANT_COUNT}.
     *
     * <p>This deliberately reverses an earlier decision, and the reason it was reversed matters more
     * than the number. Regenerate used to ask for three and discard two, so that a draft colliding
     * with a headline already on screen still left something to offer. That bought retry headroom at
     * three times the output cost of the operation it serves — and output is the expensive half of a
     * generation, billed several times the input rate and impossible to cache. A user pressing
     * "regenerate" wants one replacement card; paying for three to show one is the single largest
     * avoidable cost in the feature.
     *
     * <p>The collision case it protected against is still handled, just not by pre-buying drafts:
     * {@code regenerateVariant} already falls back to the template generator when everything it got
     * back was already seen, and answers 200-with-no-variants when even that repeats itself. A
     * deterministic generator asked twice for one draft returns the same draft either way — asking
     * for three of them never fixed that, it only made it cost more to discover.
     */
    private static final int REGENERATE_VARIANT_COUNT = 1;

    /**
     * Every draft must be able to render a hero and an action.
     *
     * <p>Deliberately short. A longer required set would reject drafts that are merely sparse — a
     * page with no proof section because the brief supplied no proof points is correct output, not
     * a malformed one, and rejecting it would push the user to regenerate until the model invented
     * the proof the system prompt forbids.
     */
    private static final Set<String> REQUIRED_SECTION_TYPES = Set.of("hero", "productCta");

    /**
     * Splits a campaign brief's free-text talking points into individual proof points.
     *
     * <p>On semicolons, newlines, or sentence ends — the three ways people actually separate them
     * in a textarea. Held as a compiled constant because it runs on every enriched generation.
     */
    private static final java.util.regex.Pattern TALKING_POINT_SPLIT =
            java.util.regex.Pattern.compile("[;\\n]|(?<=\\.)\\s+");

    private final PageGeneratorRegistry generators;
    private final ResponseShapeService shape;
    private final BriefEnricher enricher;

    public CampaignPageGenerationService(PageGeneratorRegistry generators,
                                         ResponseShapeService shape,
                                         BriefEnricher enricher) {
        this.generators = generators;
        this.shape = shape;
        this.enricher = enricher;
    }

    /**
     * Generate drafts for a brief.
     *
     * @throws ResponseStatusException 400 when the brief carries no goal — the one field with no
     *                                 sensible default, since it is what the whole page is about
     */
    public JsonNode generate(ObjectNode payload) {
        Brief brief = readBrief(payload);

        PageGenerationPort preferred = generators.active();
        Result result = safely(preferred, brief, VARIANT_COUNT);
        List<Variant> variants = validate(result.variants());

        String generator = result.generator();
        boolean fallback = result.fallback();
        String detail = result.detail();

        if (variants.isEmpty()) {
            PageGenerationPort template = generators.fallback();
            if (!template.key().equals(preferred.key())) {
                // Logged at info: the model being briefly unavailable is an ordinary outcome of a
                // feature with a fallback, not a fault. It is logged at all because a silent
                // permanent fallback is how "the AI feature stopped working" goes unnoticed.
                log.info("Page generation fell back to the template generator: {}",
                        detail == null ? "no usable variants" : detail);
                Result substitute = safely(template, brief, VARIANT_COUNT);
                variants = validate(substitute.variants());
                generator = substitute.generator();
            }
            fallback = true;
            if (detail == null) {
                detail = "The generated drafts could not be used, so a template draft was prepared instead.";
            }
        }

        if (variants.isEmpty()) {
            // Only reachable if the template generator itself returns nothing, which means a code
            // defect rather than an environment problem. A 500 is the honest answer.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No landing page drafts could be produced");
        }

        return render(variants, generator, fallback, detail);
    }

    /**
     * Rewrite one section of a draft the user is editing (PR-35, screen 5).
     *
     * <p><b>Stateless by design.</b> The caller sends the section it currently has rather than a
     * section id, because a draft only becomes a stored thing when the user saves it as a landing
     * template. Persisting half-edited drafts server-side would create a second lifecycle to
     * reconcile with the template's own — the exact parallel-record problem generation avoids.
     *
     * <p>Unlike generation there is <b>no fallback</b>: when the generator cannot reword, the
     * honest answer is to say so and leave the user's text untouched. Substituting a template
     * rewrite would silently replace prose the user was working on with something mechanical.
     */
    public JsonNode rewriteSection(ObjectNode payload) {
        Brief brief = readBrief(payload);
        JsonNode node = payload.get("section");
        if (node == null || !node.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "section is required");
        }
        String type = node.path("type").asText(null);
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "section.type is required");
        }
        // couponBlock renders the creator's live code; there is no authored text to reword, and
        // "rewriting" it could only mean fabricating a discount that the coupon does not carry.
        if ("couponBlock".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The coupon block renders each creator's own code and has no text to rewrite");
        }

        Section section = new Section(type, node.path("title").asText(""), node.path("body").asText(""));
        String instruction = payload.path("instruction").asText("");

        PageGenerationPort generator = generators.active();
        RewriteResult result;
        try {
            result = generator.rewriteSection(brief, section, instruction);
        } catch (RuntimeException e) {
            log.warn("Page generator {} threw during rewrite", generator.key(), e);
            result = RewriteResult.unavailable(generator.key(), "rewrite failed");
        }

        ObjectNode response = shape.objectMapper().createObjectNode();
        response.put("generator", result == null ? generator.key() : result.generator());
        if (result == null || result.isEmpty()) {
            // 200 with rewritten=false, not an error status: the user asked for a suggestion and
            // did not get one. Their text is intact and the page is unchanged, which is not a
            // failed request — it is an answer of "no".
            response.put("rewritten", false);
            response.put("detail", result == null || result.detail() == null
                    ? "No rewrite was produced." : result.detail());
            return response;
        }

        response.put("rewritten", true);
        ObjectNode rewritten = response.putObject("section");
        // The type is taken from the REQUEST, never from the generator's answer. The caller is
        // replacing one block in place; a generator that returned a different type — by bug or by
        // a prompt-injected instruction in the user's own copy — would silently restructure the
        // page. AnthropicPageGenerator pins this too, but the guarantee has to hold for every
        // implementation, so it is enforced at the boundary rather than trusted from the port.
        rewritten.put("type", section.type());
        rewritten.put("title", result.section().title() == null ? "" : result.section().title());
        rewritten.put("body", result.section().body() == null ? "" : result.section().body());
        return response;
    }

    /**
     * Produce one fresh draft, excluding headlines the user has already seen (PR-35, screen 3).
     *
     * <p>Regenerating is only useful if it returns something different. The caller passes the
     * headlines already on screen and any variant repeating one is dropped — without that, a
     * deterministic generator returns byte-identical output and the button appears broken.
     *
     * <p>Asks for the full variant count and keeps the first unseen one, rather than asking for a
     * single draft: a generator whose one draft collides with a seen headline would otherwise have
     * nothing left to offer.
     */
    public JsonNode regenerateVariant(ObjectNode payload) {
        Brief brief = readBrief(payload);

        Set<String> seen = new LinkedHashSet<>();
        JsonNode headlines = payload.get("seenHeadlines");
        if (headlines != null && headlines.isArray()) {
            for (JsonNode headline : headlines) {
                String value = headline.asText(null);
                if (value != null && !value.isBlank()) {
                    seen.add(value.trim().toLowerCase());
                }
            }
        }

        PageGenerationPort preferred = generators.active();
        Result result = safely(preferred, brief, REGENERATE_VARIANT_COUNT);
        List<Variant> fresh = unseen(validate(result.variants()), seen);
        String generator = result.generator();
        boolean fallback = result.fallback();
        String detail = result.detail();

        if (fresh.isEmpty()) {
            PageGenerationPort template = generators.fallback();
            if (!template.key().equals(preferred.key())) {
                Result substitute = safely(template, brief, REGENERATE_VARIANT_COUNT);
                fresh = unseen(validate(substitute.variants()), seen);
                generator = substitute.generator();
            }
            fallback = true;
            if (detail == null) {
                detail = "No new draft could be produced — the generator returned pages you have already seen.";
            }
        }

        if (fresh.isEmpty()) {
            // Distinct from a generation failure, and 200 rather than an error: the generator
            // worked and simply has nothing new to say. A 5xx here would suggest the feature broke.
            ObjectNode response = shape.objectMapper().createObjectNode();
            response.put("generator", generator);
            response.put("fallback", true);
            response.putArray("variants");
            response.put("detail", detail == null ? "No new draft was available." : detail);
            return response;
        }

        // Exactly one: the caller is replacing a single card, not the whole comparison.
        return render(List.of(fresh.get(0)), generator, fallback, detail);
    }

    /** Drop variants whose headline the user has already been shown. */
    private List<Variant> unseen(List<Variant> variants, Set<String> seen) {
        List<Variant> fresh = new ArrayList<>();
        for (Variant variant : variants) {
            if (!seen.contains(variant.headline().trim().toLowerCase())) {
                fresh.add(variant);
            }
        }
        return fresh;
    }

    /**
     * Convert a chosen variant into the {@code blocks} array {@code LandingService.saveTemplate}
     * already accepts.
     *
     * <p>This is the whole reason generation needed no new persistence: a draft becomes an ordinary
     * landing template, editable in the existing builder and rendered by the existing renderer. The
     * alternative — a parallel "generated draft" table with its own lifecycle — would have needed a
     * second editor and a second renderer to reach the same public page.
     */
    public ArrayNode toBlocks(Variant variant) {
        ArrayNode blocks = shape.objectMapper().createArrayNode();
        for (Section section : variant.sections()) {
            ObjectNode block = blocks.addObject();
            block.put("type", section.type());
            switch (section.type()) {
                // couponBlock takes no authored text: it renders the creator's real code at request
                // time. productCta's authored text is its button label, not body copy.
                case "couponBlock" -> { }
                case "productCta" -> block.put("label",
                        section.body() == null || section.body().isBlank() ? variant.ctaText() : section.body());
                default -> block.put("text", section.body() == null ? "" : section.body());
            }
        }
        return blocks;
    }

    // ---- brief --------------------------------------------------------

    private Brief readBrief(ObjectNode payload) {
        // Enrich BEFORE validating: a caller who sent a campaignId but no goal has told us where
        // the goal is, and rejecting them for a field the campaign brief already answers would be
        // pedantic. The goal check below still catches a brief with neither.
        if (brandId(payload) != null) {
            enricher.enrich(brandId(payload), payload);
        }

        String goal = text(payload, "goal");
        if (goal == null || goal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "goal is required");
        }
        return new Brief(
                text(payload, "campaignType"),
                goal,
                text(payload, "audience"),
                text(payload, "offer"),
                text(payload, "creatorHandle"),
                text(payload, "brandTone"),
                text(payload, "ctaPreference"),
                proofPoints(payload),
                text(payload, "brandName"),
                text(payload, "campaignName"),
                text(payload, "creatorName"),
                text(payload, "creatorPlatform"),
                text(payload, "disclosure"));
    }

    /**
     * The tenant the controller verified, stamped onto the payload before this service sees it.
     *
     * <p>Read from the payload rather than passed as an argument so the three entry points share
     * one signature. It is never taken from the caller's JSON — the controller overwrites it with
     * the brand from the verified token, so a forged value cannot survive.
     */
    private java.util.UUID brandId(ObjectNode payload) {
        String value = text(payload, "brandId");
        if (value == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> proofPoints(ObjectNode payload) {
        List<String> points = new ArrayList<>();
        JsonNode node = payload.get("proofPoints");
        if (node != null && node.isArray()) {
            for (JsonNode point : node) {
                String value = point.asText(null);
                if (value != null && !value.isBlank()) {
                    points.add(value.trim());
                }
            }
        }
        // The campaign brief's talking points arrive as one free-text field, not a list. Split so
        // enriched proof reaches the prompt in the same shape the user's own entries do —
        // otherwise a page built from a campaign brief would score as having no proof at all.
        if (points.isEmpty()) {
            String talking = text(payload, "proofPointsText");
            if (talking != null) {
                for (String part : TALKING_POINT_SPLIT.split(talking)) {
                    String trimmed = part.trim();
                    if (!trimmed.isBlank()) {
                        points.add(trimmed);
                    }
                }
            }
        }
        return points;
    }

    private String text(ObjectNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---- validation ---------------------------------------------------

    /**
     * A generator must not throw, but a third-party implementation is still third-party code.
     *
     * <p>This keeps the port's contract from being load-bearing on correctness: a generator that
     * breaks its promise degrades to the fallback exactly like one that returns nothing.
     */
    private Result safely(PageGenerationPort generator, Brief brief, int variantCount) {
        try {
            Result result = generator.generate(brief, variantCount);
            return result == null ? Result.unavailable(generator.key(), "generator returned nothing") : result;
        } catch (RuntimeException e) {
            log.warn("Page generator {} threw instead of reporting unavailability", generator.key(), e);
            return Result.unavailable(generator.key(), "generator failed");
        }
    }

    /** Drop drafts that are structurally unpublishable; keep the rest. */
    private List<Variant> validate(List<Variant> variants) {
        List<Variant> usable = new ArrayList<>();
        Set<String> seenHeadlines = new LinkedHashSet<>();
        for (Variant variant : variants) {
            if (variant == null || variant.headline() == null || variant.headline().isBlank()) {
                continue;
            }
            Set<String> types = new LinkedHashSet<>();
            for (Section section : variant.sections()) {
                types.add(section.type());
            }
            if (!types.containsAll(REQUIRED_SECTION_TYPES)) {
                log.info("Dropping a draft missing a required section; had {}", types);
                continue;
            }
            // Identical headlines mean the comparison screen shows the user the same page twice,
            // which is worse than showing them two options. Keep the first.
            if (!seenHeadlines.add(variant.headline().trim().toLowerCase())) {
                continue;
            }
            usable.add(variant);
        }
        return usable;
    }

    // ---- response -----------------------------------------------------

    private JsonNode render(List<Variant> variants, String generator, boolean fallback, String detail) {
        ObjectNode root = shape.objectMapper().createObjectNode();
        // Surfaced for the same reason as metrics provenance on creators: a template draft
        // presented as an AI draft is worse than one that says plainly what it is.
        root.put("generator", generator);
        root.put("fallback", fallback);
        if (detail != null) {
            root.put("detail", detail);
        }

        ArrayNode array = root.putArray("variants");
        for (Variant variant : variants) {
            ObjectNode node = array.addObject();
            node.put("id", variant.id());
            node.put("score", variant.score());
            node.put("headline", variant.headline());
            node.put("subheadline", variant.subheadline() == null ? "" : variant.subheadline());
            if (variant.offerText() != null) {
                node.put("offerText", variant.offerText());
            }
            node.put("ctaText", variant.ctaText());

            ArrayNode sections = node.putArray("sections");
            for (Section section : variant.sections()) {
                ObjectNode item = sections.addObject();
                item.put("type", section.type());
                item.put("title", section.title() == null ? "" : section.title());
                item.put("body", section.body() == null ? "" : section.body());
            }
            // The builder-ready form travels with the draft so selecting one is a save, not a
            // second round trip that could disagree with what the user previewed.
            node.set("blocks", toBlocks(variant));
        }
        return root;
    }
}
