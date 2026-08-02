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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Providers the DPS will start a flow for.
     *
     * <p>An allowlist, not a pass-through: {@code provider} lands in a redirect URL, and forwarding
     * an arbitrary value would let a caller shape where the browser is sent.
     */
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("google", "facebook");

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
     * Starts a federated sign-in.
     *
     * <p>Redirects to the BFF's provider-authorization leg. The DPS owns this entry point so the
     * whole flow begins and ends on the origin that holds the session — the browser leaves from
     * here and comes back to {@link #completeOAuth}.
     */
    @GetMapping("/auth/oauth/{provider}/start")
    public ResponseEntity<Void> startOAuth(@PathVariable String provider,
                                           @RequestParam(required = false) String brandName,
                                           @RequestParam(required = false) String displayName) {
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
        }

        StringBuilder target = new StringBuilder(properties.getBffBaseUrl())
                .append("/api/auth/oauth/").append(provider).append("/start");
        String separator = "?";
        if (brandName != null && !brandName.isBlank()) {
            target.append(separator).append("brandName=").append(encode(brandName));
            separator = "&";
        }
        if (displayName != null && !displayName.isBlank()) {
            target.append(separator).append("displayName=").append(encode(displayName));
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target.toString())).build();
    }

    /**
     * Where the OAuth flow lands after the provider and the BFF are done.
     *
     * <p>The browser arrives with a single-use handoff code — never a token. The DPS redeems it
     * server-to-server, sets the httpOnly session cookie, and redirects to the UI. A federated
     * sign-in therefore leaves no credential anywhere JavaScript can read, exactly like a password
     * one.
     *
     * <p>Redirects rather than returning JSON because the caller here is a browser following the
     * provider's redirect chain, not a fetch() from the SPA. Errors go to the UI with a message
     * parameter so the login page can render them, instead of showing a raw error page.
     */
    @GetMapping("/auth/oauth/complete")
    public ResponseEntity<Void> completeOAuth(@RequestParam(required = false) String handoff,
                                              @RequestParam(required = false) String error) {
        if (error != null && !error.isBlank()) {
            return redirectToUi("/login?error=" + encode(presentable(error)), null);
        }
        if (handoff == null || handoff.isBlank()) {
            return redirectToUi("/login?error=" + encode("OAuth sign-in did not complete"), null);
        }

        try {
            UiSession session = sessionService.completeOAuth(handoff);
            // The cookie is set on this redirect response, so the session exists before the UI
            // loads and its first /dps/session call already sees an authenticated user.
            return redirectToUi("/", session);
        } catch (ResponseStatusException exception) {
            String reason = exception.getReason() == null ? "OAuth sign-in failed" : exception.getReason();
            return redirectToUi("/login?error=" + encode(presentable(reason)), null);
        } catch (Exception exception) {
            return redirectToUi("/login?error=" + encode("OAuth sign-in failed"), null);
        }
    }

    /**
     * Reduces an upstream failure to something safe to show a user.
     *
     * <p>Two problems this solves. An upstream body can be a raw JSON error document — serialising
     * a stack-trace-adjacent blob into a URL leaks internal shape and renders as noise. And the
     * value lands in a query parameter the login page displays, so an over-long or structured
     * string is both a bad error message and needless attack surface.
     */
    private String presentable(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "OAuth sign-in failed";
        }
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
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

    /**
     * Redirects the browser to the UI, attaching the session cookie when one was established.
     *
     * <p>The cookie rides on the redirect itself, so the SPA is already authenticated by the time it
     * loads — no intermediate page, and no window where the UI has to ask "did that work?".
     */
    private ResponseEntity<Void> redirectToUi(String path, UiSession session) {
        String base = properties.getUiBaseUrl();
        if (base == null || base.isBlank()) {
            base = "http://localhost:5173";
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(base.replaceAll("/+$", "") + path));
        if (session != null) {
            builder.header(HttpHeaders.SET_COOKIE, cookieBuilder(
                    session.sessionId(),
                    Duration.ofMinutes(properties.getSessionTtlMinutes())).build().toString());
        }
        return builder.build();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
