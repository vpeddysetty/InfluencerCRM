package com.influencer.webe.marketplace;

import java.time.Instant;
import java.util.Map;

/**
 * Runtime context handed to an adapter for a specific connected store. Built from
 * a {@code marketplace_connections} row (credentials decrypted just-in-time).
 * Adapters must NOT persist anything from here beyond what they return.
 */
public class Connection {
    private final String connectionId;
    private final String providerKey;
    private final String externalAccountRef;
    private final Map<String, String> credentials;
    private final Instant syncCursor;

    public Connection(String connectionId, String providerKey, String externalAccountRef,
                      Map<String, String> credentials, Instant syncCursor) {
        this.connectionId = connectionId;
        this.providerKey = providerKey;
        this.externalAccountRef = externalAccountRef;
        this.credentials = credentials;
        this.syncCursor = syncCursor;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public String getExternalAccountRef() {
        return externalAccountRef;
    }

    public Map<String, String> getCredentials() {
        return credentials;
    }

    public String credential(String key) {
        return credentials == null ? null : credentials.get(key);
    }

    public Instant getSyncCursor() {
        return syncCursor;
    }
}
