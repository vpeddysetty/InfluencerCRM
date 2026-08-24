package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.BriefEnricher;
import com.influencer.webe.content.application.CampaignPageGenerationService;
import com.influencer.webe.content.application.PageGeneratorRegistry;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.shared.application.RequestUserResolver;
import com.influencer.webe.shared.application.ResponseShapeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code /api/campaign-pages/generate}.
 *
 * <p><b>Why a slice test in a module that otherwise has none.</b> Every other test here is a plain
 * unit test, deliberately — they are fast and need no context. But three things about this endpoint
 * are decided by Spring rather than by any class under test: that the path is mapped at all, that
 * the request body binds to an {@code ObjectNode}, and that a {@link ResponseStatusException} from
 * the service becomes that status on the wire rather than a 500. A unit test calling the controller
 * method directly asserts none of those — which is the gap that lets a working service sit behind a
 * route nobody can reach.
 *
 * <p><b>No Mockito, deliberately.</b> Nothing else in this module mocks, and the JDK here is newer
 * than the Byte Buddy that Mockito ships with — {@code @MockBean} fails to load the context with
 * "Java 26 is not supported". Rather than add a {@code net.bytebuddy.experimental} flag to the whole
 * build for one test, the two collaborators are hand-written below. They are also more legible:
 * each records exactly what this test needs to assert and nothing else.
 *
 * <p><b>Security filters are off.</b> This test is about routing and binding. The endpoint's real
 * protection is {@code anyRequest().authenticated()} in {@code SecurityConfig} — config this slice
 * would only re-assert — plus the {@code requireTenantContext} call verified below, which checks the
 * controller refuses to do any work when the resolver rejects the caller.
 */
@WebMvcTest(controllers = CampaignPageGenerationController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        // A @WebMvcTest slice keeps @Configuration classes and servlet Filters but drops @Service
        // beans — so SecurityConfig and JwtAuthenticationFilter are both registered while the
        // JwtService they need is not, and the context fails to start. Both are filtered out
        // because this slice exists to answer a routing question. What the endpoint is actually
        // protected BY is SecurityConfig's `anyRequest().authenticated()`, which belongs to that
        // class's own coverage rather than to a test of one controller.
        //
        // The two filters below are the complete set of @Component servlet filters in the BFF. If
        // a third is ever added this test fails at context load with "No qualifying bean" naming
        // that filter's dependency — add it here; the failure is not about this endpoint.
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        com.influencer.webe.security.SecurityConfig.class,
                        com.influencer.webe.security.JwtAuthenticationFilter.class,
                        com.influencer.webe.shared.workload.CallerIdentityFilter.class,
                }))
