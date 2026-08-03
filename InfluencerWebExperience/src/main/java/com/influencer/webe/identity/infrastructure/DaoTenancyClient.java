package com.influencer.webe.identity.infrastructure;

import com.influencer.webe.shared.infrastructure.DaoGatewayClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.security.AccountRole;
import org.springframework.stereotype.Component;

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
public class DaoTenancyClient {

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

    public JsonNode members(UUID accountId) {
        return gatewayClient.get("/tenancy/accounts/" + accountId + "/members", Map.of());
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

    public record BrandAccess(
            UUID brandId,
            String brandName,
            UUID accountId,
            String accountType,
            AccountRole role) {
    }
}
