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
        return create(provider, brandName, displayName, acceptedTerms, ipAddress, userAgent, false);
    }

    /**
     * As above, recording whether the person set out to SIGN IN rather than sign up.
     *
     * <p>The distinction has to be carried because it cannot be recovered later. {@code
     * signupWithSocial} doubles as sign-in and does not distinguish the two, so by the time the
     * callback knows whether an account exists the browser has already been to the provider — far
     * too late to ask for consent, and equally too late to decide that none was needed.
     *
     * <p>Held here rather than in the {@code state} parameter for the same reason as the consent
     * flag: a value the browser round-trips is a value whoever controls the browser can flip, and
     * "this was only a sign-in" is precisely the claim that would let someone create an account
     * without ever agreeing to anything.
     */
    public PendingOAuthRequest create(String provider,
                                      String brandName,
                                      String displayName,
                                      boolean acceptedTerms,
                                      String ipAddress,
                                      String userAgent,
                                      boolean signInOnly) {
        String state = UUID.randomUUID().toString();
        PendingOAuthRequest request = new PendingOAuthRequest(
                state,
                provider,
                brandName,
                displayName,
                acceptedTerms,
                ipAddress,
                userAgent,
                // Not a link: this path creates or signs in an account rather than attaching a
                // provider to one that exists.
                null,
                signInOnly,
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

    /**
     * Opens a pending request that LINKS a provider to an account that already exists.
     *
     * <p>The distinguishing field is {@code linkUserId}. Its presence is what tells the callback to
     * attach the provider identity to that user instead of signing anyone in, and it is set from the
     * verified token of a caller who is already authenticated — never from anything the browser
     * sends. That is the whole security property: a link decides which account an external identity
     * can open, so a user id the browser could choose would let anyone attach their own Facebook
     * account to someone else's workspace.
     *
     * <p>No consent fields: the terms were accepted when the account was created, and connecting a
     * second sign-in method to an existing account is not a new agreement.
     */
    public PendingOAuthRequest createForLink(String provider, UUID linkUserId) {
        String state = UUID.randomUUID().toString();
        PendingOAuthRequest request = new PendingOAuthRequest(
                state,
                provider,
                null,
                null,
                false,
                null,
                null,
                linkUserId,
                // Irrelevant for a link: no account is created or signed in to.
                false,
                Instant.now(),
                Instant.now().plus(ttl));
        pendingRequests.put(state, request);
        return request;
    }

    public record PendingOAuthRequest(String state,
                                      String provider,
                                      String brandName,
                                      String displayName,
                                      boolean acceptedTerms,
                                      String ipAddress,
                                      String userAgent,
                                      /** Non-null only for a link; see {@link #createForLink}. */
                                      UUID linkUserId,
                                      /** True when the person chose Log in rather than Sign up. */
                                      boolean signInOnly,
                                      Instant issuedAt,
                                      Instant expiresAt) {
    }
}