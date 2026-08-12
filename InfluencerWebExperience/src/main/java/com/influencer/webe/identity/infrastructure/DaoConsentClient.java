package com.influencer.webe.identity.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Appends and reads consent records — the evidence that someone accepted a legal document. */
@Component
public class DaoConsentClient {

    private final DaoGatewayClient gatewayClient;

    public DaoConsentClient(DaoGatewayClient gatewayClient) {
        this.gatewayClient = gatewayClient;
    }

    public JsonNode record(String subjectType,
                           UUID subjectId,
                           String subjectEmail,
                           String consentType,
                           String documentVersion,
                           String source,
                           String ipAddress,
                           String userAgent,
                           String metadataJson) {
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("subjectType", subjectType)
                .put("subjectEmail", subjectEmail)
                .put("consentType", consentType)
                .put("documentVersion", documentVersion)
                .put("granted", true)
                .put("source", source);
        // A lead has no account row, so subjectId is legitimately absent. Sending an explicit null
        // would be equivalent, but omitting it keeps the payload honest about what is unknown.
        if (subjectId != null) {
            body.put("subjectId", subjectId.toString());
        }
        if (ipAddress != null && !ipAddress.isBlank()) {
            body.put("ipAddress", ipAddress);
        }
        if (userAgent != null && !userAgent.isBlank()) {
            body.put("userAgent", userAgent);
        }
        if (metadataJson != null && !metadataJson.isBlank()) {
            body.put("metadata", metadataJson);
        }
        return gatewayClient.post("/consents", body);
    }

    /** One account's consent history, newest first. */
    public JsonNode bySubject(String subjectType, UUID subjectId) {
        return gatewayClient.get("/consents", Map.of(
                "subjectType", subjectType,
                "subjectId", subjectId.toString()));
    }

    /** Everything one email address ever agreed to — the subject-access-request query. */
    public JsonNode byEmail(String email) {
        return gatewayClient.get("/consents/by-email", Map.of("email", email));
    }
}
