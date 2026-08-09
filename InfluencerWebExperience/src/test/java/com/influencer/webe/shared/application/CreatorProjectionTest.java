package com.influencer.webe.shared.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the creator projection against silent field loss.
 *
 * <p>The projection is an allow-list. A column can exist on the table, be populated by the DAO,
 * and still never reach the UI because nobody added its name here — and nothing fails when that
 * happens. That is precisely how {@code preferredRate} came to be invisible: the schema, the
 * entity, and the DAO controller were all correct, the field was simply dropped in transit.
 *
 * <p>These tests exist because that failure is silent by construction. Grepping the UI for the
 * field name finds nothing and suggests a missing screen, when the actual break is one layer down.
 */
class CreatorProjectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResponseShapeService shape = new ResponseShapeService(objectMapper);

    private ObjectNode daoCreator() {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("id", "11111111-1111-1111-1111-111111111111");
        source.put("brandId", "22222222-2222-2222-2222-222222222222");
        source.put("name", "Ada Lovelace");
        source.put("handle", "@ada");
        source.put("platform", "instagram");
        source.put("email", "ada@example.com");
        source.put("preferredRate", new BigDecimal("2500"));
        return source;
    }

    @Test
    @DisplayName("preferredRate survives the projection")
    void preferredRateIsExposed() {
        JsonNode out = shape.creator(daoCreator());

        assertTrue(out.hasNonNull("preferredRate"),
                "preferredRate must reach the UI — it is the per-brand negotiated rate, and the "
                        + "one capability with no documented competitor equivalent");
        assertEquals(new BigDecimal("2500"), out.get("preferredRate").decimalValue());
    }

    @Test
    @DisplayName("a rate of zero is preserved, not dropped as absent")
    void zeroRateIsNotTreatedAsMissing() {
        ObjectNode source = daoCreator();
        source.put("preferredRate", BigDecimal.ZERO);

        JsonNode out = shape.creator(source);

        // A rate negotiated to zero and no rate agreed are different facts. Collapsing them would
        // make a gifting arrangement indistinguishable from an unfinished negotiation.
        assertTrue(out.hasNonNull("preferredRate"));
        assertEquals(0, out.get("preferredRate").decimalValue().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("an absent rate stays absent rather than becoming zero")
    void absentRateStaysAbsent() {
        ObjectNode source = daoCreator();
        source.remove("preferredRate");

        JsonNode out = shape.creator(source);

        // The C8c principle: writing 0 as a stand-in for "unknown" is what makes a
        // `rate < X` filter silently match every creator whose rate was never set.
        assertFalse(out.hasNonNull("preferredRate"));
    }

    @Test
    @DisplayName("the rate survives the list projection too, not just the single-creator one")
    void listProjectionAlsoExposesRate() {
        // creatorsList() and creator() are separate code paths. A field added to one and not the
        // other shows on the detail drawer and vanishes from the table, which reads as a bug in
        // the table.
        var array = objectMapper.createArrayNode();
        array.add(daoCreator());

        JsonNode out = shape.creatorsList(array, null, null);

        assertTrue(out.isArray() && out.size() == 1);
        assertTrue(out.get(0).hasNonNull("preferredRate"));
    }

    @Test
    @DisplayName("the projection still drops fields it was never asked to expose")
    void unknownFieldsAreStillFiltered() {
        // The allow-list must stay an allow-list. If a change ever turned it into a pass-through,
        // every one of these tests would keep passing while the projection stopped protecting
        // anything — including columns that should not leave the DAO.
        ObjectNode source = daoCreator();
        source.put("internalScoringSecret", "must-not-leak");

        JsonNode out = shape.creator(source);

        assertFalse(out.has("internalScoringSecret"));
    }
}
