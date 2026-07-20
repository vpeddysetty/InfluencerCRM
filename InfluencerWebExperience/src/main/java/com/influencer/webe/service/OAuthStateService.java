package com.influencer.webe.service;

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
        String state = UUID.randomUUID().toString();
        PendingOAuthRequest request = new PendingOAuthRequest(
                state,
                provider,
                brandName,
                displayName,
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

    public record PendingOAuthRequest(String state, String provider, String brandName, String displayName, Instant issuedAt, Instant expiresAt) {
    }
}