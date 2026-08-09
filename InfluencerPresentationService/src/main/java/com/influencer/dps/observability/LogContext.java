package com.influencer.dps.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * The fields every log line carries, and the only place their names are written down.
 *
 * <p><b>Why constants rather than string literals at each call site.</b> These keys are the schema
 * a log scraper queries on. A typo in one service — {@code tenantId} where every other service
 * writes {@code tenant} — does not fail a build or a test; it produces a field that silently never
 * matches a dashboard filter, and the gap is only noticed during the incident the dashboard existed
 * for. Naming them once makes the schema a compile-time thing.
 *
 * <p><b>Why MDC.</b> The alternative is threading a context object through every method signature
 * so it can be interpolated into messages. That is invasive, gets dropped by whoever is in a hurry,
 * and produces lines that are correlated only where someone remembered. MDC attaches the context to
 * the thread once, at the edge, and every log statement underneath inherits it — including ones
 * inside libraries that know nothing about this class.
 *
 * <p><b>The cost, stated plainly:</b> MDC is thread-local, so it does not follow work handed to
 * another thread. {@link #wrap} exists for that case and must be used for any async dispatch, or
 * the correlation id silently disappears exactly where it is hardest to reconstruct.
 */
public final class LogContext {

    /** Correlates one browser action across every service it touches. The key field for support. */
    public static final String REQUEST_ID = "rid";

    /** Which service emitted the line. */
    public static final String SERVICE = "svc";

    /** The calling micro-frontend, when one identified itself (step 1). */
    public static final String APP_ID = "app";

    /** Tenant the request is acting on. Present on anything that touches customer data. */
    public static final String TENANT = "tenant";

    /** The acting user, where one is known. Never an email — see the redaction note below. */
    public static final String USER = "user";

    /**
     * A stable name for what happened, e.g. {@code marketplace.connect.refused}.
     *
     * <p>Dotted and lowercase by convention so a scraper can alert on a prefix
     * ({@code marketplace.*}) without pattern-matching prose. The human message is free to change
     * wording; this is the part that must not, because alerts are built on it.
     */
    public static final String EVENT = "evt";

    /** Duration in milliseconds, on lines that measure something. */
    public static final String DURATION_MS = "ms";

    /** HTTP status, on lines that conclude a request. */
    public static final String STATUS = "status";

    private LogContext() {
    }

    /** Generates a correlation id. Short enough to paste into a ticket, random enough not to collide. */
    public static String newRequestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static void put(String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    public static String get(String key) {
        return MDC.get(key);
    }

    public static String requestId() {
        return MDC.get(REQUEST_ID);
    }

    public static void clear() {
        MDC.clear();
    }

    /**
     * Copies the current context onto a task that will run on another thread.
     *
     * <p>Without this an async handoff loses the correlation id, and the resulting lines are
     * unattributable — which is worst precisely for background work, where there is no user waiting
     * to say what they were doing.
     */
    public static Runnable wrap(Runnable task) {
        var captured = MDC.getCopyOfContextMap();
        return () -> {
            var previous = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            }
            try {
                task.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
