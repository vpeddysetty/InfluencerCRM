package com.influencer.webe.attribution.application;

import com.influencer.webe.shared.application.ResponseShapeService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.marketplace.Capability;
import com.influencer.webe.marketplace.Connection;
import com.influencer.webe.marketplace.ConnectionResult;
import com.influencer.webe.marketplace.CouponSpec;
import com.influencer.webe.marketplace.CredentialProtector;
import com.influencer.webe.marketplace.ExternalCoupon;
import com.influencer.webe.marketplace.MarketplaceProvider;
import com.influencer.webe.marketplace.MarketplaceProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * <p>Credentials are envelope-encrypted by {@link CredentialProtector} before they reach the DAO
 * (roadmap M3.1) and decrypted only at the moment an adapter call needs them. A provider that
 * declares {@link MarketplaceProvider#usesRealCredentials()} cannot be connected at all unless a
 * key is configured — see that class for why the alternative was a silent plaintext fallback.
 */
@Service
public class MarketplaceService {
    private static final Logger log = LoggerFactory.getLogger(MarketplaceService.class);

    private final MarketplaceProviderRegistry registry;
    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final CredentialProtector credentials;

    public MarketplaceService(MarketplaceProviderRegistry registry,
                              DaoGatewayClient dao,
                              ResponseShapeService shape,
                              CredentialProtector credentials) {
        this.registry = registry;
        this.dao = dao;
        this.shape = shape;
        this.credentials = credentials;
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

        // Checked BEFORE the handshake, not after. Connecting first would send the operator's real
        // store credentials to the vendor, establish a session, and only then discover there is
        // nowhere safe to put them — leaving a live grant this system cannot record and the
        // operator has to go revoke by hand. Refusing up front costs nothing.
        boolean mustProtect = provider.usesRealCredentials();
        if (mustProtect && !this.credentials.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Marketplace credential encryption is not configured on this deployment, so "
                    + provider.displayName() + " cannot be connected. Set "
                    + "web-experience.marketplace.credential-key and restart.");
        }

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
        payload.put("credentialsEncrypted",
                this.credentials.protect(serializeCredentials(credentials), mustProtect));

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

    /** A webhook whose signature checked out, and the brand it belongs to. */
    public record VerifiedWebhook(UUID brandId, UUID connectionId, String providerKey) {
    }

    /**
     * Authenticates an inbound webhook and resolves the brand it is for.
     *
     * <p><b>The brand comes from the connection, never from the request.</b> That is the whole fix:
     * the caller no longer names a tenant, so it cannot name someone else's. The store identifier
     * in the provider's own headers selects a {@code marketplace_connections} row, and that row's
     * {@code brandId} is the tenant.
     *
     * <p><b>The signature is checked against that connection's credentials.</b> Resolving the brand
     * and verifying the signature are one step on purpose: done separately, a request could be
     * verified against one store's secret and then attributed to another store's brand.
     *
     * <p>Every failure is a flat 401 with the same message. Distinguishing "unknown store" from
     * "bad signature" would tell someone probing which store identifiers exist.
     */
    public VerifiedWebhook verifyWebhook(String providerKey,
                                         Map<String, String> headers,
                                         String rawBody) {
        MarketplaceProvider provider = registry.find(providerKey).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook rejected"));

        String storeRef = storeReferenceFrom(headers);
        if (storeRef == null || storeRef.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook rejected");
        }

        JsonNode connRow = findConnectionByStore(provider.key(), storeRef);
        if (connRow == null) {
            log.warn("Webhook for {} named an unknown store", provider.key());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook rejected");
        }

        Connection connection = toConnection(connRow);
        byte[] body = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
        if (!provider.verifyWebhook(body, headers, connection)) {
            log.warn("Webhook signature failed for {} store {}", provider.key(), storeRef);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook rejected");
        }

        return new VerifiedWebhook(
                UUID.fromString(connRow.get("brandId").asText()),
                UUID.fromString(connRow.get("id").asText()),
                provider.key());
    }

    /**
     * The store identifier a provider puts in its webhook headers.
     *
     * <p>Header names are matched case-insensitively: HTTP header case is not significant, and a
     * provider that changes {@code X-Shopify-Shop-Domain} to lowercase would otherwise silently
     * stop authenticating.
     */
    private String storeReferenceFrom(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("x-shopify-shop-domain") || name.equals("x-marketplace-store")) {
                return header.getValue();
            }
        }
        return null;
    }

    /** Finds the connection a store identifier belongs to, or null. */
    private JsonNode findConnectionByStore(String providerKey, String storeRef) {
        try {
            JsonNode rows = dao.get("/marketplace-connections",
                    Map.of("providerKey", providerKey, "externalAccountRef", storeRef));
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                return null;
            }
            // externalAccountRef is unique per provider in practice; take the first and log if not.
            if (rows.size() > 1) {
                log.warn("{} store {} matches {} connections; using the first",
                        providerKey, storeRef, rows.size());
            }
            JsonNode row = rows.get(0);
            return row.hasNonNull("brandId") && row.hasNonNull("id") ? row : null;
        } catch (RuntimeException lookupFailed) {
            // A DAO outage must not become a way past authentication.
            log.warn("Could not resolve the store for a {} webhook: {}", providerKey, lookupFailed.toString());
            return null;
        }
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
        String stored = connRow.hasNonNull("credentialsEncrypted")
                ? connRow.get("credentialsEncrypted").asText() : null;

        String plaintext;
        try {
            plaintext = credentials.reveal(stored, connRow.get("id").asText());
        } catch (IllegalStateException e) {
            // A wrong or missing key must not read as "this store has no credentials", which is
            // what an empty map would look like to the adapter — it would fail somewhere deep in a
            // vendor call with an authentication error that sends the operator hunting at Shopify.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Stored credentials for this connection cannot be decrypted. "
                    + "Check web-experience.marketplace.credential-key.", e);
        }

        Map<String, String> creds = deserializeCredentials(plaintext);
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
