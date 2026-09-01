package com.influencer.webe.campaign.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.campaign.infrastructure.AgentMappingClient;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The column-mapping route may only read a batch the caller owns (roadmap OP-26).
 *
 * <p><b>Why this is worth its own test.</b> The endpoint was the one route in its controller that
 * read a batch without proving ownership, and the omission cost two things at once: another
 * tenant's column headers came back in the response, and the mapping call behind it spends OpenAI
 * budget, so an unowned id billed us in order to leak. Both are asserted below — the 403, and that
 * the agent is never called on the refused path, because a fix that returned 403 *after* paying for
 * the mapping would look correct and still be wrong.
 */
class ImportBatchOwnershipTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID CALLER_BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_BRAND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BATCH = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** A DAO that serves one batch, owned by whichever brand the test names. */
    private static class StubDao extends DaoGatewayClient {

        private final Map<String, JsonNode> responses = new LinkedHashMap<>();
        private final List<String> reads = new ArrayList<>();

        StubDao() {
            // The real constructor calls httpClientFactory.create(); get() is overridden so no
            // request is ever made. Same shape as the other stubs in this module.
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    return null;
                }
            }, null);
        }

        StubDao owning(UUID ownerBrandId) {
            responses.put("/import-batches/" + BATCH,
                    MAPPER.createObjectNode().put("id", BATCH.toString())
                            .put("brandId", ownerBrandId.toString()));
            responses.put("/import-batches/" + BATCH + "/columns",
                    MAPPER.createObjectNode().put("sourceFilename", "roster.xlsx")
                            .set("columns", MAPPER.createArrayNode().add("IG Handle").add("Followers")));
            return this;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            reads.add(path);
            return responses.get(path);
        }

        List<String> reads() {
            return reads;
        }
    }

    /** Resolves every caller to one brand; the test varies who OWNS the batch instead. */
    private static class StubResolver extends RequestUserResolver {

        StubResolver() {
            super(null);
        }

        @Override
        public UUID resolveBrandId(String authorizationHeader, UUID explicitBrandId) {
            return CALLER_BRAND;
        }
    }

    /** Records whether the billed mapping call was made. */
    private static class CountingAgent extends AgentMappingClient {

        private int calls;

        CountingAgent() {
            super(null, MAPPER);
        }

        @Override
        public JsonNode mapColumns(List<String> spreadsheetColumns) {
            calls++;
            return MAPPER.createObjectNode().put("mapped", true);
        }

        int calls() {
            return calls;
        }
    }

    private ImportBatchesController controller(StubDao dao, CountingAgent agent) {
        return new ImportBatchesController(dao, agent, new StubResolver(),
                new ResponseShapeService(MAPPER));
    }

    @Test
    @DisplayName("a batch belonging to another brand is refused with 403")
    void refusesAnotherTenantsBatch() {
        StubDao dao = new StubDao().owning(OTHER_BRAND);
        CountingAgent agent = new CountingAgent();

        assertThatThrownBy(() -> controller(dao, agent).generateAgentColumnMapping(null, null, BATCH))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("the refused path never reaches the columns, so no headers leak")
    void refusalDoesNotReadTheColumns() {
        StubDao dao = new StubDao().owning(OTHER_BRAND);

        assertThatThrownBy(() -> controller(dao, new CountingAgent())
                .generateAgentColumnMapping(null, null, BATCH))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(dao.reads()).doesNotContain("/import-batches/" + BATCH + "/columns");
    }

    @Test
    @DisplayName("the refused path never spends the AI budget")
    void refusalDoesNotCallTheAgent() {
        CountingAgent agent = new CountingAgent();

        assertThatThrownBy(() -> controller(new StubDao().owning(OTHER_BRAND), agent)
                .generateAgentColumnMapping(null, null, BATCH))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(agent.calls()).isZero();
    }

    @Test
    @DisplayName("the owner still gets their mapping")
    void ownerIsUnaffected() {
        CountingAgent agent = new CountingAgent();

        JsonNode result = controller(new StubDao().owning(CALLER_BRAND), agent)
                .generateAgentColumnMapping(null, null, BATCH);

        assertThat(result.path("importBatchId").asText()).isEqualTo(BATCH.toString());
        assertThat(result.path("mapping").path("mapped").asBoolean()).isTrue();
        assertThat(agent.calls()).isEqualTo(1);
    }
}
