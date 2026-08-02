package com.influencer.webe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.client.DaoGatewayClient;
import com.influencer.webe.marketplace.Capability;
import com.influencer.webe.marketplace.Connection;
import com.influencer.webe.marketplace.ConnectionResult;
import com.influencer.webe.marketplace.CouponSpec;
import com.influencer.webe.marketplace.ExternalCoupon;
import com.influencer.webe.marketplace.MarketplaceProvider;
import com.influencer.webe.marketplace.MarketplaceProviderRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the marketplace SPI for the BFF: lists available providers and
 * their capabilities, runs the connect handshake and persists the connection,
 * and pushes a coupon to a connected store through its adapter.
 *
 * SECURITY NOTE (Phase 6): credentials are currently serialized to JSON and
 * stored in {@code credentials_encrypted} as-is. Real envelope encryption is a
 * Phase 6 prerequisite before any real (Shopify) credentials flow through here.
 */
@Service
public class MarketplaceService {
    private final MarketplaceProviderRegistry registry;
    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public MarketplaceService(MarketplaceProviderRegistry registry,
                              DaoGatewayClient dao,
                              ResponseShapeService shape) {
        this.registry = registry;
        this.dao = dao;
        this.shape = shape;
    }

    /** Catalog of available providers + their capabilities for the UI. */
    public JsonNode listProviders() {
        ArrayNode out = shape.objectMapper().createArrayNode();
        for (MarketplaceProvider provider : registry.all()) {
            ObjectNode node = shape.objectMapper().createObjectNode();
            node.put("key", provider.key());
            node.put("displayName", provider.displayName());
            ArrayNode caps = node.putArray("capabilities");
            for (Capability capability : provider.capabilities()) {
                caps.add(capability.name());
            }
            out.add(node);
        }
        return out;
    }

    /**
     * Validate credentials with the provider, then persist a
     * {@code marketplace_connections} row. Returns the shaped connection.
     */
    public JsonNode connect(UUID brandId, String providerKey, Map<String, String> credentials) {
        MarketplaceProvider provider = registry.find(providerKey).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown marketplace provider: " + providerKey));

        ConnectionResult result = provider.connect(credentials);
        if (!result.isSuccess()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    result.getMessage() == null ? "Unable to connect to marketplace" : result.getMessage());
        }

        ObjectNode payload = shape.objectMapper().createObjectNode();
        payload.put("brandId", brandId.toString());
        payload.put("providerKey", provider.key());
        payload.put("displayName", result.getDisplayName() != null ? result.getDisplayName() : provider.displayName());
        payload.put("status", "connected");
        payload.put("externalAccountRef", result.getExternalAccountRef());
        payload.put("credentialsEncrypted", serializeCredentials(credentials)); // TODO Phase 6: envelope-encrypt

        JsonNode saved = dao.post("/marketplace-connections", payload);
        return shape.marketplaceConnection(saved);
    }

    /**
     * Push a locally-created coupon to a connected marketplace. Loads the coupon
     * and its connection, calls the adapter, and records the external id +
     * sync_status back on the coupon.
     */
    public JsonNode pushCoupon(UUID brandId, UUID couponId, UUID connectionIdOverride) {
        JsonNode coupon = dao.get("/influencer-campaign-codes/" + couponId, null);
        requireOwner(coupon, brandId, "coupon");

        UUID connectionId = connectionIdOverride;
        if (connectionId == null && coupon.hasNonNull("marketplaceConnectionId")) {
            connectionId = UUID.fromString(coupon.get("marketplaceConnectionId").asText());
        }
        if (connectionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No marketplace connection specified for this coupon");
        }

        JsonNode connRow = dao.get("/marketplace-connections/" + connectionId, null);
        requireOwner(connRow, brandId, "connection");

        MarketplaceProvider provider = registry.find(connRow.get("providerKey").asText()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Connection uses an unknown provider"));
        if (!provider.capabilities().contains(Capability.CREATE_COUPON)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    provider.displayName() + " does not support coupon creation");
        }

        Connection connection = toConnection(connRow);
        CouponSpec spec = toSpec(coupon);

        ExternalCoupon external;
        try {
            external = provider.createCoupon(spec, connection);
        } catch (RuntimeException ex) {
            markSyncFailed(coupon, connectionId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Marketplace rejected the coupon: " + ex.getMessage());
        }

        ObjectNode update = coupon.deepCopy();
        update.put("marketplaceConnectionId", connectionId.toString());
        update.put("externalCouponId", external.getExternalId());
        update.put("syncStatus", external.getStatus());
        JsonNode saved = dao.put("/influencer-campaign-codes/" + couponId, update);
        return shape.campaignCode(saved);
    }

    // ---- internals -----------------------------------------------------

    private void markSyncFailed(JsonNode coupon, UUID connectionId) {
        try {
            ObjectNode update = coupon.deepCopy();
            update.put("marketplaceConnectionId", connectionId.toString());
            update.put("syncStatus", "sync_failed");
            dao.put("/influencer-campaign-codes/" + coupon.get("id").asText(), update);
        } catch (RuntimeException ignored) {
            // Best-effort status write; the primary error is already surfaced.
        }
    }

    private CouponSpec toSpec(JsonNode coupon) {
        CouponSpec spec = new CouponSpec();
        spec.setCode(coupon.hasNonNull("code") ? coupon.get("code").asText() : null);
        spec.setDiscountType(coupon.hasNonNull("discountType") ? coupon.get("discountType").asText() : null);
        if (coupon.hasNonNull("discountValue")) {
            spec.setDiscountValue(new BigDecimal(coupon.get("discountValue").asText()));
        }
        if (coupon.hasNonNull("startsAt")) {
            spec.setStartsAt(parseInstant(coupon.get("startsAt").asText()));
        }
        if (coupon.hasNonNull("endsAt")) {
            spec.setEndsAt(parseInstant(coupon.get("endsAt").asText()));
        }
        return spec;
    }

    private Connection toConnection(JsonNode connRow) {
        Map<String, String> creds = deserializeCredentials(
                connRow.hasNonNull("credentialsEncrypted") ? connRow.get("credentialsEncrypted").asText() : null);
        Instant cursor = connRow.hasNonNull("syncCursor") ? parseInstant(connRow.get("syncCursor").asText()) : null;
        return new Connection(
                connRow.get("id").asText(),
                connRow.get("providerKey").asText(),
                connRow.hasNonNull("externalAccountRef") ? connRow.get("externalAccountRef").asText() : null,
                creds,
                cursor);
    }

    private void requireOwner(JsonNode row, UUID brandId, String label) {
        if (row == null || row.isNull() || !row.hasNonNull("id")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, label + " not found");
        }
        if (!row.hasNonNull("brandId") || !row.get("brandId").asText().equals(brandId.toString())) {
            // Ownership enforcement for money/store resources (Phase 2 hardening).
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your " + label);
        }
    }

    private String serializeCredentials(Map<String, String> credentials) {
        try {
            return shape.objectMapper().writeValueAsString(credentials == null ? Map.of() : credentials);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store credentials");
        }
    }

    private Map<String, String> deserializeCredentials(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        try {
            JsonNode node = shape.objectMapper().readTree(raw);
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                out.put(entry.getKey(), entry.getValue().asText());
            }
        } catch (Exception ignored) {
            // Malformed stored credentials → empty map; connect handshake will fail loudly.
        }
        return out;
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
