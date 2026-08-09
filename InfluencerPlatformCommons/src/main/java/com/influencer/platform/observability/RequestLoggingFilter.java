package com.influencer.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes the logging context for every request, and records how it ended.
 *
 * <p><b>The correlation id is accepted from the caller, not minted here.</b> An id generated per
 * service would mean four unrelated ids for one browser action, and correlating them would require
 * matching on timestamps — which is guesswork under concurrency. The DPS mints one at the edge and
 * every downstream hop inherits it through {@code X-Request-Id}, so one value follows the request
 * across the whole chain.
 *
 * <p><b>An inbound id is length-capped and character-filtered.</b> It is attacker-controllable and
 * lands in a log file that support tooling parses: an unbounded value is a way to bloat the log,
 * and a value containing a newline is log injection — a forged record that reads as genuine. The
 * encoder escapes newlines too, so this is the second of two independent guards.
 *
 * <p><b>Ordered first.</b> Security filters reject requests, and a rejection with no correlation id
 * is the hardest kind to investigate — an auth failure is exactly what support gets called about.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String APP_ID_HEADER = "X-App-Id";

    private static final int MAX_ID_LENGTH = 64;

    /**
     * Paths that must not produce a log line per call.
     *
     * <p>A liveness probe every few seconds is thousands of identical INFO lines a day. That is not
     * free: it consumes the retention window the size cap allows, pushing out the older lines an
     * investigation actually needs.
     */
    private static boolean isNoise(String path) {
        return path.startsWith("/actuator") || path.equals("/health") || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestId = sanitize(request.getHeader(REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = LogContext.newRequestId();
        }

        LogContext.put(LogContext.REQUEST_ID, requestId);
        LogContext.put(LogContext.APP_ID, sanitize(request.getHeader(APP_ID_HEADER)));
        LogContext.put(LogContext.TENANT, sanitize(request.getHeader("X-Brand-Id")));

        // Echoed so a browser — and the support person reading a screenshot of its network tab —
        // can quote the id that ties their report to these logs.
        response.setHeader(REQUEST_ID_HEADER, requestId);

        String path = request.getRequestURI();
        boolean noisy = isNoise(path);
        long startedAt = System.currentTimeMillis();

        try {
            if (!noisy) {
                log.debug("{} {}", request.getMethod(), path);
            }
            chain.doFilter(request, response);
        } finally {
            if (!noisy) {
                long elapsed = System.currentTimeMillis() - startedAt;
                int status = response.getStatus();
                LogContext.put(LogContext.STATUS, Integer.toString(status));
                LogContext.put(LogContext.DURATION_MS, Long.toString(elapsed));
                LogContext.put(LogContext.EVENT, "http.request.completed");

                // 5xx is ours, 4xx is usually the caller's. Logging every 404 at ERROR trains
                // support to ignore ERROR, which is how a real one gets missed.
                if (status >= 500) {
                    log.error("{} {} failed with {} in {}ms", request.getMethod(), path, status, elapsed);
                } else if (status >= 400) {
                    log.warn("{} {} rejected with {} in {}ms", request.getMethod(), path, status, elapsed);
                } else {
                    log.info("{} {} -> {} in {}ms", request.getMethod(), path, status, elapsed);
                }
            }
            // Threads are pooled and reused. Without this the next request on this thread inherits
            // the previous one's ids and logs under someone else's identity.
            LogContext.clear();
        }
    }

    /** Caps length and strips anything that is not safe in a log field. Returns null if unusable. */
    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_ID_LENGTH) {
            trimmed = trimmed.substring(0, MAX_ID_LENGTH);
        }
        String cleaned = trimmed.replaceAll("[^A-Za-z0-9._:-]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }
}
