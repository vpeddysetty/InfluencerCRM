package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule engine decides whether a real person is rejected from a brand's programme, so its
 * behaviour is pinned directly rather than only through the API.
 *
 * <p>The tests that matter most are the ones asserting what the engine will NOT do: approve
 * (roadmap #5), match on a missing metric, or match an unknown attribute.
 */
class VettingRuleEngineTest {

    private final VettingRuleEngine engine = new VettingRuleEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode creator(String extra) {
        return json("""
                { "id": "c1", "handle": "someone", "platform": "instagram",
                  "followerCount": 25000, "engagementRate": 3.5, "niche": "beauty",
                  "riskFlags": ["alcohol"], "contentThemes": ["skincare"],
                  "audienceDemographics": {"geo": {"US": 0.44, "GB": 0.12},
                                            "age": {"18-24": 0.38}}
                  %s }""".formatted(extra.isEmpty() ? "" : "," + extra));
    }

    private JsonNode rules(String raw) {
        return json(raw);
    }

    // ---- the asymmetry that defines this phase --------------------------

    @Test
    @DisplayName("a rule can never approve, even when it asks to")
    void rulesCannotApprove() {
        // A rule claiming action:"approve" must NOT approve. The DB constraint would reject the
        // row, but the engine degrades it to review as well: defence at both layers, because
        // this is the decision the roadmap says must never happen automatically.
        var result = engine.evaluate(creator(""), rules("""
                [{"id":"r1","name":"Sneaky","enabled":true,
                  "condition":{"attribute":"follower_count","operator":"gt","value":100},
                  "action":"approve"}]"""));

        assertThat(result.status()).isEqualTo("under_review");
        assertThat(result.status()).isNotEqualTo("approved");
    }

    @Test
    @DisplayName("no rule matching means review, never approval")
    void silenceIsNotEndorsement() {
        var result = engine.evaluate(creator(""), rules("[]"));

        assertThat(result.status()).isEqualTo("under_review");
        assertThat(result.ruleId()).isNull();
    }

    // ---- ordering --------------------------------------------------------

    @Test
    @DisplayName("first match wins, so a specific exception can sit above a general rule")
    void firstMatchWins() {
        var result = engine.evaluate(creator(""), rules("""
                [{"id":"specific","name":"Allow beauty","enabled":true,
                  "condition":{"attribute":"niche","operator":"eq","value":"beauty"},
                  "action":"review","reason":"beauty needs a look"},
                 {"id":"general","name":"Reject small","enabled":true,
                  "condition":{"attribute":"follower_count","operator":"lt","value":50000},
                  "action":"reject"}]"""));

        assertThat(result.ruleId()).isEqualTo("specific");
        assertThat(result.status()).isEqualTo("under_review");
    }

    @Test
    @DisplayName("a disabled rule is skipped entirely")
    void disabledRuleSkipped() {
        var result = engine.evaluate(creator(""), rules("""
                [{"id":"off","name":"Off","enabled":false,
                  "condition":{"attribute":"follower_count","operator":"lt","value":50000},
                  "action":"reject"}]"""));

        assertThat(result.status()).isEqualTo("under_review");
        assertThat(result.ruleId()).isNull();
    }

    // ---- the missing-metric case ----------------------------------------

    @Test
    @DisplayName("an absent metric never matches — 'unknown' is not zero")
    void absentMetricNeverMatches() {
        // C.6 again: an unresolved handle leaves followerCount null. Treating null as 0 would
        // make `follower_count < 5000` reject every creator whose platform lookup failed.
        JsonNode noMetrics = json("""
                {"id":"c2","handle":"unknown_person","platform":"instagram","niche":null}""");

        var result = engine.evaluate(noMetrics, rules("""
                [{"id":"r1","name":"Too small","enabled":true,
                  "condition":{"attribute":"follower_count","operator":"lt","value":5000},
                  "action":"reject"}]"""));

        assertThat(result.status()).isEqualTo("under_review");
        assertThat(result.ruleId()).isNull();
    }

    // ---- attribute coverage ---------------------------------------------

    @Test
    @DisplayName("numeric comparisons")
    void numericOperators() {
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"follower_count\",\"operator\":\"gt\",\"value\":10000}"))).isTrue();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"follower_count\",\"operator\":\"lt\",\"value\":10000}"))).isFalse();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"engagement_rate\",\"operator\":\"gte\",\"value\":3.5}"))).isTrue();
    }

    @Test
    @DisplayName("risk flags are matched as an array")
    void arrayOperators() {
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"risk_flags\",\"operator\":\"contains\",\"value\":\"alcohol\"}"))).isTrue();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"risk_flags\",\"operator\":\"contains\",\"value\":\"gambling\"}"))).isFalse();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"risk_flags\",\"operator\":\"contains_any\",\"value\":[\"gambling\",\"alcohol\"]}"))).isTrue();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"risk_flags\",\"operator\":\"is_empty\"}"))).isFalse();
    }

    @Test
    @DisplayName("niche can be matched against an allowed list")
    void nicheInList() {
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"niche\",\"operator\":\"in\",\"value\":[\"beauty\",\"fashion\"]}"))).isTrue();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"niche\",\"operator\":\"not_in\",\"value\":[\"beauty\",\"fashion\"]}"))).isFalse();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"niche\",\"operator\":\"not_in\",\"value\":[\"gaming\"]}"))).isTrue();
    }

    @Test
    @DisplayName("audience demographics are addressable by path")
    void audienceByPath() {
        // Decision #14: rules MAY read audience attributes.
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"audience.geo.US\",\"operator\":\"gte\",\"value\":0.4}"))).isTrue();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"audience.geo.US\",\"operator\":\"lt\",\"value\":0.4}"))).isFalse();
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"audience.age.18-24\",\"operator\":\"gt\",\"value\":0.3}"))).isTrue();
    }

    @Test
    @DisplayName("an unknown attribute never matches — a typo must not silently reject")
    void unknownAttributeFailsClosed() {
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"folower_count\",\"operator\":\"lt\",\"value\":999999}"))).isFalse();
        // And crucially, not a path into the creator's own row either.
        assertThat(engine.matches(creator(""), json(
                "{\"attribute\":\"preferred_rate\",\"operator\":\"gt\",\"value\":0}"))).isFalse();
    }

    // ---- the dry-run (C2.4) ---------------------------------------------

    @Test
    @DisplayName("dry-run reports which existing creators a draft rule would hit")
    void dryRunCountsMatches() {
        // The roadmap: a rule that would silently reject 80% of a brand's roster should be
        // discovered before it is switched on, not after.
        JsonNode roster = json("""
                [{"id":"a","followerCount":1000},
                 {"id":"b","followerCount":9000},
                 {"id":"c","followerCount":100000},
                 {"id":"d"}]""");

        var matched = engine.dryRun(roster, json(
                "{\"attribute\":\"follower_count\",\"operator\":\"lt\",\"value\":10000}"));

        assertThat(matched).containsExactly("a", "b");
        // "d" has no follower count and is deliberately absent: unknown is not small.
        assertThat(matched).doesNotContain("d");
    }

    @Test
    @DisplayName("a condition stored as a JSON string still matches")
    void conditionAsStringStillMatches() {
        // Regression. The DAO maps jsonb as Java String, so a saved condition comes back as
        // TEXT. Requiring an object made every stored rule inert: nothing matched, and every
        // creator fell through to review while the rule set looked correct in the UI.
        JsonNode stringCondition = mapper.getNodeFactory().textNode(
                "{\"attribute\":\"risk_flags\",\"operator\":\"contains\",\"value\":\"alcohol\"}");

        assertThat(engine.matches(creator(""), stringCondition)).isTrue();
    }

    @Test
    @DisplayName("a malformed stored condition matches nothing rather than throwing")
    void malformedConditionIsInert() {
        // One broken rule must not stop the rest of a brand's rule set from being evaluated.
        JsonNode broken = mapper.getNodeFactory().textNode("{not json");

        assertThat(engine.matches(creator(""), broken)).isFalse();
    }
}
