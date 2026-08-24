package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.PageGenerationPort.Brief;
import com.influencer.webe.content.application.PageGenerationPort.Result;
import com.influencer.webe.content.application.PageGenerationPort.RewriteResult;
import com.influencer.webe.content.application.PageGenerationPort.Section;
import com.influencer.webe.content.application.PageGenerationPort.Variant;
import com.influencer.webe.content.infrastructure.TemplatePageGenerator;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section rewrite and single-variant regeneration (roadmap PR-35, screens 3 and 5).
 *
 * <p>These two share a theme the generation tests do not: <b>the user already has something.</b>
 * Generation starts from nothing, so substituting a template draft is a kindness. Rewriting starts
 * from prose the user may have spent time on, so the failure mode to guard is the opposite one —
 * silently replacing their words with something worse.
 */
class CampaignPageEditingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplatePageGenerator template = new TemplatePageGenerator();

    private CampaignPageGenerationService serviceWith(PageGenerationPort preferred) {
        PageGeneratorRegistry registry =
                new PageGeneratorRegistry(List.of(preferred, template), preferred.key());
        return new CampaignPageGenerationService(
                registry, new ResponseShapeService(MAPPER), new BriefEnricher(null));
    }

    private ObjectNode rewritePayload(String type, String body, String instruction) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("goal", "Launch the winter trail collection");
        payload.put("offer", "15% off the first order");
        ObjectNode section = payload.putObject("section");
        section.put("type", type);
        section.put("title", "Offer");
        section.put("body", body);
        payload.put("instruction", instruction);
        return payload;
    }

    // ---- rewrite -------------------------------------------------------

    @Test
    @DisplayName("a generator that cannot reword says so instead of returning the text unchanged")
    void unavailableRewriteIsReportedNotFaked() {
        // Echoing the input back would render as a button that appears to work and changes
        // nothing — the user would press it repeatedly, assuming they had phrased it wrong.
        CampaignPageGenerationService service = serviceWith(
                stub("stub", (b, n) -> Result.of(List.of(), "stub")));

        JsonNode response = service.rewriteSection(
                rewritePayload("richText", "Some existing copy.", "make it punchier"));

        assertFalse(response.get("rewritten").asBoolean(),
                "a generator with no rewrite capability must report that, not fake a rewrite");
        assertTrue(response.hasNonNull("detail"), "the user is owed a reason");
        assertFalse(response.has("section"), "no section means no replacement text");
    }

    @Test
    @DisplayName("a rewrite never changes the section's type")
    void rewriteKeepsTheSectionType() {
        // A generator that returned a different type would silently restructure the page — the
        // caller is replacing one block in place, not choosing a new layout.
        CampaignPageGenerationService service = serviceWith(stub("stub",
                (b, n) -> Result.of(List.of(), "stub"),
                (b, section, instruction) ->
                        RewriteResult.of(new Section("productCta", "Hijacked", "New copy."), "stub")));

        JsonNode response = service.rewriteSection(
                rewritePayload("richText", "Old copy.", "improve it"));

        assertTrue(response.get("rewritten").asBoolean());
        assertEquals("New copy.", response.get("section").get("body").asText());
        assertEquals("richText", response.get("section").get("type").asText(),
                "the type comes from the request, never from the generator's answer");
    }

    @Test
    @DisplayName("a generator that throws during rewrite leaves the user's text alone")
    void throwingRewriteDegradesToUnavailable() {
        CampaignPageGenerationService service = serviceWith(stub("stub",
                (b, n) -> Result.of(List.of(), "stub"),
                (b, section, instruction) -> {
                    throw new IllegalStateException("boom");
                }));

        JsonNode response = service.rewriteSection(
                rewritePayload("richText", "Precious copy.", "improve it"));

        assertFalse(response.get("rewritten").asBoolean());
    }

    @Test
    @DisplayName("the coupon block is refused, because it has no authored text to reword")
    void couponBlockCannotBeRewritten() {
        // It renders each creator's live code at request time. "Rewriting" it could only mean
        // inventing a discount the coupon does not actually carry.
        CampaignPageGenerationService service = serviceWith(template);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.rewriteSection(rewritePayload("couponBlock", "", "make it bolder")));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    @DisplayName("the template generator shortens to the first sentence but will not reword")
    void templateRewriteIsHonestAboutItsLimits() {
        // The one rewrite it can do honestly is mechanical. Everything else is refused rather
        // than approximated, so a page never acquires mangled copy from a generator with no model.
        Brief brief = new Brief(null, "Launch", null, "15% off", null, null, null, List.of());

        RewriteResult shortened = template.rewriteSection(brief,
                new Section("richText", "Offer", "First sentence here. Second one follows."),
                "make it shorter");
        assertEquals("First sentence here.", shortened.section().body());

        RewriteResult refused = template.rewriteSection(brief,
                new Section("richText", "Offer", "Some copy."), "make it punchier and more fun");
        assertTrue(refused.isEmpty(), "it cannot reword, and must not pretend otherwise");
    }

    // ---- regenerate ----------------------------------------------------

    @Test
    @DisplayName("regenerate skips headlines the user has already seen")
    void regenerateReturnsSomethingNew() {
        // A regenerate button that returns the same card is indistinguishable from a broken one.
        Variant seen = usable("variant_a", "Your trail, upgraded");
        Variant fresh = usable("variant_b", "Built for the long way round");
        CampaignPageGenerationService service = serviceWith(
                stub("stub", (b, n) -> Result.of(List.of(seen, fresh), "stub")));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("goal", "Launch the winter trail collection");
        payload.putArray("seenHeadlines").add("Your trail, upgraded");

        JsonNode response = service.regenerateVariant(payload);

        assertEquals(1, response.get("variants").size(), "regenerate replaces one card, not all");
        assertEquals("Built for the long way round",
                response.get("variants").get(0).get("headline").asText());
    }

    @Test
    @DisplayName("nothing new is an answer, not an error")
    void regenerateWithNothingNewReturnsEmptyNotFailure() {
        // The generator worked and simply has nothing further to offer. A 5xx here would tell the
        // user the feature broke, which is both untrue and unactionable.
        Variant only = usable("variant_a", "Your trail, upgraded");
        CampaignPageGenerationService service = serviceWith(
                stub("only-one", (b, n) -> Result.of(List.of(only), "only-one")));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("goal", "Launch the winter trail collection");
        // Everything this generator and the template fallback can produce is already on screen.
        payload.putArray("seenHeadlines")
                .add("Your trail, upgraded")
                .add("15% off the first order — Launch the winter trail collection")
                .add("Launch the winter trail collection");

        JsonNode response = service.regenerateVariant(payload);

        assertEquals(0, response.get("variants").size());
        assertTrue(response.get("fallback").asBoolean());
        assertTrue(response.hasNonNull("detail"));
    }

    // ---- helpers -------------------------------------------------------

    private Variant usable(String id, String headline) {
        return new Variant(id, 80, headline, "sub", "15% off", "Shop", List.of(
                new Section("hero", "Hero", headline),
                new Section("productCta", "CTA", "Shop")));
    }

    private PageGenerationPort stub(String key, Generator generator) {
        return stub(key, generator, null);
    }

    /** A generator whose generate and rewrite behaviour the test dictates. */
    private PageGenerationPort stub(String key, Generator generator, Rewriter rewriter) {
        return new PageGenerationPort() {
            @Override
            public Result generate(Brief brief, int count) {
                return generator.generate(brief, count);
            }

            @Override
            public RewriteResult rewriteSection(Brief brief, Section section, String instruction) {
                return rewriter == null
                        ? PageGenerationPort.super.rewriteSection(brief, section, instruction)
                        : rewriter.rewrite(brief, section, instruction);
            }

            @Override
            public String key() {
                return key;
            }
        };
    }

    @FunctionalInterface
    private interface Generator {
        Result generate(Brief brief, int count);
    }

    @FunctionalInterface
    private interface Rewriter {
        RewriteResult rewrite(Brief brief, Section section, String instruction);
    }
}
