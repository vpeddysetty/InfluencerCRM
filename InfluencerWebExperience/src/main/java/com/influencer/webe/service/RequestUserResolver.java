package com.influencer.webe.service;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class RequestUserResolver {
    private final SessionService sessionService;

    public RequestUserResolver(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public UUID resolveUserId(String authorizationHeader, UUID explicitUserId) {
        Optional<SessionService.SessionInfo> sessionInfo = resolveFromAuthorization(authorizationHeader);
        if (sessionInfo.isPresent()) {
            UUID tokenUserId = sessionInfo.get().userId();
            if (explicitUserId != null && !explicitUserId.equals(tokenUserId)) {
                throw new IllegalArgumentException("userId does not match authenticated user");
            }
            return tokenUserId;
        }
        if (explicitUserId != null) {
            return explicitUserId;
        }
        throw new IllegalArgumentException("userId is required (or provide valid Authorization Bearer token)");
    }

    public Optional<SessionService.SessionInfo> resolveFromAuthorization(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null) {
            return Optional.empty();
        }
        return sessionService.resolve(token);
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.startsWith(prefix)) {
            return null;
        }
        String token = authorizationHeader.substring(prefix.length()).trim();
        return token.isBlank() ? null : token;
    }
}
