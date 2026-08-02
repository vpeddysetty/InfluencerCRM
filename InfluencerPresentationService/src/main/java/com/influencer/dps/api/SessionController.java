package com.influencer.dps.api;

import com.influencer.dps.config.DpsProperties;
import com.influencer.dps.session.UiSession;
import com.influencer.dps.session.UiSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication and session for every micro-frontend origin.
 *
 * <p>The browser receives an httpOnly session cookie and nothing else. No access token, no refresh
 * token, no JWT — so an XSS payload has nothing to steal, which is the substantive gain over holding
 * the session in JavaScript.
 *
 * <p>Every response describing a session goes through {@link SessionView}, whose shape deliberately
 * has no field capable of carrying a token. Leaking one would take a code change, not an oversight.
 */
@RestController
@RequestMapping("/dps")
public class SessionController {

    private final UiSessionService sessionService;
    private final DpsProperties properties;

    public SessionController(UiSessionService sessionService, DpsProperties properties) {
        this.sessionService = sessionService;
        this.properties = properties;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<SessionView> login(@Valid @RequestBody LoginRequest request) {
        UiSession session = sessionService.login(request.email(), request.password());
        return withSessionCookie(session, SessionView.of(session));
    }

    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<SessionView> signup(@Valid @RequestBody SignupRequest request) {
        UiSession session = sessionService.signup(request.email(), request.password(), request.brandName());
        return withSessionCookie(session, SessionView.of(session));
    }

    /**
     * The session as the browser sees it.
     *
     * <p>Called on load by the shell and by every remote. Returns 200 with
     * {@code authenticated: false} rather than 401 when there is no session: "not logged in" is a
     * normal state on first visit, and a 401 here would fill the console with errors and tempt
     * callers into treating a routine case as a failure.
     */
    @GetMapping("/session")
    public ResponseEntity<SessionView> currentSession(HttpServletRequest request) {
        Optional<UiSession> session = resolve(request);
        return session
                .map(value -> ResponseEntity.ok(SessionView.of(value)))
                .orElseGet(() -> ResponseEntity.ok(SessionView.anonymous()));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        resolve(request).ifPresent(sessionService::logout);
        // Clear the cookie regardless: the user asked to be logged out, and leaving a stale cookie
        // behind would produce confusing "session not found" errors on the next request.
        ResponseCookie cleared = cookieBuilder("", Duration.ZERO).build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .build();
    }

    @GetMapping("/brands")
    public List<UiSession.BrandSummary> brands(HttpServletRequest request) {
        return requireSession(request).availableBrands();
    }

    @PostMapping("/brands/switch")
    public ResponseEntity<SessionView> switchBrand(HttpServletRequest request,
                                                   @Valid @RequestBody SwitchBrandRequest body) {
        UiSession switched = sessionService.switchBrand(requireSession(request), body.brandId());
        return withSessionCookie(switched, SessionView.of(switched));
    }

    /**
     * Whether the session holds a permission.
     *
     * <p>For a remote deciding what to render. Authorization is still enforced downstream on every
     * call — this only avoids offering the user a dead end.
     */
    @GetMapping("/authorize")
    public Map<String, Object> authorize(HttpServletRequest request, @RequestParam String permission) {
        UiSession session = requireSession(request);
        return Map.of(
                "permission", permission,
                "granted", session.permissions().contains(permission),
                "role", session.role() == null ? "" : session.role(),
                "brandId", session.brandId() == null ? "" : session.brandId().toString());
    }

    /** Data warmed at login, so the first screen does not wait on repeated round trips. */
    @GetMapping("/cache")
    public Map<String, Object> warmCache(HttpServletRequest request) {
        return requireSession(request).warmCache();
    }

    // ------------------------------------------------------------------ helpers

    private Optional<UiSession> resolve(HttpServletRequest request) {
        String sessionId = readCookie(request);
        return sessionId == null ? Optional.empty() : sessionService.findRefreshed(sessionId);
    }

    private UiSession requireSession(HttpServletRequest request) {
        return resolve(request).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active session"));
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (properties.getCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private <T> ResponseEntity<T> withSessionCookie(UiSession session, T body) {
        ResponseCookie cookie = cookieBuilder(
                session.sessionId(),
                Duration.ofMinutes(properties.getSessionTtlMinutes())).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
    }

    private ResponseCookie.ResponseCookieBuilder cookieBuilder(String value, Duration maxAge) {
        return ResponseCookie.from(properties.getCookieName(), value)
                // The property that makes this design worthwhile: JavaScript cannot read it, so an
                // XSS payload cannot steal the session.
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path("/")
                .maxAge(maxAge);
    }

    // ------------------------------------------------------------------ payloads

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record SignupRequest(@Email @NotBlank String email,
                                @NotBlank String password,
                                String brandName) {
    }

    public record SwitchBrandRequest(UUID brandId) {
    }

    /**
     * Everything the browser is allowed to know about its session.
     *
     * <p>There is no field here that can hold a token. That is deliberate: leaking one would require
     * changing this record, not merely forgetting to strip a value.
     */
    public record SessionView(
            boolean authenticated,
            UUID userId,
            String email,
            String userName,
            UUID accountId,
            UUID brandId,
            String brandName,
            String role,
            List<String> permissions,
            List<UiSession.BrandSummary> availableBrands,
            Map<String, Object> warmCache) {

        static SessionView of(UiSession session) {
            return new SessionView(
                    true,
                    session.userId(),
                    session.email(),
                    session.userName(),
                    session.accountId(),
                    session.brandId(),
                    session.brandName(),
                    session.role(),
                    session.permissions(),
                    session.availableBrands(),
                    session.warmCache());
        }

        static SessionView anonymous() {
            return new SessionView(false, null, null, null, null, null, null, null,
                    List.of(), List.of(), Map.of());
        }
    }
}
