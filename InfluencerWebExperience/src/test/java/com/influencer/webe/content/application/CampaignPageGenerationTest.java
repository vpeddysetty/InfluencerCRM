package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.PageGenerationPort.Brief;
import com.influencer.webe.content.application.PageGenerationPort.Result;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the promise this feature is built on: the user always ends up with an editable page.
 *
 * <p>The failure this class exists to prevent is the one the design spec calls out by name — a
 * generation that fails and leaves the user staring at the blank canvas the whole feature was
 * built to remove. Every path below asserts that a broken, empty, or lying generator still yields
 * drafts, and that the response says plainly which generator produced them.
 */
class CampaignPageGenerationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplatePageGenerator template = new TemplatePageGenerator();

    /**
     * Builds the service with a preferred generator that sits in front of the template one.
     *
     * <p>Registry order matters: the template generator must always be present, because it is what
     * {@code fallback()} resolves to.
     */
    private CampaignPageGenerationService serviceWith(PageGenerationPort preferred) {
        PageGeneratorRegistry registry =
                new PageGeneratorRegistry(List.of(preferred, template), preferred.key());
        // A null DAO is safe: enrichment only runs for a payload carrying a brandId, and these
        // tests send none. The enrichment path has its own coverage in BriefEnricherTest.
        return new CampaignPageGenerationService(
                registry, new ResponseShapeService(MAPPER), new BriefEnricher(null));
    }

    private ObjectNode brief() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("goal", "Launch the winter trail collection");
        payload.put("audience", "Hikers 25-40");
        payload.put("offer", "15% off the first order");
        payload.put("creatorHandle", "@northbound");
        payload.putArray("proofPoints").add("Recycled fabric").add("Two-year guarantee");
        return payload;
    }

    // ---- the fallback promise -----------------------------------------

    @Test
    @DisplayName("a generator that returns nothing still yields an editable draft")
    void emptyGeneratorFallsBackToTemplate() {
        CampaignPageGenerationService service = serviceWith(
                stub("stub", (b, n) -> Result.unavailable("stub", "model was busy")));

        JsonNode response = service.generate(brief());

        assertTrue(response.get("variants").size() > 0,
                "a failed generation must still hand the user something to edit");
        assertEquals("template", response.get("generator").asText(),
                "the response must name the generator that actually produced the drafts");
        assertTrue(response.get("fallback").asBoolean(),
                "a template draft presented as an AI draft is the failure this flag prevents");
        assertEquals("model was busy", response.get("detail").asText(),
                "the reason the preferred generator did not run is worth keeping");
    }

    @Test
    @DisplayName("a generator that throws is treated as unavailable, not as a server error")
    void throwingGeneratorFallsBackToTemplate() {
        // The port forbids throwing, but a contract is not an enforcement mechanism. A generator
        // that breaks its promise must degrade exactly like one that returns nothing.
        CampaignPageGenerationService service = serviceWith(stub("stub", (b, n) -> {
            throw new IllegalStateException("boom");
        }));

        JsonNode response = service.generate(brief());

        assertTrue(response.get("variants").size() > 0);
        assertEquals("template", response.get("generator").asText());
        assertTrue(response.get("fallback").asBoolean());
    }

    @Test
    @DisplayName("drafts missing a hero or a call to action are dropped, not published")
    void structurallyUnusableVariantsAreRejected() {
        // A page with no hero and no action renders as a paragraph on a public URL. Better to fall
        // back to a complete template draft than to show the user something unpublishable.
        Variant noCta = new Variant("variant_a", 90, "A headline", "sub", null, "Shop",
                List.of(new Section("hero", "Hero", "A headline")));
        CampaignPageGenerationService service = serviceWith(
                stub("stub", (b, n) -> Result.of(List.of(noCta), "stub")));

        JsonNode response = service.generate(brief());

        assertEquals("template", response.get("generator").asText(),
                "a draft with no call to action must not reach the user");
        assertTrue(response.get("fallback").asBoolean());
    }

    @Test
    @DisplayName("duplicate headlines collapse so the comparison screen shows real choices")
    void duplicateHeadlinesAreCollapsed() {
        // Showing the same page twice is worse than showing one option: it makes the compare step
        // look broken and invites the user to regenerate for no reason.
        Variant first = usable("variant_a", "Your trail, upgraded");
        Variant duplicate = usable("variant_b", "  YOUR TRAIL, UPGRADED  ");
        Variant distinct = usable("variant_c", "Built for the long way round");

        CampaignPageGenerationService service = serviceWith(
                stub("stub", (b, n) -> Result.of(List.of(first, duplicate, distinct), "stub")));

        JsonNode response = service.generate(brief());

        assertEquals(2, response.get("variants").size(),
                "headlines differing only in case and padding are the same headline");
        assertEquals("stub", response.get("generator").asText());
        assertFalse(response.get("fallback").asBoolean(),
                "dropping a duplicate is not a generation failure");
    }

    @Test
    @DisplayName("a brief with no goal is rejected before any model is called")
    void goalIsRequired() {
        // The one field with no sensible default: it is what the entire page is about, and a
        // generator given nothing would invent a campaign.
        CampaignPageGenerationService service = serviceWith(template);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("audience", "Hikers");

        ResponseStatusException error =
                assertThrows(ResponseStatusException.class, () -> service.generate(payload));
        assertEquals(400, error.getStatusCode().value());
    }

    // ---- the draft is a landing template ------------------------------

    @Test
    @DisplayName("a draft converts to blocks the existing renderer already understands")
    void draftsConvertToRenderableBlocks() {
        // This is what lets a generated draft be saved through the ordinary landing-template path
        // and rendered with no new render code. If the block shape drifts, the page renders blank.
        CampaignPageGenerationService service = serviceWith(template);

        JsonNode response = service.generate(brief());
        JsonNode blocks = response.get("variants").get(0).get("blocks");

        assertNotNull(blocks, "a draft must travel with its builder-ready blocks");
        assertTrue(blocks.size() > 0);
        for (JsonNode block : blocks) {
            String type = block.get("type").asText();
            switch (type) {
                // couponBlock renders the creator's real code at request time; authored text here
                // would be a second, stale copy of the discount.
                case "couponBlock" -> assertFalse(block.has("text"),
                        "couponBlock carries no authored text");
                // renderBlock reads productCta's label, not its text.
                case "productCta" -> assertTrue(block.hasNonNull("label"),
                        "productCta must carry the button label the renderer reads");
                default -> assertTrue(block.has("text"),
                        type + " must carry the text the renderer reads");
            }
        }
    }

    // ---- the template generator itself --------------------------------

    @Test
    @DisplayName("the template generator omits sections the brief cannot support")
    void templateOmitsUnsupportedSections() {
        // The edge case the checklist names: absent input must produce an absent section, never an
        // invented creator or a fabricated discount on a page published under the brand's name.
        Brief sparse = new Brief(null, "Launch the winter collection", null, null, null, null, null, null);

        Result result = template.generate(sparse, 3);

        assertFalse(result.isEmpty());
        for (Variant variant : result.variants()) {
            for (Section section : variant.sections()) {
                assertFalse("couponBlock".equals(section.type()),
                        "a brief with no offer must not produce a coupon block");
            }
            assertTrue(variant.sections().stream().anyMatch(s -> "hero".equals(s.type())));
            assertTrue(variant.sections().stream().anyMatch(s -> "productCta".equals(s.type())));
        }
    }

    @Test
    @DisplayName("the template generator's variants differ in structure, not just wording")
    void templateVariantsAreStructurallyDistinct() {
        // Three orderings of the same copy is a weak version of the feature; three copies of
        // identical copy is a fake one, and the compare screen would have nothing to compare.
        Result result = template.generate(new Brief(
                null, "Launch the winter collection", "Hikers", "15% off", "@northbound",
                null, "Shop the collection", List.of("Recycled fabric")), 3);

        assertEquals(3, result.variants().size());
        List<String> orderings = result.variants().stream()
                .map(v -> v.sections().stream().map(Section::type).reduce("", String::concat))
                .distinct()
                .toList();
        assertEquals(3, orderings.size(), "each variant must lay its sections out differently");
    }

    @Test
    @DisplayName("the score rewards a complete page and never exceeds its bounds")
    void scoreReflectsCompleteness() {
        Brief rich = new Brief(null, "Launch the winter collection", "Hikers", "15% off",
                "@northbound", null, "Shop the collection", List.of("Recycled fabric"));
        Brief sparse = new Brief(null, "Launch the winter collection", null, null, null, null, null, null);

        int richScore = template.generate(rich, 1).variants().get(0).score();
        int sparseScore = template.generate(sparse, 1).variants().get(0).score();

        assertTrue(richScore > sparseScore,
                "a page with an offer, a creator and proof must score above one without");
        assertTrue(richScore <= 100 && sparseScore >= 0, "the score is a 0-100 figure");
    }

    // ---- registry -----------------------------------------------------

    @Test
    @DisplayName("an unknown provider key resolves to the template generator rather than failing")
    void unknownProviderKeyDegradesToTemplate() {
        // A typo in the environment variable must degrade to the generator that always works, for
        // the same reason BillingProviderRegistry falls back to manual rather than throwing.
        PageGeneratorRegistry registry = new PageGeneratorRegistry(List.of(template), "anthropc");
        assertEquals("template", registry.active().key());
    }

    // ---- helpers ------------------------------------------------------

    private Variant usable(String id, String headline) {
        return new Variant(id, 80, headline, "sub", "15% off", "Shop", List.of(
                new Section("hero", "Hero", headline),
                new Section("productCta", "CTA", "Shop")));
    }

    /** A generator whose behaviour the test dictates. */
    private PageGenerationPort stub(String key, Generator generator) {
        return new PageGenerationPort() {
            @Override
            public Result generate(Brief brief, int count) {
                return generator.generate(brief, count);
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
}
