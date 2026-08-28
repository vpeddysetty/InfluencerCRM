package com.influencer.webe.identity.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.CreatorDirectory;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads and writes creator logins and their per-brand links (roadmap Stage 4). */
@Component
public class DaoCreatorIdentityClient implements CreatorDirectory {

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

    /**
     * One creator identity, projected to the fields a caller may see.
     *
     * <p><b>The projection is the point.</b> {@code GET /creator-identities/{id}} returns the whole
     * JPA entity, {@code passwordHash} included — so returning the DAO's answer unchanged would
     * spread a credential hash into every caller, and eventually into a response body or a log.
     * Only id, email and displayName leave this method, and a new field has to be added here
     * deliberately rather than appearing because the entity grew one.
     *
     * <p>Empty when the identity does not exist, which a caller must treat as "no such creator"
     * rather than as an error: it is the ordinary answer for a deleted account.
     */
    public Optional<JsonNode> findById(UUID identityId) {
        JsonNode found;
        try {
            found = gatewayClient.get("/creator-identities/" + identityId, Map.of());
        } catch (RuntimeException e) {
            // The DAO throws on a miss. Not found is not a fault.
            return Optional.empty();
        }
        if (found == null || !found.hasNonNull("id")) {
            return Optional.empty();
        }
        ObjectNode safe = JsonNodeFactory.instance.objectNode();
        safe.put("id", found.get("id").asText());
        safe.put("email", found.path("email").asText(""));
        if (found.hasNonNull("displayName")) {
            safe.put("displayName", found.get("displayName").asText());
        }
        return Optional.of(safe);
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

    /**
     * Approve or refuse a pending claim.
     *
     * @param brandId the caller's brand, from the verified token. Sent so the DAO can refuse a link
     *                belonging to another brand — see OP-18. It is deliberately a required argument
     *                rather than an optional one: the whole defect was that this call could be made
     *                without saying whose claim it was, and an overload without it would let the
     *                unscoped version come back.
     */
    public JsonNode decide(UUID linkId, UUID brandId, String status, UUID decidedByUserId) {
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("status", status)
                .put("brandId", brandId.toString());
        if (decidedByUserId != null) {
            body.put("decidedByUserId", decidedByUserId.toString());
        }
        return gatewayClient.post("/creator-identities/links/" + linkId + "/decision", body);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reshapes {@link #findById(UUID)}'s projection rather than reading again, so there is
     * exactly one place that decides what may leave this context.
     */
    @Override
    public Optional<Creator> lookupCreator(UUID creatorIdentityId) {
        return findById(creatorIdentityId).map(node -> new Creator(
                UUID.fromString(node.get("id").asText()),
                node.path("email").asText(null),
                node.path("displayName").asText(null)));
    }
}
