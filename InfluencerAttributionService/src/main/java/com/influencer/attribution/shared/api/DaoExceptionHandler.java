package com.influencer.attribution.shared.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns persistence failures into meaningful HTTP status codes.
 *
 * <p>Without this, a unique-constraint violation surfaced as a 500, which the BFF then mapped to
 * 502 Bad Gateway — telling the user the service was broken when in fact their input was a
 * duplicate. A duplicate is a 409 and must read as one all the way to the client.
 */
@RestControllerAdvice
public class DaoExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException exception) {
        String detail = rootMessage(exception);
        HttpStatus status = isUniqueViolation(detail) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(body(status, describe(detail, status)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(status).body(body(status, exception.getReason()));
    }

    /**
     * A rejected state transition — e.g. approving a commission that is already paid.
     *
     * <p>409 rather than 500: the request was well-formed and the service is healthy; the aggregate
     * simply refused a transition that would violate its own invariant. Reporting that as a server
     * error would tell the caller to retry, which can never succeed.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, exception.getMessage()));
    }

    /** Invalid input reaching an application service, e.g. an unknown id. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, exception.getMessage()));
    }

    private boolean isUniqueViolation(String detail) {
        String lower = detail == null ? "" : detail.toLowerCase();
        return lower.contains("duplicate key") || lower.contains("unique constraint");
    }

    /**
     * Translates the specific constraints an operator is most likely to hit into wording that says
     * what to do, rather than echoing a raw Postgres message.
     */
    private String describe(String detail, HttpStatus status) {
        String lower = detail == null ? "" : detail.toLowerCase();
        if (lower.contains("uq_creators_brand_platform_handle")) {
            return "This creator handle already exists for this brand on this platform.";
        }
        if (lower.contains("uq_icc_brand_code")) {
            return "This campaign code already exists for this brand.";
        }
        if (lower.contains("uq_das_grain_brand")) {
            return "A daily attribution stat already exists for this brand, day, creator, campaign and channel.";
        }
        if (lower.contains("brands_account_id_name_key")) {
            return "A brand with this name already exists in this account.";
        }
        if (status == HttpStatus.CONFLICT) {
            return "This record conflicts with one that already exists.";
        }
        return "The request violates a data constraint.";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
