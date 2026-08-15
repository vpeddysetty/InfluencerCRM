package com.influencer.dps.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.influencer.dps.channel.AppRegistry;
import com.influencer.dps.config.DpsProperties;
import com.influencer.dps.session.UiSession;
import com.influencer.dps.session.UiSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    /** Identifies the calling micro-frontend. See {@link #callingApp}. */
    static final String APP_ID_HEADER = "X-App-Id";

    private final UiSessionService sessionService;
    private final DpsProperties properties;
    private final com.influencer.dps.identity.IdentityClient identityClient;

    public SessionController(UiSessionService sessionService,
                             DpsProperties properties,
                             com.influencer.dps.identity.IdentityClient identityClient) {
        this.sessionService = sessionService;
        this.properties = properties;
        this.identityClient = identityClient;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<SessionView> login(@Valid @RequestBody LoginRequest request) {
        UiSession session = sessionService.login(request.email(), request.password());
        return withSessionCookie(session, SessionView.of(session));
    }

    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<SessionView> signup(@Valid @RequestBody SignupRequest request) {
        UiSession session = sessionService.signup(
                request.email(), request.password(), request.brandName(), request.accountType());
        return withSessionCookie(session, SessionView.of(session));
    }

    /**
     * Begins connecting a provider to the account this browser is signed in to.
     *
     * <p>Its own endpoint rather than the generic {@code /dps/api} proxy, because that proxy returns
     * the upstream response as a BODY. A 302 arrives with its status intact but no forwarded
     * {@code Location}, so the browser has nothing to follow — fine for the fetch() calls the proxy
     * exists for, useless for a leg whose entire purpose is to navigate.
     *
     * <p>The session cookie is what authorises this. Its access token is handed to the BFF, which
     * resolves the user from it: linking decides which account an external identity can open, so the
     * user must come from a verified token and never from the request.
     */
    @GetMapping("/auth/connected-accounts/{provider}/start")
    public ResponseEntity<Void> startProviderLink(@PathVariable String provider,
                                                  HttpServletRequest request) {
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
        }

        UiSession session = resolve(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first"));

        // The BFF is asked SERVER-TO-SERVER for the authorization URL, and only its answer is
        // redirected to. The obvious alternative — redirect the browser at the BFF with the access
        // token in the query string — would put a bearer token in browser history, in the Referer of
        // whatever the provider loads next, and in every access log along the way. That is the exact
        // exposure this service exists to prevent, and it is why the session lives in an httpOnly
        // cookie rather than in the page.
        //
        // So the token stays on this side of the wire, travelling as a header on a call the browser
        // never sees, and the browser receives only the provider URL it was always going to be sent
        // to. bff-base-url here, not the public one: this leg is server-to-server.
        String location = identityClient.authorizationUrlForLink(provider, session.accessToken());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
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
                                           @RequestParam(required = false) String displayName,
                                           @RequestParam(required = false, defaultValue = "false") boolean acceptedTerms) {
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
        }

        // THE PUBLIC BFF URL, NOT dps.bff-base-url.
        //
        // This is a Location header: the BROWSER resolves it. bff-base-url is the server-to-server
        // address, which in the deployed compose stack is http://web-experience:8081 — a hostname on
        // the container bridge network that does not resolve anywhere else. Sending it here is what
        // broke Google sign-in: the browser was redirected to a name it could not look up, and the
        // landing page sat on "Connecting…" forever because the redirect that was supposed to
        // replace the page never happened.
        //
        // Two URLs for two audiences, so neither can be quietly used for the other.
        StringBuilder target = new StringBuilder(properties.requirePublicBffBaseUrl())
                .append("/api/auth/oauth/").append(provider).append("/start");
        String separator = "?";
        if (brandName != null && !brandName.isBlank()) {
            target.append(separator).append("brandName=").append(encode(brandName));
            separator = "&";
        }
        if (displayName != null && !displayName.isBlank()) {
            target.append(separator).append("displayName=").append(encode(displayName));
            separator = "&";
        }
        // Forwarded rather than re-asked: the checkbox is on the landing page, and this leg is a
        // redirect with nowhere to render one. The BFF re-checks it — see OAuthFlowService.startGoogle.
        if (acceptedTerms) {
            target.append(separator).append("acceptedTerms=true");
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
                                              @RequestParam(required = false) String error,
                                              @RequestParam(required = false) String linked) {
        if (error != null && !error.isBlank()) {
            return redirectToUi("/login?error=" + encode(presentable(error)), null);
        }

        // A LINK rather than a sign-in. There is no handoff to redeem and no session to create —
        // the caller already had one, which is what authorised the link in the first place. Handled
        // before the handoff check, which would otherwise read a successful link as a failed login
        // and send the user to the sign-in page with an error.
        if (linked != null && !linked.isBlank()) {
            return redirectToUi("/settings?linked=" + encode(presentable(linked)), null);
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
        AppRegistry app = callingApp(request).orElse(null);
        return session
                .map(value -> ResponseEntity.ok(SessionView.of(value, app)))
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

    /**
     * Which micro-frontend is calling, from {@code X-App-Id}.
     *
     * <p><b>An absent header is permitted; an unrecognised one is not.</b> Every remote in the
     * platform predates this header, so requiring it would break all of them on deploy — a
     * self-inflicted outage in the name of security, and the reliable way to get a security control
     * reverted. An absent header therefore means "unscoped", exactly as before.
     *
     * <p>A header that is <em>present but unknown</em> is a different case and is rejected. It is
     * either a misconfigured deployment or someone probing for an app id that grants more, and
     * silently treating it as unscoped would make the strictest-sounding value the most permissive
     * one. Failing there costs nothing, because no legitimate caller sends an unknown id.
     *
     * <p>Once every remote sends the header, the absent case should become a rejection too. That is
     * a one-line change here, deliberately left until the remotes are updated.
     */
    private Optional<AppRegistry> callingApp(HttpServletRequest request) {
        String header = request.getHeader(APP_ID_HEADER);
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        Optional<AppRegistry> app = AppRegistry.find(header);
        if (app.isEmpty()) {
            log.warn("Rejected an unknown app id '{}'", header.replaceAll("[^A-Za-z0-9._:-]", ""));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unknown application");
        }
        return app;
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
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.getCookieName(), value)
                // The property that makes this design worthwhile: JavaScript cannot read it, so an
                // XSS payload cannot steal the session.
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path("/")
                .maxAge(maxAge);

        // Only when configured. An empty Domain attribute is not the same as none: it is invalid,
        // and a browser drops the whole cookie rather than treating it as host-only — which would
        // turn "no domain configured" into "no session at all" for local development.
        //
        // Applied through the shared builder so the CLEARING cookie carries it too. A logout whose
        // Domain does not match the one the cookie was set with does not clear anything: the browser
        // treats them as different cookies and the session cookie survives the sign-out.
        String domain = properties.getCookieDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain.trim());
        }
        return builder;
    }

    // ------------------------------------------------------------------ payloads

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    /**
     * {@code accountType} selects a solo brand workspace or an agency one. Optional — the BFF
     * defaults it to {@code brand}, and it validates the value, so this layer forwards rather than
     * duplicating a rule that would then have two places to drift apart.
     *
     * <p>Unknown fields are rejected here as well as at the BFF. This record deserializes first, so
     * without its own guard an unrecognised property is dropped at this boundary and the BFF's
     * check never sees it — the caller would get a 200 for a request the platform does not
     * actually support.
     */
    public record SignupRequest(@Email @NotBlank String email,
                                @NotBlank String password,
                                String brandName,
                                String accountType) {

        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unrecognised signup field: " + name);
        }
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
            return of(session, null);
        }

        /**
         * The session as one app may see it.
         *
         * <p>When the caller identified itself, the permission list is narrowed to what that app is
         * entitled to use. An unidentified caller gets the full list, because every existing remote
         * predates the header and breaking them would be a worse outcome than the exposure this
         * narrows — see the note on {@code X-App-Id} in the controller.
         *
         * <p>Narrowing the list is a UI-rendering benefit only. The enforcement that matters is on
         * the proxy, which refuses paths the app is not entitled to.
         */
        static SessionView of(UiSession session, AppRegistry app) {
            List<String> permissions = app == null
                    ? session.permissions()
                    : app.scope(session.permissions());
            return new SessionView(
                    true,
                    session.userId(),
                    session.email(),
                    session.userName(),
                    session.accountId(),
                    session.brandId(),
                    session.brandName(),
                    session.role(),
                    permissions,
                    session.availableBrands(),
                    session.warmCache());
        }

        static SessionView anonymous() {
            return new SessionView(false, null, null, null, null, null, null, null,
                    List.of(), List.of(), Map.of());
        }
    }
}
