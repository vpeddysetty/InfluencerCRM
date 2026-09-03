package com.influencer.webe.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Getting a creator to the point where money can reach them (roadmap PR-47).
 *
 * <p>Two behaviours carry the weight here, and both are about not telling a brand something false.
 * An account EXISTING is not the same as a creator being payable — those are days apart, and §11.5
 * records that promising a payout date on the wrong one is the specific mistake to avoid. And an
 * unreadable status is UNKNOWN, never "not payable": overwriting a true value with false because
 * Stripe had a bad minute would tell a brand their creator had gone backwards.
 */
class CreatorPayoutOnboardingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CREATOR = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static class StubDao extends DaoGatewayClient {
        private final ObjectNode creator;
        private final List<JsonNode> patches = new ArrayList<>();

        StubDao(ObjectNode creator) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    return null;
                }
            }, null);
            this.creator = creator;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            return path.startsWith("/creators/") ? creator : null;
        }

        @Override
        public JsonNode patch(String path, JsonNode payload) {
            patches.add(payload);
            return payload;
        }

        List<JsonNode> patches() {
            return patches;
        }
    }

    /** Answers whatever the test needs, and records what it was asked. */
    private static class StubPort implements CreatorPayoutOnboardingPort {
        boolean configured = true;
        String startedWith = "unset";
        Onboarding onboarding = new Onboarding("acct_new", "https://connect.stripe.com/setup/x");
        Status status = new Status("acct_new", true, null);

        @Override public String key() { return "stub"; }
        @Override public boolean isConfigured() { return configured; }

        @Override
        public Onboarding start(String existingAccountId, String email, String returnUrl, String refreshUrl) {
            startedWith = existingAccountId;
            return onboarding;
        }

        @Override
        public Status status(String accountId) {
            return status;
        }
    }

    private ObjectNode creator(UUID owner, String accountId, boolean payoutsEnabled) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("id", CREATOR.toString());
        c.put("brandId", owner.toString());
        c.put("email", "creator@example.com");
        if (accountId != null) c.put("stripeAccountId", accountId);
        c.put("payoutsEnabled", payoutsEnabled);
        return c;
    }

    private CreatorPayoutOnboardingService service(StubDao dao, StubPort port) {
        return new CreatorPayoutOnboardingService(dao, new ResponseShapeService(MAPPER), port, "https://tejdux.com");
    }

    @Test
    @DisplayName("a deployment paying by hand is told nothing is broken, not given a 500")
    void unconfiguredIsAConflict() {
        StubPort port = new StubPort();
        port.configured = false;

        assertThatThrownBy(() -> service(new StubDao(creator(BRAND, null, false)), port).start(BRAND, CREATOR))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not enabled");
    }

    @Test
    @DisplayName("another brand's creator is not found, and the message does not confirm they exist")
    void refusesAnotherTenant() {
        assertThatThrownBy(() -> service(new StubDao(creator(OTHER, null, false)), new StubPort())
                .start(BRAND, CREATOR))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("the account id is stored before the URL is handed back")
    void accountIdIsPersistedFirst() {
        // If the creator completes onboarding and this write had not happened, the account would
        // exist at Stripe with nothing here pointing at it -- and the next attempt would make a
        // second one, splitting their payouts.
        StubDao dao = new StubDao(creator(BRAND, null, false));

        JsonNode out = service(dao, new StubPort()).start(BRAND, CREATOR);

        assertThat(dao.patches()).hasSize(1);
        assertThat(dao.patches().get(0).get("stripeAccountId").asText()).isEqualTo("acct_new");
        assertThat(out.get("onboardingUrl").asText()).startsWith("https://connect.stripe.com/");
    }

    @Test
    @DisplayName("resuming reuses the existing account rather than creating a second")
    void resumeReusesTheAccount() {
        // The common case, not the exception: identity checks stall and bank details get mistyped,
        // and a creator returning tomorrow needs a fresh link to the SAME account.
        StubDao dao = new StubDao(creator(BRAND, "acct_existing", false));
        StubPort port = new StubPort();
        port.onboarding = new CreatorPayoutOnboardingPort.Onboarding("acct_existing", "https://connect.stripe.com/y");

        service(dao, port).start(BRAND, CREATOR);

        assertThat(port.startedWith).isEqualTo("acct_existing");
        // And nothing is written, because nothing changed.
        assertThat(dao.patches()).isEmpty();
    }

    @Test
    @DisplayName("an account that exists does NOT mean the creator can be paid")
    void accountExistingIsNotPayable() {
        // The distinction section 11.5 is about. Days separate these two in practice.
        StubDao dao = new StubDao(creator(BRAND, "acct_existing", false));
        StubPort port = new StubPort();
        port.status = new CreatorPayoutOnboardingPort.Status("acct_existing", false, "individual.id_number");

        JsonNode out = service(dao, port).refreshStatus(BRAND, CREATOR);

        assertThat(out.get("payoutsEnabled").asBoolean()).isFalse();
        // And it says what is outstanding, so "why not" does not require logging into Stripe.
        assertThat(out.get("detail").asText()).contains("id_number");
    }

    @Test
    @DisplayName("an unreadable status leaves the stored value alone rather than writing false")
    void unknownIsNotNotPayable() {
        // "We could not ask" and "they cannot be paid" are different facts. Overwriting the second
        // with the first would tell a brand their creator had gone backwards.
        StubDao dao = new StubDao(creator(BRAND, "acct_existing", true));
        StubPort port = new StubPort();
        port.status = null;

        JsonNode out = service(dao, port).refreshStatus(BRAND, CREATOR);

        assertThat(dao.patches()).isEmpty();
        assertThat(out.get("payoutsEnabled").asBoolean()).isTrue();
        assertThat(out.get("stale").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a creator who never started is reported as such, not as a failure")
    void notStartedIsNotAnError() {
        JsonNode out = service(new StubDao(creator(BRAND, null, false)), new StubPort())
                .refreshStatus(BRAND, CREATOR);

        assertThat(out.get("payoutsEnabled").asBoolean()).isFalse();
        assertThat(out.get("detail").asText()).contains("not been started");
    }

    @Test
    @DisplayName("a successful status read is stored with the time it was read")
    void statusIsStoredWithItsTimestamp() {
        // A cached boolean with no timestamp is a number nobody can judge.
        StubDao dao = new StubDao(creator(BRAND, "acct_existing", false));

        service(dao, new StubPort()).refreshStatus(BRAND, CREATOR);

        assertThat(dao.patches()).hasSize(1);
        assertThat(dao.patches().get(0).has("payoutStatusCheckedAt")).isTrue();
    }
}
