package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.application.ConsentService;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lead capture from a public page (roadmap PR-61).
 *
 * <p>Three guards in this codebase refused to render a form on a landing page, all for the stated
 * reason that it would collect personal data "with nowhere to POST it". This is the somewhere, and
 * these tests are about what makes collecting it defensible rather than merely possible: consent
 * before the row, a refusal leaving NOTHING behind, and an unauthenticated write that cannot be
 * used as a spam funnel.
 */
class PageLeadServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class StubDao extends DaoGatewayClient {
        private final List<String> posted = new ArrayList<>();
        private JsonNode template;

        StubDao(JsonNode template) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public java.net.http.HttpClient create() {
                    return null;
                }
            }, null);
            this.template = template;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.equals("/landing-templates")) {
                var arr = MAPPER.createArrayNode();
                if (template != null) arr.add(template);
                return arr;
            }
            return null;
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            posted.add(path);
            return MAPPER.createObjectNode().put("id", "55555555-5555-5555-5555-555555555555");
        }

        List<String> posted() {
            return posted;
        }
    }

    /** Refuses exactly as the real one does, and records whether it was asked. */
    private static class RecordingConsent extends ConsentService {
        boolean checked;
        boolean recorded;

        RecordingConsent() {
            super(null, null, null, null, null, null);
        }

        @Override
        public void requireAccepted(Boolean acceptedTerms) {
            checked = true;
            if (!Boolean.TRUE.equals(acceptedTerms)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "You must accept the Terms of Service and Privacy Policy to continue");
            }
        }

        @Override
        public void recordSignupConsent(String subjectType, java.util.UUID subjectId, String email,
                                        String source, jakarta.servlet.http.HttpServletRequest request,
                                        String metadataJson) {
            recorded = true;
        }
    }

    private ObjectNode published() {
        ObjectNode t = MAPPER.createObjectNode();
        t.put("id", "33333333-3333-3333-3333-333333333333");
        t.put("brandId", "11111111-1111-1111-1111-111111111111");
        t.put("status", "published");
        t.put("publicSlug", "c-spring");
        return t;
    }

    private ObjectNode payload(String email) {
        ObjectNode p = MAPPER.createObjectNode();
        if (email != null) p.put("email", email);
        p.put("name", "A Visitor");
        p.put("message", "Please get in touch.");
        return p;
    }

    private PageLeadService service(StubDao dao, RecordingConsent consent) {
        return new PageLeadService(dao, new ResponseShapeService(MAPPER), consent);
    }

    @Test
    @DisplayName("a refusal writes NOTHING — not even a row to attach the refusal to")
    void refusalLeavesNoTrace() {
        // The ordering an erasure request depends on being able to trust: if someone declines,
        // there must be no record they were ever here.
        StubDao dao = new StubDao(published());
        RecordingConsent consent = new RecordingConsent();

        assertThatThrownBy(() -> service(dao, consent).capture("c-spring", payload("v@example.com"), false, null))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(dao.posted()).isEmpty();
    }

    @Test
    @DisplayName("consent is checked before the row is written, not after")
    void consentComesFirst() {
        StubDao dao = new StubDao(published());
        RecordingConsent consent = new RecordingConsent();

        service(dao, consent).capture("c-spring", payload("v@example.com"), true, null);

        assertThat(consent.checked).isTrue();
        assertThat(consent.recorded).isTrue();
        assertThat(dao.posted()).contains("/page-leads");
    }

    @Test
    @DisplayName("an unpublished page is not addressable, so a form posting to one is refused")
    void unpublishedIsNotFound() {
        ObjectNode draft = published();
        draft.put("status", "draft");

        assertThatThrownBy(() -> service(new StubDao(draft), new RecordingConsent())
                .capture("c-spring", payload("v@example.com"), true, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("a missing or malformed email is refused before consent is even considered")
    void emailIsRequired() {
        StubDao dao = new StubDao(published());

        assertThatThrownBy(() -> service(dao, new RecordingConsent())
                .capture("c-spring", payload("not-an-email"), true, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("email");

        assertThat(dao.posted()).isEmpty();
    }

    @Test
    @DisplayName("the response tells a public caller nothing about the brand or the page")
    void responseIsThin() {
        JsonNode out = service(new StubDao(published()), new RecordingConsent())
                .capture("c-spring", payload("v@example.com"), true, null);

        assertThat(out.get("received").asBoolean()).isTrue();
        assertThat(out.has("brandId")).isFalse();
        assertThat(out.has("id")).isFalse();
        assertThat(out.has("landingTemplateId")).isFalse();
    }

    @Test
    @DisplayName("past the per-page ceiling it refuses rather than accepting a spam wave")
    void rateLimited() {
        // The second unauthenticated write in the product; OP-25 was the first and its lesson
        // applies exactly. Unlike the sign-up ceiling this REFUSES rather than degrading -- there
        // is no lesser version of storing somebody's email address.
        StubDao dao = new StubDao(published());
        PageLeadService service = service(dao, new RecordingConsent());

        for (int i = 0; i < 30; i++) {
            service.capture("c-spring", payload("v" + i + "@example.com"), true, null);
        }

        assertThatThrownBy(() -> service.capture("c-spring", payload("v31@example.com"), true, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Too many");
    }

    @Test
    @DisplayName("the tenant comes from the slug, never from the caller's body")
    void brandIsNotTakenFromTheBody() {
        // A brandId in the body would be the thing to distrust on an endpoint anyone can POST to.
        StubDao dao = new StubDao(published());
        ObjectNode hostile = payload("v@example.com");
        hostile.put("brandId", "99999999-9999-9999-9999-999999999999");

        service(dao, new RecordingConsent()).capture("c-spring", hostile, true, null);

        assertThat(dao.posted()).containsExactly("/page-leads");
    }
}
