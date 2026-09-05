package com.influencer.webe.identity.infrastructure;

import com.influencer.webe.shared.infrastructure.DaoGatewayClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.security.AccountRole;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the tenancy spine from the DAO: which brands a user may reach, and with what role.
 *
 * <p>This is the authority behind the JWT's brand claims and behind every {@code X-Brand-Id}
 * validation, so it deliberately has no caching — a revoked membership must take effect on the next
 * login rather than whenever a cache happens to expire.
 */
@Component
public class DaoTenancyClient implements com.influencer.webe.identity.application.BrandAccessPort {

    private final DaoGatewayClient gatewayClient;

    public DaoTenancyClient(DaoGatewayClient gatewayClient) {
        this.gatewayClient = gatewayClient;
    }

    public List<BrandAccess> findAccessibleBrands(UUID userId) {
        JsonNode response = gatewayClient.get("/tenancy/users/" + userId + "/brands", null);
        List<BrandAccess> brands = new ArrayList<>();
        if (response == null || !response.isArray()) {
            return brands;
        }
        for (JsonNode node : response) {
            AccountRole role = AccountRole.parse(text(node, "effectiveRole"))
                    .orElse(AccountRole.parse(text(node, "accountRole")).orElse(null));
            if (role == null) {
                // A row we cannot map to a known role must not silently become a permissive
                // default; skipping it fails closed.
                continue;
            }
            brands.add(new BrandAccess(
                    UUID.fromString(text(node, "brandId")),
                    text(node, "brandName"),
                    UUID.fromString(text(node, "accountId")),
                    text(node, "accountType"),
                    role));
        }
        return brands;
    }

    public List<JsonNode> brandsForAccount(UUID accountId) {
        JsonNode response = gatewayClient.get("/tenancy/accounts/" + accountId + "/brands", null);
        List<JsonNode> brands = new ArrayList<>();
        if (response != null && response.isArray()) {
            response.forEach(brands::add);
        }
        return brands;
    }

    public JsonNode createBrand(UUID accountId, String name) {
        return gatewayClient.post("/tenancy/brands", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode()
                .put("accountId", accountId.toString())
                .put("name", name));
    }

    /**
     * Renames an existing brand.
     *
     * <p>Used by the post-social-signup onboarding step. A federated signup has no brand field to
     * fill in before the redirect, so the workspace is provisioned named after the person's provider
     * display name — "Ari Rivera" rather than their company. This is what corrects it once they say
     * what the workspace is actually called.
     *
     * <p>Sends only the name: the DAO's PUT leaves status and custom attributes untouched when they
     * are absent, so a rename cannot silently reset the rest of the record.
     */
    public JsonNode renameBrand(UUID brandId, String name) {
        return gatewayClient.put("/tenancy/brands/" + brandId,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("name", name));
    }

    public JsonNode members(UUID accountId) {
        return gatewayClient.get("/tenancy/accounts/" + accountId + "/members", Map.of());
    }

    // ------------------------------------------------------------------ invitations

    public JsonNode createInvitation(UUID accountId, String email, String role, UUID brandId,
                                     String tokenHash, UUID invitedBy, Instant expiresAt) {
        var body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("email", email)
                .put("role", role)
                .put("tokenHash", tokenHash)
                .put("invitedBy", invitedBy == null ? null : invitedBy.toString())
                .put("expiresAt", expiresAt.toString());
        if (brandId != null) {
            body.put("brandId", brandId.toString());
        }
        return gatewayClient.post("/tenancy/accounts/" + accountId + "/invitations", body);
    }

    public JsonNode invitations(UUID accountId) {
        return gatewayClient.get("/tenancy/accounts/" + accountId + "/invitations", Map.of());
    }

    public JsonNode invitationByTokenHash(String tokenHash) {
        return gatewayClient.get("/tenancy/invitations/by-token/" + tokenHash, null);
    }

    /** One invitation by id, so a caller can check which account it belongs to before acting. */
    public JsonNode invitation(UUID invitationId) {
        return gatewayClient.get("/tenancy/invitations/" + invitationId, null);
    }

