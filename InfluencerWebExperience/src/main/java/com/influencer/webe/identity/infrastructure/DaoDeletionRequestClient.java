package com.influencer.webe.identity.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Records and advances deletion requests — the audit trail behind {@code /data-deletion/}. */
@Component
public class DaoDeletionRequestClient {

    private final DaoGatewayClient gatewayClient;

    public DaoDeletionRequestClient(DaoGatewayClient gatewayClient) {
        this.gatewayClient = gatewayClient;
    }

    /**
     * Records an arriving request.
     *
     * <p>{@code rawMessageS3Key} doubles as the idempotency key: SNS delivers at least once, and
     * the DAO returns the existing row rather than creating a second one for a redelivery. Two rows
     * would mean two approval links, each authorising an irreversible act on the same person.
     */
    public JsonNode record(String subjectEmail,
                           UUID subjectUserId,
                           String scope,
                           String provider,
                           String rawMessageS3Key,
                           String intakeSource,
                           String approvalTokenHash,
                           Instant approvalExpiresAt) {
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("subjectEmail", subjectEmail)
                .put("scope", scope)
                .put("intakeSource", intakeSource);
        if (subjectUserId != null) {
            body.put("subjectUserId", subjectUserId.toString());
        }
        if (provider != null && !provider.isBlank()) {
            body.put("provider", provider);
        }
        if (rawMessageS3Key != null && !rawMessageS3Key.isBlank()) {
            body.put("rawMessageS3Key", rawMessageS3Key);
        }
        if (approvalTokenHash != null) {
            body.put("approvalTokenHash", approvalTokenHash);
            if (approvalExpiresAt != null) {
                body.put("approvalExpiresAt", approvalExpiresAt.toString());
            }
        }
        return gatewayClient.post("/deletion-requests", body);
    }

    /** Finds the request an approval link refers to, by the hash of its token. */
    public JsonNode byApprovalTokenHash(String tokenHash) {
        return gatewayClient.get("/deletion-requests/by-approval-token",
                Map.of("tokenHash", tokenHash));
    }

    public JsonNode byId(UUID id) {
        return gatewayClient.get("/deletion-requests/" + id, Map.of());
    }

    public JsonNode byEmail(String email) {
        return gatewayClient.get("/deletion-requests/by-email", Map.of("email", email));
    }

    /** The operator queue. */
    public JsonNode open() {
        return gatewayClient.get("/deletion-requests/open", Map.of());
    }

    /** Marks a request approved. The DAO refuses this without an approver named. */
    public JsonNode approve(UUID id, String approvedBy, Instant when) {
        return gatewayClient.patch("/deletion-requests/" + id,
                JsonNodeFactory.instance.objectNode()
                        .put("approvedAt", when.toString())
                        .put("approvedBy", approvedBy));
    }

    /** Marks a request complete. The DAO refuses this unless it was approved first. */
    public JsonNode complete(UUID id, String outcomeNote, Instant when) {
        return gatewayClient.patch("/deletion-requests/" + id,
                JsonNodeFactory.instance.objectNode()
                        .put("completedAt", when.toString())
                        .put("outcomeNote", outcomeNote));
    }

    /** Records a refusal, which is an outcome rather than an error. */
    public JsonNode refuse(UUID id, String reason, Instant when) {
        return gatewayClient.patch("/deletion-requests/" + id,
                JsonNodeFactory.instance.objectNode()
                        .put("refusedAt", when.toString())
                        .put("refusedReason", reason));
    }

    public JsonNode markOperatorNotified(UUID id, Instant when) {
        return gatewayClient.patch("/deletion-requests/" + id,
                JsonNodeFactory.instance.objectNode()
                        .put("operatorNotifiedAt", when.toString()));
    }

    public JsonNode markRequesterNotified(UUID id, Instant when) {
        return gatewayClient.patch("/deletion-requests/" + id,
                JsonNodeFactory.instance.objectNode()
                        .put("requesterNotifiedAt", when.toString()));
    }

    public JsonNode acknowledge(UUID id, Instant when) {
        return gatewayClient.patch("/deletion-requests/" + id,
                JsonNodeFactory.instance.objectNode()
                        .put("acknowledgedAt", when.toString()));
    }
}
