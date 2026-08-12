package com.influencer.webe.identity.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OAuthStateService {
    private final Map<String, PendingOAuthRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Duration ttl = Duration.ofMinutes(15);

    public PendingOAuthRequest create(String provider, String brandName, String displayName) {
        return create(provider, brandName, displayName, false, null, null);
    }

    /**
     * Opens a pending request, carrying the consent given before the browser left.
     *
     * <p>A federated signup has no moment after the redirect at which a checkbox could be shown: the
     * next thing that happens is the provider's own screen, and the callback returns to a browser
     * that is already signing in. So consent is taken on the landing page, verified at {@code
     * /start}, and parked HERE until the callback creates the account and can record it against a
     * user id.
     *
     * <p>Keeping it in this server-side map, rather than round-tripping it through the {@code state}
     * parameter, is what makes it trustworthy — a value returned by the browser could be flipped by
     * whoever controls the browser, and the record would then attest to something that never
     * happened. The IP and user agent are captured here too, at the moment of the actual act, for
     * the same reason: by the callback they would describe the provider's redirect, not the person.
     */
    public PendingOAuthRequest create(String provider,
                                      String brandName,
                                      String displayName,
                                      boolean acceptedTerms,
                                      String ipAddress,
                                      String userAgent) {
        String state = UUID.randomUUID().toString();
        PendingOAuthRequest request = new PendingOAuthRequest(
                state,
                provider,
                brandName,
                displayName,
                acceptedTerms,
                ipAddress,
                userAgent,
                Instant.now(),
                Instant.now().plus(ttl));
        pendingRequests.put(state, request);
        return request;
    }

    public PendingOAuthRequest consume(String state) {
        if (state == null || state.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state is required");
        }

        PendingOAuthRequest request = pendingRequests.remove(state);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state is invalid or expired");
        }
        if (request.expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state is invalid or expired");
        }
        return request;
    }

    public record PendingOAuthRequest(String state,
                                      String provider,
                                      String brandName,
                                      String displayName,
                                      boolean acceptedTerms,
                                      String ipAddress,
                                      String userAgent,
                                      Instant issuedAt,
                                      Instant expiresAt) {
    }
}