package com.influencer.dps.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consent checkbox has to survive the hop through this service.
 *
 * <p>It did not, and email-and-password signup was broken in production as a result. The UI sent
 * {@code acceptedTerms}; {@link SessionController.SignupRequest} had no such component, so its
 * {@code @JsonAnySetter} guard threw {@code "Unrecognised signup field: acceptedTerms"}; and a
 * caller that omitted the field instead reached the BFF with no consent and was refused with
 * {@code "You must accept the Terms of Service and Privacy Policy to continue"}. Either way there
 * was no route through. The social sign-up paths were threaded when consent capture landed; the
 * primary one was missed.
 */
class SignupConsentForwardingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("the signup payload accepts the consent flag rather than rejecting it as unknown")
    void consentIsAnAcceptedField() {
        String json = """
                {"email":"someone@example.com","password":"secret","brandName":"Acme",
                 "accountType":"brand","acceptedTerms":true}
                """;

        SessionController.SignupRequest request = assertDoesNotThrow(
                () -> mapper.readValue(json, SessionController.SignupRequest.class),
                "acceptedTerms must deserialize; the @JsonAnySetter guard used to throw on it");

        assertEquals(Boolean.TRUE, request.acceptedTerms());
    }

    @Test
    @DisplayName("genuinely unknown fields are still rejected")
    void unknownFieldsStillThrow() {
        // The guard exists so an unsupported property is refused here rather than silently dropped,
        // which would hand the caller a 200 for a request the platform does not support. Adding a
        // field must not weaken that.
        String json = """
                {"email":"someone@example.com","password":"secret","somethingElse":"x"}
                """;

        assertTrue(
                assertDoesNotThrow(() -> {
                    try {
                        mapper.readValue(json, SessionController.SignupRequest.class);
                        return false;
                    } catch (Exception e) {
                        return e.getMessage() != null && e.getMessage().contains("somethingElse");
                    }
                }),
                "an unrecognised field must still be rejected by name");
    }

    @Test
    @DisplayName("the flag and the client that gave it are forwarded to the BFF")
    void consentAndClientAreForwarded() throws IOException {
        String client = Files.readString(
                Path.of("src/main/java/com/influencer/dps/identity/IdentityClient.java"),
                StandardCharsets.UTF_8);

        assertTrue(client.contains("body.put(\"acceptedTerms\", acceptedTerms)"),
                "the consent flag must reach the BFF, which owns the rule");

        // Without these the call is just another server-to-server request, so the BFF would read its
        // own peer address and every consent record would name the DPS container as the party that
        // agreed. A record naming the wrong client is worse than no record: it asserts something
        // untrue.
        assertTrue(client.contains("X-Forwarded-For"),
                "the browser's address must be forwarded, or consent is attributed to this container");
        assertTrue(client.contains("User-Agent"),
                "the browser's user agent must be forwarded for the same reason");
    }

    @Test
    @DisplayName("header forwarding cannot smuggle credentials")
    void forwardedHeadersAreAllowListed() throws IOException {
        String client = Files.readString(
                Path.of("src/main/java/com/influencer/dps/identity/IdentityClient.java"),
                StandardCharsets.UTF_8);

        // HttpRequest.Builder#header APPENDS rather than replaces, so a pass-through would let a
        // caller add a second Authorization or service-token header beside the real one.
        assertTrue(
                client.contains("\"X-Forwarded-For\".equalsIgnoreCase(name)")
                        && client.contains("\"User-Agent\".equalsIgnoreCase(name)"),
                "only the two client-describing headers may be forwarded");
    }
}
