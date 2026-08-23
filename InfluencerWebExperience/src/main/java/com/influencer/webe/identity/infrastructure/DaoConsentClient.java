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
        return record(subjectType, subjectId, subjectEmail, consentType, documentVersion, source,
                ipAddress, userAgent, metadataJson, null, null, null);
    }

    /**
     * As above, plus the evidence of what the subject was actually shown.
     *
     * <p>The three evidence arguments are all optional and are omitted from the payload when
     * absent, which is the honest encoding of "this was captured before evidence capture existed"
     * or "the snapshot could not be taken". A caller must not substitute a placeholder: the DAO
     * rejects a malformed hash, and an invented one would read as evidence until checked.
     */
    public JsonNode record(String subjectType,
                           UUID subjectId,
                           String subjectEmail,
                           String consentType,
                           String documentVersion,
                           String source,
                           String ipAddress,
                           String userAgent,
                           String metadataJson,
                           String documentUrl,
                           String documentSha256,
                           String evidenceS3Key) {
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
        if (documentUrl != null && !documentUrl.isBlank()) {
            body.put("documentUrl", documentUrl);
        }
        if (documentSha256 != null && !documentSha256.isBlank()) {
            body.put("documentSha256", documentSha256);
        }
        if (evidenceS3Key != null && !evidenceS3Key.isBlank()) {
            body.put("evidenceS3Key", evidenceS3Key);
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