@AutoConfigureMockMvc(addFilters = false)
@Import(CampaignPageGenerationControllerTest.Collaborators.class)
class CampaignPageGenerationControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingGenerationService generation;

    @Autowired
    private StubRequestUserResolver resolver;

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        generation.reset();
        resolver.reset();
    }

    private String briefJson() {
        ObjectNode brief = MAPPER.createObjectNode();
        brief.put("goal", "Launch the winter trail collection");
        brief.put("offer", "15% off the first order");
        return brief.toString();
    }

    @Test
    @DisplayName("the route is mapped and returns the drafts the service produced")
    void generateReturnsDrafts() throws Exception {
        mockMvc.perform(post("/api/campaign-pages/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(briefJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generator").value("template"))
                .andExpect(jsonPath("$.fallback").value(false))
                .andExpect(jsonPath("$.variants[0].headline").value("Your trail, upgraded"));
    }

    @Test
    @DisplayName("the request body reaches the service as the brief the caller sent")
    void requestBodyBindsToTheBrief() throws Exception {
        // Binding is what a direct method call cannot check: a controller missing @RequestBody, or
        // a body Spring cannot deserialize into ObjectNode, fails only over HTTP.
        mockMvc.perform(post("/api/campaign-pages/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(briefJson()))
                .andExpect(status().isOk());

        ObjectNode received = generation.lastBrief();
        assertEquals("Launch the winter trail collection", received.get("goal").asText());
        assertEquals("15% off the first order", received.get("offer").asText());
    }

    @Test
    @DisplayName("a brief the service rejects comes back as 400, not 500")
    void rejectedBriefSurfacesAsBadRequest() throws Exception {
        // The service throws ResponseStatusException; only the framework turns that into a status.
        // If this regressed, callers would see a 500 for their own malformed input.
        generation.failWith(new ResponseStatusException(HttpStatus.BAD_REQUEST, "goal is required"));

        mockMvc.perform(post("/api/campaign-pages/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unauthenticated caller is refused before any model spend")
    void unauthenticatedCallerNeverReachesTheGenerator() throws Exception {
        // The ordering matters commercially, not only for correctness: generation costs money per
        // call, so the tenant check has to run before the service, not alongside it.
        resolver.rejectCallers();

        mockMvc.perform(post("/api/campaign-pages/generate")
                        .header("Authorization", "Bearer nonsense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(briefJson()))
                .andExpect(status().isUnauthorized());

        assertEquals(0, generation.calls(),
                "the generator must not run for a caller the resolver rejected");
    }

    @Test
    @DisplayName("the endpoint is POST-only")
    void getIsNotAllowed() throws Exception {
        // Generation costs money and is not idempotent. A GET would also be cacheable and would put
        // the brief in a URL, where it lands in access logs.
        mockMvc.perform(get("/api/campaign-pages/generate"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---- hand-written collaborators ------------------------------------

    @TestConfiguration
    static class Collaborators {

        @Bean
        @Primary
        RecordingGenerationService generation() {
            return new RecordingGenerationService();
        }

        @Bean
        @Primary
        StubRequestUserResolver resolver() {
            return new StubRequestUserResolver();
        }
    }

    /** Records what the controller passed, and returns a fixed payload — or throws on demand. */
    static class RecordingGenerationService extends CampaignPageGenerationService {

        private final AtomicReference<ObjectNode> lastBrief = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile RuntimeException failure;

        RecordingGenerationService() {
            // The real superclass needs a registry and a shaper. Passing an empty registry is safe
            // because generate() is overridden and never reaches it.
            super(new PageGeneratorRegistry(List.of(), "template"),
                    new ResponseShapeService(MAPPER), new BriefEnricher(null));
        }

        @Override
        public JsonNode generate(ObjectNode payload) {
            calls.incrementAndGet();
            lastBrief.set(payload);
            if (failure != null) {
                throw failure;
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("generator", "template");
            response.put("fallback", false);
            ObjectNode variant = response.putArray("variants").addObject();
            variant.put("id", "variant_a");
            variant.put("score", 88);
            variant.put("headline", "Your trail, upgraded");
            return response;
        }

        void failWith(RuntimeException e) {
            this.failure = e;
        }

        ObjectNode lastBrief() {
            return lastBrief.get();
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            failure = null;
            calls.set(0);
            lastBrief.set(null);
        }
    }

    /** Accepts every caller, until a test says otherwise. */
    static class StubRequestUserResolver extends RequestUserResolver {

        private volatile boolean reject;

        StubRequestUserResolver() {
            // The verifier is never consulted: requireTenantContext is overridden below.
            super(null);
        }

        @Override
        public TenantContext requireTenantContext(String authorizationHeader) {
            if (reject) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no session");
            }
            // A minimal but valid context. The controller only needs the call to succeed — it reads
            // nothing off the result, because a brief carries no tenant data.
            return new TenantContext(
                    java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID(),
                    "owner@example.com",
                    com.influencer.webe.security.AccountRole.OWNER,
                    java.util.Set.of(),
                    java.util.Set.of());
        }

        void rejectCallers() {
            this.reject = true;
        }

        void reset() {
            this.reject = false;
        }
    }
}
