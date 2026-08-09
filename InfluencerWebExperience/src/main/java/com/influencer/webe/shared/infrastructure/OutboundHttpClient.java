package com.influencer.webe.shared.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * General-purpose egress to third-party APIs (roadmap M6.1).
 *
 * <p><b>None existed.</b> The only outbound client in the BFF was {@code DaoHttpClientFactory},
 * which builds an mTLS client for the DAO specifically — wrong shape for a public API, and wrong
 * trust store. Every social adapter would otherwise hand-roll its own {@code HttpClient}, each
 * with its own timeout policy and its own idea of what an error is.
 *
 * <p><b>Never throws on a failed call.</b> Returns {@link Optional#empty()} instead. A creator
 * lookup that fails must degrade to the manual path rather than fail a signup (rule C.6), and an
 * adapter forced to catch four exception types to express "no answer" gets that wrong eventually.
 * The distinction that matters to a caller is "did I get data", not which layer failed.
 *
 * <p>Timeouts are mandatory and bounded: these calls happen on a request thread, and a platform
 * that stops responding must not hold one open indefinitely.
 */
@Component
public class OutboundHttpClient {

    private static final Logger log = LoggerFactory.getLogger(OutboundHttpClient.class);

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public OutboundHttpClient(
            ObjectMapper objectMapper,
            @Value("${web-experience.outbound.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${web-experience.outbound.request-timeout-ms:10000}") int requestTimeoutMs) {
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                // NORMAL, not ALWAYS: following a redirect from HTTPS to HTTP would send whatever
                // credentials the request carries over plaintext.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GET a URL and parse the body as JSON.
     *
     * @param headers extra headers, typically authorization. May be empty.
     * @return the parsed body, or empty on any failure — non-2xx, timeout, unparseable body
     */
    public Optional<JsonNode> getJson(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET();
        if (headers != null) {
            headers.forEach(builder::header);
        }

        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Logged at info, not error: a 404 for a handle that does not exist is an ordinary
                // outcome of the feature, not a fault. Logging it as an error trains people to
                // ignore the log. The URL is logged without its query string — API keys travel
                // there, and a log is a place secrets outlive their rotation.
                log.info("Outbound GET {} returned {}", stripQuery(url), response.statusCode());
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (java.io.InterruptedIOException e) {
            // A timeout on the send() path. Distinguished from a general IO failure because it is
            // the one worth watching for quota or rate-limit trouble.
            log.warn("Outbound GET {} timed out after {}", stripQuery(url), requestTimeout);
            return Optional.empty();
        } catch (InterruptedException e) {
            // Restore the flag. Swallowing it leaves the thread's interrupt state lying, and a
            // pool thread that cannot be shut down is a slow leak.
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Outbound GET {} failed: {}", stripQuery(url), e.toString());
            return Optional.empty();
        }
    }

    /**
     * POST form-encoded parameters and parse the JSON response.
     *
     * <p>Form encoding rather than JSON because that is what Stripe's API takes — it is the
     * common shape for payment APIs, not a quirk. Added for M2.1.
     *
     * <p><b>Returns the body on a non-2xx as well as a 2xx</b>, unlike {@link #getJson}. A payment
     * provider puts the reason a charge was declined in the error body, and a caller that only
     * learns "it failed" cannot tell a customer whether to try a different card or contact
     * support. {@link Response#ok()} says which happened.
     */
    public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)));
        if (headers != null) {
            headers.forEach(builder::header);
        }

        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body;
            try {
                body = objectMapper.readTree(response.body());
            } catch (Exception e) {
                body = objectMapper.createObjectNode();
            }
            boolean ok = response.statusCode() / 100 == 2;
            if (!ok) {
                // The URL only — never the form body, which carries payment parameters, and never
                // the Authorization header, which carries a live secret key.
                log.warn("Outbound POST {} returned {}", stripQuery(url), response.statusCode());
            }
            return new Response(ok, response.statusCode(), body);
        } catch (java.io.InterruptedIOException e) {
            log.warn("Outbound POST {} timed out after {}", stripQuery(url), requestTimeout);
            return new Response(false, 0, objectMapper.createObjectNode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(false, 0, objectMapper.createObjectNode());
        } catch (Exception e) {
            log.warn("Outbound POST {} failed: {}", stripQuery(url), e.toString());
            return new Response(false, 0, objectMapper.createObjectNode());
        }
    }

    /**
     * @param ok     whether the status was 2xx
     * @param status the HTTP status, or 0 if the call never completed
     * @param body   the parsed body — present on failure too, since that is where a provider
     *               explains itself
     */
    public record Response(boolean ok, int status, JsonNode body) {
    }

    /** Percent-encodes a form body. Null values are dropped rather than sent as "null". */
    private static String encodeForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        form.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    /**
     * Drops the query string before logging.
     *
     * <p>Platform API keys are commonly passed as a query parameter, and logs are copied,
     * shipped, and retained longer than any key rotation window.
     */
    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?<redacted>";
    }
}
