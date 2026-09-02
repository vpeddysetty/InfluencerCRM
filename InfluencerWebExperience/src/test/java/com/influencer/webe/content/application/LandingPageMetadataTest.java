package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The share card a landing page produces (roadmap PR-59).
 *
 * <p>Before this, every render path emitted a {@code <title>} holding the INTERNAL template name
 * and nothing else — so a page whose whole purpose is to be opened from a creator's post previewed
 * as a bare URL everywhere. The metadata is derived from the page's own sections rather than
 * generated: a share card that says something different from the page would be worse than none.
 *
 * <p>Exercises the private helper directly. The alternative is asserting against a full rendered
 * document, which couples the test to the stylesheet and the section markup — both of which change
 * for reasons that have nothing to do with what a preview says.
 */
class LandingPageMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String metadata(ObjectNode template, ArrayNode sections) throws Exception {
        LandingService service = new LandingService(null, null, null, null, null);
        Method m = LandingService.class.getDeclaredMethod(
                "pageMetadata", com.fasterxml.jackson.databind.JsonNode.class,
                com.fasterxml.jackson.databind.JsonNode.class, java.util.Map.class);
        m.setAccessible(true);
        return (String) m.invoke(service, template, sections, java.util.Map.of());
    }

    private ObjectNode template(String name) {
        return MAPPER.createObjectNode().put("name", name);
    }

    private ArrayNode sections(String type, String field, String value) {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode section = arr.addObject();
        section.put("type", type);
        section.putObject("fields").put(field, value);
        return arr;
    }

    @Test
    @DisplayName("the hero headline becomes the title, not the internal page name")
    void headlineWinsOverPageName() throws Exception {
        String out = metadata(template("Winter LP v3 FINAL"),
                sections("hero", "headline", "Trail shoes built for the wet months"));

        assertThat(out).contains("<title>Trail shoes built for the wet months</title>");
        assertThat(out).doesNotContain("Winter LP v3 FINAL");
    }

    @Test
    @DisplayName("the page name is still the fallback when there is no hero headline")
    void pageNameIsTheFallback() throws Exception {
        String out = metadata(template("Winter collection"), MAPPER.createArrayNode());

        assertThat(out).contains("<title>Winter collection</title>");
    }

    @Test
    @DisplayName("the description comes from the page's own words")
    void descriptionFromSections() throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode hero = arr.addObject();
        hero.put("type", "hero");
        hero.putObject("fields")
                .put("headline", "Trail shoes")
                .put("subheadline", "Woven in Portugal and ready for a wet January.");

        String out = metadata(template("LP"), arr);

        assertThat(out).contains("Woven in Portugal and ready for a wet January.");
        assertThat(out).contains("og:description");
        assertThat(out).contains("twitter:description");
    }

    @Test
    @DisplayName("a page with nothing to say omits the description rather than inventing one")
    void noDescriptionWhenThereIsNothing() throws Exception {
        String out = metadata(template("LP"), MAPPER.createArrayNode());

        assertThat(out).doesNotContain("og:description");
        assertThat(out).doesNotContain("name=\"description\"");
    }

    @Test
    @DisplayName("no og:image, because a tag pointing at nothing is a broken preview")
    void noImageTag() throws Exception {
        String out = metadata(template("LP"), sections("hero", "headline", "Trail shoes"));

        assertThat(out).doesNotContain("og:image");
        // summary, not summary_large_image: the large card reserves a band for an image that is
        // not there yet and renders it empty.
        assertThat(out).contains("content=\"summary\"");
    }

    @Test
    @DisplayName("no canonical, because this service has no absolute origin to name")
    void noCanonical() throws Exception {
        // Guessing a hostname would publish a canonical pointing somewhere wrong. Platforms fall
        // back to the URL they fetched, which is always right.
        String out = metadata(template("LP"), sections("hero", "headline", "Trail shoes"));

        assertThat(out).doesNotContain("rel=\"canonical\"");
        assertThat(out).doesNotContain("og:url");
    }

    @Test
    @DisplayName("a long description is cut on a word boundary")
    void descriptionIsTruncatedCleanly() throws Exception {
        String longText = "Woven in Portugal ".repeat(30);
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode hero = arr.addObject();
        hero.put("type", "hero");
        hero.putObject("fields").put("headline", "Trail shoes").put("subheadline", longText);

        String out = metadata(template("LP"), arr);

        assertThat(out).contains("…");
        assertThat(out).doesNotContain("Portug…");
    }

    @Test
    @DisplayName("markup in a section cannot escape into the head")
    void escapesMarkup() throws Exception {
        // Tokens are substituted before this runs, so a coupon or creator name carrying markup
        // reaches here as content. It must be escaped like anything else on the page.
        String out = metadata(template("LP"),
                sections("hero", "headline", "Shoes <script>alert(1)</script>"));

        assertThat(out).doesNotContain("<script>");
        assertThat(out).contains("&lt;script&gt;");
    }
}
