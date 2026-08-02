package com.influencer.dps.api;

import com.influencer.dps.config.DpsProperties;
import com.influencer.dps.identity.IdentityClient;
import com.influencer.dps.session.UiSession;
import com.influencer.dps.session.UiSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Forwards a remote's API calls to the platform, attaching the session's credentials.
 *
 * <p>This is what replaces the token that used to live in JavaScript. A remote calls
 * {@code /dps/api/creators} with its session cookie; the DPS looks the session up, attaches the
 * bearer token and the {@code X-Brand-Id} tenancy header, and forwards. The browser never holds a
 * credential, and no call site can choose a different tenant.
 *
 * <p>Refreshing an expired access token happens inside {@code findRefreshed} — invisibly, before the
 * call is forwarded. The remote never sees a 401 caused merely by expiry.
 */
@RestController
@RequestMapping("/dps/api")
public class ApiProxyController {

    private final UiSessionService sessionService;
    private final IdentityClient identityClient;
    private final DpsProperties properties;

    public ApiProxyController(UiSessionService sessionService,
                              IdentityClient identityClient,
                              DpsProperties properties) {
        this.sessionService = sessionService;
        this.identityClient = identityClient;
        this.properties = properties;
    }

    @RequestMapping(value = "/**", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<String> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) String body) {

        UiSession session = resolve(request).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active session"));

        // /dps/api/creators -> /api/creators
        String path = request.getRequestURI().substring("/dps/api".length());
        String query = request.getQueryString();
        String target = "/api" + path + (query == null || query.isBlank() ? "" : "?" + query);

        HttpResponse<String> upstream = identityClient.proxy(
                request.getMethod(),
                target,
                session.accessToken(),
                session.brandId() == null ? null : session.brandId().toString(),
                body,
                request.getContentType());

        // The upstream status is preserved rather than normalised. A 403 from the permission check
        // and a 409 from a duplicate mean different things to the caller, and collapsing them into
        // a generic error would throw away the diagnosis.
        return ResponseEntity.status(upstream.statusCode())
                .contentType(resolveContentType(upstream))
                .body(upstream.body());
    }

    private Optional<UiSession> resolve(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (var cookie : request.getCookies()) {
            if (properties.getCookieName().equals(cookie.getName())) {
                return sessionService.findRefreshed(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private MediaType resolveContentType(HttpResponse<String> upstream) {
        return upstream.headers().firstValue("content-type")
                .map(value -> {
                    try {
                        return MediaType.parseMediaType(value);
                    } catch (Exception exception) {
                        return MediaType.APPLICATION_JSON;
                    }
                })
                .orElse(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
    }
}
