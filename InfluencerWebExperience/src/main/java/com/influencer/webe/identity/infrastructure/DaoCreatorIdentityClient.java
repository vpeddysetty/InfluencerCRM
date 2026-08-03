package com.influencer.webe.identity.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads and writes creator logins and their per-brand links (roadmap Stage 4). */
@Component
public class DaoCreatorIdentityClient {

    private final DaoGatewayClient gatewayClient;

    public DaoCreatorIdentityClient(DaoGatewayClient gatewayClient) {
        this.gatewayClient = gatewayClient;
    }

    public JsonNode create(String email, String passwordHash, String displayName) {
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("email", email)
                .put("passwordHash", passwordHash)
                .put("displayName", displayName);
        return gatewayClient.post("/creator-identities", body);
    }

    /**
     * Empty rather than throwing when there is no such login.
     *
     * <p>"No account with this email" is the normal case on signup and an authentication failure on
     * login — the caller decides which, and a 404 propagating from here would make both look like
     * an outage.
     */
    public Optional<JsonNode> findByEmail(String email) {
        try {
            JsonNode response = gatewayClient.get("/creator-identities/by-email",
                    Map.of("email", email));
            return response == null || response.get("id") == null ? Optional.empty() : Optional.of(response);
        } catch (ResponseStatusException exception) {
            return Optional.empty();
        }
    }

    public JsonNode link(UUID identityId, UUID creatorId, UUID brandId, String status, UUID decidedBy) {
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("creatorId", creatorId.toString())
                .put("brandId", brandId.toString())
                .put("status", status);
        if (decidedBy != null) {
            body.put("confirmedByUserId", decidedBy.toString());
        }
        return gatewayClient.post("/creator-identities/" + identityId + "/links", body);
    }

    public JsonNode links(UUID identityId, String status) {
        return gatewayClient.get("/creator-identities/" + identityId + "/links",
                status == null ? Map.of() : Map.of("status", status));
    }

    public JsonNode pendingForBrand(UUID brandId) {
        return gatewayClient.get("/creator-identities/links/pending",
                Map.of("brandId", brandId.toString()));
    }

    public JsonNode decide(UUID linkId, String status, UUID decidedByUserId) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("status", status);
        if (decidedByUserId != null) {
            body.put("decidedByUserId", decidedByUserId.toString());
        }
        return gatewayClient.post("/creator-identities/links/" + linkId + "/decision", body);
    }
}
