package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Evaluates a brand's vetting rules against a creator (roadmap C2.3).
 *
 * <p><b>Rules may reject and advance. They may never approve.</b> (Roadmap #5.) This class has
 * no code path that produces {@code approved}, and {@link Outcome} has no such value — the
 * asymmetry is enforced here as well as by the DB constraint, because rejection is reversible
 * while approval grants access to briefs, assets and eventually money.
 *
 * <p><b>First match wins, in `position` order.</b> A brand can therefore put a specific
 * exception above a general rule. Without ordering, overlapping rules would resolve
 * arbitrarily and a brand could not reason about its own configuration.
 *
 * <p><b>Demographic conditions read AUDIENCE attributes only.</b> A rule says "this creator's
 * followers are 70% aged 18-24", never "this creator is 22". That is the difference between
 * campaign targeting and screening a person by protected characteristics, and it is why no
 * creator-personal demographic field exists on the schema at all — the safest guarantee that a
 * rule cannot filter on them is for the data never to be collected.
 */
@Component
public class VettingRuleEngine {

    /** What a rule may do. Deliberately no APPROVE — see the class comment. */
    public enum Action { REJECT, REVIEW }

    /**
     * The result of evaluating a brand's whole rule set.
     *
     * @param status     the vetting status to move to
     * @param ruleId     the rule that decided, or null when no rule matched
     * @param ruleName   for the audit trail, so an event is readable without a join
     * @param reason     shown to the creator on a rejection and to the brand in the queue
     */
    public record Outcome(String status, String ruleId, String ruleName, String reason) {}

    /**
     * Attributes a rule may read.
     *
     * <p>An allow-list, not an open field reference. A rule naming an arbitrary column could
     * reach a creator's own demographics or another brand's negotiated rate; restricting the
     * vocabulary here means a malformed or malicious rule simply does not match.
     */
    private static final Set<String> NUMERIC_ATTRIBUTES = Set.of(
            "follower_count", "engagement_rate", "average_views",
            "audience_size_estimate", "brand_safety_score");

    private static final Set<String> STRING_ATTRIBUTES = Set.of("niche", "platform", "status");

    private static final Set<String> ARRAY_ATTRIBUTES = Set.of(
            "risk_flags", "content_themes", "content_categories");

    /** Audience demographics, addressed as a path inside the jsonb: `audience.geo.US`. */
    private static final String AUDIENCE_PREFIX = "audience.";

    /**
     * Evaluate rules in order and return the first match.
     *
     * @param creator the creator as the DAO returned it
     * @param rules   the brand's rules, already ordered by position
     * @return the outcome, or a review outcome when nothing matched (C2.6: anything a rule did
     *         not resolve goes to a human, never to approval)
     */
    public Outcome evaluate(JsonNode creator, JsonNode rules) {
        if (rules != null && rules.isArray()) {
            for (JsonNode rule : rules) {
                if (!rule.path("enabled").asBoolean(true)) {
                    continue;
                }
                if (matches(creator, rule.get("condition"))) {
                    Action action = parseAction(rule.path("action").asText("review"));
                    return new Outcome(
                            action == Action.REJECT ? "rejected" : "under_review",
                            rule.path("id").asText(null),
                            rule.path("name").asText(null),
                            rule.path("reason").asText(null));
                }
            }
        }
        // Nothing matched. The creator goes to a human, NOT to approved — a rule set that does
        // not mention someone is silence, not endorsement.
        return new Outcome("under_review", null, null, "No rule matched; queued for review.");
    }

    /**
     * Count how many of a set of creators a draft rule would match (C2.4, the dry-run).
     *
     * <p>The roadmap calls this out as mattering more than it looks: a rule that would silently
     * reject 80% of a brand's existing creators should be discovered before it is switched on,
     * not after. Cheap to build, and it prevents the worst failure mode here.
     */
    public List<String> dryRun(JsonNode creators, JsonNode condition) {
        List<String> matched = new ArrayList<>();
        if (creators == null || !creators.isArray()) {
            return matched;
        }
        for (JsonNode creator : creators) {
            if (matches(creator, condition)) {
                matched.add(creator.path("id").asText());
            }
        }
        return matched;
    }

    // ---- condition evaluation -------------------------------------------

    boolean matches(JsonNode creator, JsonNode rawCondition) {
        // The DAO maps jsonb columns as Java String, so a stored condition arrives as TEXT even
        // though it is an object in Postgres. Requiring an object here silently matched nothing
        // — every rule was inert and every creator fell through to review. Normalizing both
        // shapes is the fix; this is the fourth time this trap has appeared in this codebase.
        JsonNode condition = parseObject(rawCondition);
        if (creator == null || condition == null || !condition.isObject()) {
            return false;
        }
        String attribute = condition.path("attribute").asText("").trim();
        String operator = condition.path("operator").asText("").trim().toLowerCase(Locale.ROOT);
        JsonNode value = condition.get("value");
        if (attribute.isEmpty() || operator.isEmpty()) {
            return false;
        }

        if (attribute.startsWith(AUDIENCE_PREFIX)) {
            return matchesAudience(creator, attribute.substring(AUDIENCE_PREFIX.length()), operator, value);
        }
        if (NUMERIC_ATTRIBUTES.contains(attribute)) {
            return matchesNumeric(readNumber(creator, attribute), operator, value);
        }
        if (STRING_ATTRIBUTES.contains(attribute)) {
            return matchesString(creator.path(camel(attribute)).asText(null), operator, value);
        }
        if (ARRAY_ATTRIBUTES.contains(attribute)) {
            return matchesArray(creator.get(camel(attribute)), operator, value);
        }
        // An unknown attribute never matches. Failing closed means a typo in a rule leaves the
        // creator in the review queue rather than silently rejecting or silently passing them.
        return false;
    }

    /**
     * A numeric comparison where the creator's value is absent never matches.
     *
     * <p>This is the C.6 decision showing up again: an unresolved handle leaves
     * {@code follower_count} null, and "we do not know" must not be read as zero. A rule
     * {@code follower_count < 5000} would otherwise reject every creator whose lookup failed.
     */
    private boolean matchesNumeric(BigDecimal actual, String operator, JsonNode value) {
        if (actual == null || value == null || !value.isNumber()) {
            return false;
        }
        int cmp = actual.compareTo(value.decimalValue());
        return switch (operator) {
            case "lt" -> cmp < 0;
            case "lte" -> cmp <= 0;
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "eq" -> cmp == 0;
            case "neq" -> cmp != 0;
            default -> false;
        };
    }

    private boolean matchesString(String actual, String operator, JsonNode value) {
        if (value == null) {
            return false;
        }
        String expected = value.isTextual() ? value.asText() : value.toString();
        boolean present = actual != null && !actual.isBlank();
        return switch (operator) {
            case "eq" -> present && actual.equalsIgnoreCase(expected);
            case "neq" -> !present || !actual.equalsIgnoreCase(expected);
            case "in" -> present && containsIgnoreCase(value, actual);
            case "not_in" -> !present || !containsIgnoreCase(value, actual);
            case "contains" -> present && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            case "is_null" -> !present;
            default -> false;
        };
    }

    private boolean matchesArray(JsonNode actual, String operator, JsonNode value) {
        List<String> values = new ArrayList<>();
        if (actual != null && actual.isArray()) {
            actual.forEach(v -> values.add(v.asText("").toLowerCase(Locale.ROOT)));
        }
        return switch (operator) {
            case "contains" -> value != null && values.contains(value.asText("").toLowerCase(Locale.ROOT));
            case "not_contains" -> value == null || !values.contains(value.asText("").toLowerCase(Locale.ROOT));
            case "contains_any" -> value != null && value.isArray()
                    && anyMatch(value, values);
            case "is_empty" -> values.isEmpty();
            case "is_not_empty" -> !values.isEmpty();
            default -> false;
        };
    }

    /**
     * Audience demographics, addressed by dotted path inside the jsonb — {@code audience.geo.US}.
     *
     * <p>Only the audience blob is reachable this way. The creator's own row is not addressable
     * by path, so a demographic rule cannot be pointed at the person rather than their audience.
     */
    private boolean matchesAudience(JsonNode creator, String path, String operator, JsonNode value) {
        JsonNode node = creator.get("audienceDemographics");
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            try {
                node = MAPPER.readTree(node.asText());
            } catch (Exception e) {
                return false;
            }
        }
        for (String segment : path.split("\\.")) {
            if (node == null) {
                return false;
            }
            node = node.get(segment);
        }
        if (node == null || !node.isNumber()) {
            return false;
        }
        return matchesNumeric(node.decimalValue(), operator, value);
    }

    // ---- helpers ---------------------------------------------------------

    /** Shared mapper: parsing a condition per evaluation is hot enough not to build one each time. */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Accept a condition as either a parsed object or a JSON string.
     *
     * <p>Necessary because jsonb columns are mapped as Java {@code String} on the entity, so the
     * same field is an object on the way in and text on the way back out.
     */
    private JsonNode parseObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return node;
        }
        if (node.isTextual()) {
            try {
                JsonNode parsed = MAPPER.readTree(node.asText());
                return parsed.isObject() ? parsed : null;
            } catch (Exception e) {
                // A malformed stored condition matches nothing rather than throwing: one broken
                // rule must not stop the rest of a brand's rule set from being evaluated.
                return null;
            }
        }
        return null;
    }

    private boolean anyMatch(JsonNode candidates, List<String> values) {
        for (JsonNode candidate : candidates) {
            if (values.contains(candidate.asText("").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(JsonNode array, String actual) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode candidate : array) {
            if (candidate.asText("").equalsIgnoreCase(actual)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal readNumber(JsonNode creator, String attribute) {
        JsonNode node = creator.get(camel(attribute));
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Rules are written in snake_case; the API speaks camelCase. */
    private String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    private Action parseAction(String action) {
        // Anything unrecognised — including a smuggled "approve" — degrades to REVIEW rather
        // than being honoured. A rule set cannot talk its way into approving someone.
        return "reject".equalsIgnoreCase(action) ? Action.REJECT : Action.REVIEW;
    }
}