    /** Replaces an invitation's token, invalidating the previous one. */
    public JsonNode rotateInvitationToken(UUID invitationId, String tokenHash, Instant expiresAt) {
        return gatewayClient.post("/tenancy/invitations/" + invitationId + "/rotate-token",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("tokenHash", tokenHash)
                        .put("expiresAt", expiresAt.toString()));
    }

    public JsonNode acceptInvitation(UUID invitationId, UUID userId) {
        return gatewayClient.post("/tenancy/invitations/" + invitationId + "/accept",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("userId", userId.toString()));
    }

    public JsonNode revokeInvitation(UUID invitationId) {
        return gatewayClient.post("/tenancy/invitations/" + invitationId + "/revoke",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
    }

    public JsonNode updateMemberRole(UUID accountId, UUID userId, String role) {
        return gatewayClient.put("/tenancy/accounts/" + accountId + "/members/" + userId,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("role", role));
    }

    public void removeMember(UUID accountId, UUID userId) {
        gatewayClient.delete("/tenancy/accounts/" + accountId + "/members/" + userId);
    }

    /**
     * Provisions a workspace for a newly created user.
     *
     * <p>One call rather than three so the account, brand and membership share a transaction in the
     * DAO. A user with an account but no membership is a tenant nothing can serve, and splitting
     * the writes across HTTP calls would make that state reachable on any partial failure.
     */
    public Provisioned provisionWorkspace(UUID userId, String workspaceName, String accountType, String role) {
        JsonNode response = gatewayClient.post("/tenancy/provision",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("userId", userId.toString())
                        .put("workspaceName", workspaceName)
                        .put("accountType", accountType)
                        .put("role", role));
        if (response == null || response.get("accountId") == null) {
            throw new IllegalStateException("Tenancy provisioning returned no account");
        }
        return new Provisioned(
                UUID.fromString(text(response, "accountId")),
                UUID.fromString(text(response, "brandId")),
                text(response, "accountType"),
                text(response, "role"));
    }

    /**
     * The account provisioned for a user.
     *
     * <p>Empty rather than throwing when there is none: the caller decides whether a missing
     * account is a failure, and at signup time it is.
     */
    public Optional<UUID> findAccountIdForUser(UUID userId) {
        JsonNode response = gatewayClient.get("/tenancy/users/" + userId + "/account", null);
        String id = response == null ? null : text(response, "id");
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(id));
    }

    /**
     * Sets an account's type after it has been provisioned.
     *
     * <p>Needed only because the provisioning trigger can create a {@code brand} account and
     * nothing else, so an agency signup creates then promotes. Disappears when provisioning moves
     * into the application (roadmap Stage 2).
     */
    public JsonNode promoteAccountType(UUID accountId, String accountType) {
        return gatewayClient.patch("/tenancy/accounts/" + accountId,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("accountType", accountType));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * The same answer, in the published port's type (roadmap PR-64).
     *
     * <p>A second method rather than a changed signature: {@code findAccessibleBrands} is called
     * from five places inside this context, and rewriting all of them to satisfy a boundary rule
     * they do not cross would be churn for its own sake. Both delegate to the one query, so there
     * is still exactly one definition of who sees what — which is the whole point of §5's warning.
     */
    @Override
    public java.util.List<com.influencer.webe.identity.application.BrandAccessPort.BrandAccess>
            findAccessibleBrandsForPort(UUID userId) {
        java.util.List<com.influencer.webe.identity.application.BrandAccessPort.BrandAccess> out =
                new ArrayList<>();
        for (BrandAccess b : findAccessibleBrands(userId)) {
            out.add(new com.influencer.webe.identity.application.BrandAccessPort.BrandAccess(
                    b.brandId(), b.brandName(), b.accountId(), b.accountType(), b.role()));
        }
        return out;
    }

    public record BrandAccess(
            UUID brandId,
            String brandName,
            UUID accountId,
            String accountType,
            AccountRole role) {
    }

    public record Provisioned(UUID accountId, UUID brandId, String accountType, String role) {
    }
}
