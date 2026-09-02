package com.influencer.webe.content.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.PageGenerationPort;
import com.influencer.webe.shared.infrastructure.OutboundHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Page generation backed by the Anthropic Messages API (roadmap PR-35).
 *
 * <p><b>Raw HTTP rather than the Anthropic Java SDK.</b> This is one POST to one endpoint from a
 * service that already owns a hardened egress client — {@code OutboundHttpClient} carries the
 * timeout policy, the never-throw contract and the "log the URL, never the body" rule that a new
 * dependency would arrive without. Adding an SDK would also pull a second HTTP stack into a module
 * that deliberately has exactly one.
 *
 * <p><b>Structured output comes from a forced tool call, not from asking for JSON.</b> A model told
 * to "reply with JSON" returns prose wrapped around JSON often enough that the parser becomes the
 * feature. One tool plus {@code tool_choice} naming it means the only shape the model can answer in
 * is the schema, and the arguments arrive already parsed.
 *
 * <p><b>Absent unless configured.</b> {@code @ConditionalOnProperty} keeps the bean out of the
 * context entirely unless the provider is selected, so a deployment with no API key does not fail
 * at call time — the bean never exists and the registry resolves to the template generator.
 */
@Component
@ConditionalOnProperty(name = "web-experience.landing.generation.provider", havingValue = "anthropic")
public class AnthropicPageGenerator implements PageGenerationPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicPageGenerator.class);

    /** The tool the model is forced to call. Its schema is the output contract. */
    private static final String TOOL_NAME = "emit_page_variants";

    /** The same trick for a single-section rewrite. */
    private static final String REWRITE_TOOL_NAME = "emit_rewritten_section";

    /**
     * The designed arrangements the stylesheet actually implements (roadmap PR-58).
     *
     * <p>Mirrors the 13 variants in {@code packages/ui/src/sectionTypes.js}, which is the single
     * source of truth for what a section IS. Duplicated here rather than imported because that file
     * is JavaScript and this is the server — the same duplication {@code LandingService.renderSection}
     * already carries, and the same rule applies: when they disagree, a brand fills in a field that
     * never appears on their page.
     *
     * <p>Used to DROP an unrecognised value rather than pass it through. A variant the stylesheet
     * does not implement renders as an unstyled section, which looks broken in a way the type's
     * default never does — and the model inventing "hero-large" is an ordinary outcome, not an
     * exceptional one.
     */
    private static final java.util.Set<String> VALID_VARIANTS = java.util.Set.of(
            "centred", "left", "split",              // hero, offer
            "grid", "stacked-list",                  // proof
            "portrait-left", "quote-first",          // creator
            "one-column", "two-column",              // text
            "contained", "full-bleed",               // media
            "stacked", "inline");                    // signup


    private final OutboundHttpClient http;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final Duration timeout;

    public AnthropicPageGenerator(
            OutboundHttpClient http,
            ObjectMapper mapper,
            @Value("${web-experience.landing.generation.api-key:}") String apiKey,
            // Held in config rather than hardcoded so changing model is a config redeploy, not a
            // code change — the same reasoning as the billing and email provider keys.
            @Value("${web-experience.landing.generation.model:claude-opus-5}") String model,
            @Value("${web-experience.landing.generation.base-url:https://api.anthropic.com}") String baseUrl,
            // Generating three drafts routinely exceeds the 10s shared outbound default, which is
            // tuned for social-profile lookups. Raising that global value instead would also let a
            // hung social API hold a request thread twelve times as long.
            @Value("${web-experience.landing.generation.timeout-ms:120000}") int timeoutMs) {
        this.http = http;
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl.trim();
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public String key() {
        return "anthropic";
    }

    @Override
    public Result generate(Brief brief, int count) {
        if (apiKey.isBlank()) {
            // Selected as the provider but never given a key. Reported rather than called, because
            // "misconfigured" and "the model was unreachable" need different fixes and the caller
            // substitutes the template generator either way.
            return Result.unavailable(key(), "no API key configured");
        }

        OutboundHttpClient.Response response = http.postJson(
                baseUrl + "/v1/messages",
                requestBody(brief, count),
                Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01"),
                timeout);

        if (!response.ok()) {
            return Result.unavailable(key(), "generation request failed with status " + response.status());
        }

        List<Variant> variants = readVariants(response.body(), brief);
        if (variants.isEmpty()) {
            return Result.unavailable(key(), "no usable variants in the response");
        }
        return Result.of(variants, key());
    }

    /**
     * Rewrite one section, with the brief still in scope.
     *
     * <p>The brief travels with the request so a rewrite cannot drift away from the campaign — a
     * model asked only "make this punchier", with no offer or audience in view, will happily
     * invent a livelier claim that the brief never supported.
     */
    @Override
    public RewriteResult rewriteSection(Brief brief, Section section, String instruction) {
        if (section == null) {
            return RewriteResult.unavailable(key(), "no section to rewrite");
        }
        if (apiKey.isBlank()) {
            return RewriteResult.unavailable(key(), "no API key configured");
        }

        OutboundHttpClient.Response response = http.postJson(
                baseUrl + "/v1/messages",
                rewriteBody(brief, section, instruction),
                Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01"),
                timeout);

        if (!response.ok()) {
            return RewriteResult.unavailable(key(), "rewrite request failed with status " + response.status());
        }

        JsonNode input = toolInput(response.body(), REWRITE_TOOL_NAME);
        String body = input == null ? null : text(input, "body");
        if (body == null || body.isBlank()) {
            return RewriteResult.unavailable(key(), "no usable text in the response");
        }
        // Type is never taken from the response: the caller is replacing one section in place, and
        // a model that changed hero to productCta would silently restructure the page.
        return RewriteResult.of(new Section(section.type(), section.title(), body), key());
    }

    private ObjectNode rewriteBody(Brief brief, Section section, String instruction) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 2000);
        body.put("system", systemPrompt());

        StringBuilder prompt = new StringBuilder();
        prompt.append("Rewrite one section of an existing landing page.\n\n");
        prompt.append("Campaign brief:\n");
        appendIf(prompt, "Goal", brief.goal());
        appendIf(prompt, "Audience", brief.audience());
        appendIf(prompt, "Offer", brief.offer());
        appendIf(prompt, "Creator", brief.creatorHandle());
        appendIf(prompt, "Brand tone", brief.brandTone());
        if (!brief.proofPoints().isEmpty()) {
            prompt.append("Proof points: ").append(String.join("; ", brief.proofPoints())).append("\n");
        }
        prompt.append("\nSection type: ").append(section.type());
        prompt.append("\nCurrent text: ").append(section.body() == null ? "" : section.body());
        prompt.append("\n\nWhat to change: ")
              .append(instruction == null || instruction.isBlank()
                      ? "Improve it for clarity and conversion, keeping the same meaning."
                      : instruction.trim());
        prompt.append("\n\nRewrite only this section. Keep it the same kind of content, and add ")
              .append("no claim the brief does not support.");

        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt.toString());

        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", REWRITE_TOOL_NAME);
        tool.put("description", "Return the rewritten section text.");
        ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("body");
        describe(schema.putObject("properties"), "body",
                "The rewritten visitor-facing text for this section.");
        body.putArray("tools").add(tool);

        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", REWRITE_TOOL_NAME);
        return body;
    }

    // ---- request ------------------------------------------------------

    private ObjectNode requestBody(Brief brief, int count) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 8000);
        body.put("system", systemPrompt());

        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", userPrompt(brief, count));

        body.putArray("tools").add(toolSchema());
        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", TOOL_NAME);
        return body;
    }

    /**
     * The standing instructions.
     *
     * <p>The no-invented-claims paragraph is the load-bearing one. These pages are published to a
     * public URL under the brand's name, so a fabricated statistic or testimonial is the brand's
     * legal exposure rather than a quality complaint — and a model given a sparse brief will fill
     * the gaps plausibly unless told not to.
     */
    private String systemPrompt() {
        return """
                You write landing pages for influencer marketing campaigns. Each page is read on a \
                phone by someone who arrived from a creator's post and has not heard of the brand.

                State the offer plainly and give the page one clear action. Use the brand's own \
                words where the brief supplies them.

                Write only what the brief supports. Do not invent statistics, testimonials, \
                certifications, endorsements, or product claims. Where the brief gives you nothing \
                for a section, write the section without that material rather than inventing it.

                The drafts must differ in structure and angle, not only in wording. Drafts whose \
                sections say the same things in different phrasing give the reader nothing to \
                choose between.""";
    }

    private String userPrompt(Brief brief, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write ").append(count).append(" distinct landing page drafts for this campaign.\n\n");
        appendIf(sb, "Campaign type", brief.campaignType());
        appendIf(sb, "Goal", brief.goal());
        appendIf(sb, "Audience", brief.audience());
        appendIf(sb, "Offer", brief.offer());
        appendIf(sb, "Creator", brief.creatorHandle());
        appendIf(sb, "Brand tone", brief.brandTone());
        appendIf(sb, "Preferred call to action", brief.ctaPreference());
        if (!brief.proofPoints().isEmpty()) {
            sb.append("Proof points: ").append(String.join("; ", brief.proofPoints())).append('\n');
        }

        // The absences are stated rather than left implicit. An unmentioned missing creator or
        // offer is exactly the gap a model fills with a plausible invention, which is the failure
        // the system prompt forbids — saying "there is none" is more reliable than saying "do not
        // invent one" alone.
        if (!brief.hasCreator()) {
            sb.append("\nNo creator is named: omit the creator section rather than inventing one.");
        }
        if (!brief.hasOffer()) {
            sb.append("\nNo offer is given: build the call to action around the goal, and do not invent a discount.");
        }
        if (brief.proofPoints().isEmpty()) {
            sb.append("\nNo proof points are given: do not supply any.");
        }
        if (brief.has(brief.creatorPlatform())) {
            // Where the visitor came from changes what they already know. Someone arriving from a
            // TikTok video has seen the product in motion; someone from an email list has not.
            sb.append("\n\nVisitors arrive from ").append(brief.creatorPlatform().trim())
              .append(". Write the opening for someone who has just come from that platform.");
        }
        if (brief.has(brief.disclosure())) {
            // Carried verbatim into the legal section. This is an FTC/ASA requirement on a paid
            // partnership page, so it is quoted rather than paraphrased — a model rewording a
            // disclosure could weaken the very statement that makes the page compliant.
            sb.append("\n\nInclude this disclosure verbatim in the legal section, word for word: ")
              .append(brief.disclosure().trim());
        }
        return sb.toString();
    }

    private void appendIf(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    /**
     * The tool schema, which doubles as the output contract.
     *
     * <p>{@code section.type} is an enum over the renderer's <em>existing</em> block vocabulary so a
     * generated draft writes straight into {@code landing_templates.blocks} and renders through
     * {@code LandingService.renderBlock} with no new render path. A free-text type would produce
     * sections the renderer silently drops as {@code richText}.
     */
    private ObjectNode toolSchema() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Return the generated landing page drafts.");

        ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("variants");

        ObjectNode variants = schema.putObject("properties").putObject("variants");
        variants.put("type", "array");
        ObjectNode variant = variants.putObject("items");
        variant.put("type", "object");
        variant.put("additionalProperties", false);
        ArrayNode variantRequired = variant.putArray("required");
        for (String required : List.of("headline", "subheadline", "ctaText", "sections")) {
            variantRequired.add(required);
        }

        ObjectNode props = variant.putObject("properties");
        describe(props, "headline", "The hero headline. Specific to this campaign, never a greeting.");
        describe(props, "subheadline", "One supporting line beneath the headline.");
        describe(props, "offerText", "The offer in the visitor's words. Omit when the brief gives no offer.");
        describe(props, "ctaText", "The button label. An action, such as 'Shop the collection'.");

        ObjectNode sections = props.putObject("sections");
        sections.put("type", "array");
        sections.put("description", "The page's sections, in display order.");
        ObjectNode section = sections.putObject("items");
        section.put("type", "object");
        section.put("additionalProperties", false);
        ArrayNode sectionRequired = section.putArray("required");
        for (String required : List.of("type", "title", "body")) {
            sectionRequired.add(required);
        }

        ObjectNode sectionProps = section.putObject("properties");
        ObjectNode type = sectionProps.putObject("type");
        type.put("type", "string");
        type.put("description",
                "The block type. couponBlock renders the creator's real code at request time and takes no body. "
                        + "proof carries two to four short reasons to buy, one per line in the body. "
                        + "creator is the creator's own words about the product, and may only be used when the "
                        + "brief names a creator.");
        ArrayNode allowed = type.putArray("enum");
        // PR-58. Widened from the five legacy block names to include `proof` and `creator`.
        //
        // WHY THIS MATTERED. The editor has had eight section types since PR-39, and this enum still
        // listed the renderer's ORIGINAL five — so the two sections that carry the most weight on an
        // influencer page were unreachable by the model. Anything it wrote toward them fell through
        // `sectionsFromVariant`'s default branch and became a plain Text section. The generator was
        // not weak at reasons-to-buy; it was never allowed to emit them.
        //
        // `media` is deliberately still absent. The model is not asked to invent an image URL — a
        // plausible-looking one that 404s is worse on a public page than an obvious empty frame —
        // and a media section with no asset is dropped by the renderer anyway.
        for (String value : List.of("hero", "richText", "couponBlock", "productCta", "proof", "creator", "legal")) {
            allowed.add(value);
        }
        describe(sectionProps, "title", "Short label shown in the editor, such as 'Offer'.");
        describe(sectionProps, "body", "The visitor-facing text. Empty for couponBlock.");

        // The designed arrangement, not a layout instruction: every value here is a variant the
        // stylesheet already implements, so the model picks among finished designs and still cannot
        // express a colour, font, size or position. That is the curated-editor line from
        // sectionTypes.js, and widening the vocabulary must not cross it.
        //
        // Without this every generated page landed on variants[0] — the same centred hero every
        // time — so three drafts the prompt asked to differ "in structure and angle" rendered
        // near-identically. Optional: an omitted or unrecognised variant falls back to the type's
        // default, which is what the editor does for a hand-added section.
        ObjectNode variantProp = sectionProps.putObject("variant");
        variantProp.put("type", "string");
        variantProp.put("description",
                "Optional. The designed arrangement for this section: hero and offer take "
                        + "centred|left|split; proof takes grid|stacked-list; creator takes "
                        + "portrait-left|quote-first; text takes one-column|two-column. Vary it between "
                        + "drafts so they differ in shape, not only in wording. Omit it to use the default.");
        return tool;
    }

    private void describe(ObjectNode properties, String name, String description) {
        ObjectNode node = properties.putObject(name);
        node.put("type", "string");
        node.put("description", description);
    }

    // ---- response -----------------------------------------------------

    /**
     * Read the forced tool call out of the response.
     *
     * <p>Every failure degrades to an empty list rather than an exception: to the caller a malformed
     * response and an unreachable API are the same event, and both must land on the template
     * generator rather than on a 500.
     */
    private List<Variant> readVariants(JsonNode body, Brief brief) {
        List<Variant> variants = new ArrayList<>();
        JsonNode input = toolInput(body, TOOL_NAME);
        JsonNode list = input == null ? null : input.get("variants");
        if (list == null || !list.isArray()) {
            return variants;
        }
        for (JsonNode node : list) {
            // Indexed on what survived, not on position in the response: dropping a malformed
            // second variant must not leave the third one labelled "variant_c" with no B.
            Variant variant = readVariant(node, brief, variants.size());
            if (variant != null) {
                variants.add(variant);
            }
        }
        return variants;
    }

    /**
     * The arguments of the forced tool call, or null if the response carries none.
     *
     * <p>The response may also contain text blocks; only the tool call is an answer. Shared by
     * generation and rewrite so both treat a text-only reply as "no answer" identically.
     */
    private JsonNode toolInput(JsonNode body, String toolName) {
        JsonNode content = body == null ? null : body.get("content");
        if (content == null || !content.isArray()) {
            return null;
        }
        for (JsonNode block : content) {
            if ("tool_use".equals(text(block, "type")) && toolName.equals(text(block, "name"))) {
                return block.get("input");
            }
        }
        return null;
    }

    private Variant readVariant(JsonNode node, Brief brief, int index) {
        String headline = text(node, "headline");
        if (headline == null || headline.isBlank()) {
            // Not a weak draft but an unusable one — the hero is the one section every layout
            // renders. Drop it and let the others stand rather than failing the whole generation.
            log.info("Dropping a generated variant with no headline");
            return null;
        }

        List<Section> sections = new ArrayList<>();
        JsonNode nodes = node.get("sections");
        if (nodes != null && nodes.isArray()) {
            for (JsonNode section : nodes) {
                String type = text(section, "type");
                if (type == null || type.isBlank()) {
                    continue;
                }
                // PR-58. A variant the stylesheet does not implement is dropped rather than
                // passed through: an unknown value would render as an unstyled section, which
                // looks broken in a way the default never does.
                String variant = textOr(section, "variant", "");
                sections.add(new Section(type, textOr(section, "title", ""), textOr(section, "body", ""),
                        null, VALID_VARIANTS.contains(variant) ? variant : null));
            }
        }
        if (sections.isEmpty()) {
            return null;
        }

        return new Variant(
                "variant_" + (char) ('a' + index),
                // Scored here rather than read from the model — see ConversionScore for why.
                ConversionScore.score(brief, sections),
                headline,
                textOr(node, "subheadline", ""),
                text(node, "offerText"),
                textOr(node, "ctaText", "Shop now"),
                sections);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null ? fallback : value;
    }
}
