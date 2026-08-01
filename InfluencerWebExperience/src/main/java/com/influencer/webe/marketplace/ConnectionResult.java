package com.influencer.webe.marketplace;

/**
 * Outcome of a {@link MarketplaceProvider#connect} handshake: whether the
 * credentials validated, the resolved external account handle, and a message for
 * the UI on failure.
 */
public class ConnectionResult {
    private final boolean success;
    private final String externalAccountRef;
    private final String displayName;
    private final String message;

    private ConnectionResult(boolean success, String externalAccountRef, String displayName, String message) {
        this.success = success;
        this.externalAccountRef = externalAccountRef;
        this.displayName = displayName;
        this.message = message;
    }

    public static ConnectionResult ok(String externalAccountRef, String displayName) {
        return new ConnectionResult(true, externalAccountRef, displayName, null);
    }

    public static ConnectionResult failed(String message) {
        return new ConnectionResult(false, null, null, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getExternalAccountRef() {
        return externalAccountRef;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMessage() {
        return message;
    }
}
