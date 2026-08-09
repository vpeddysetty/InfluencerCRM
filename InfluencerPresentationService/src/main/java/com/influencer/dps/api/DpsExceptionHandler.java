package com.influencer.dps.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Turns a malformed request into a 400 rather than a 500.
 *
 * <p>Needed because request records reject unrecognised fields by throwing during deserialization.
 * Jackson wraps that in {@link HttpMessageNotReadableException}, which without this advice surfaces
 * as a server error — telling the caller the platform broke when in fact they sent a field it does
 * not support.
 *
 * <p>Upstream failures are deliberately not handled here: {@code IdentityClient} already raises a
 * {@code ResponseStatusException} carrying the BFF's own status, and Spring's default handling
 * preserves it. Catching those too would flatten a 409 or a 403 into something less useful.
 */
@RestControllerAdvice
public class DpsExceptionHandler {

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "errorCode", "BAD_REQUEST",
                "message", rootMessage(exception),
                "details", Map.of()));
    }

    /**
     * The message worth showing is the one thrown by the record, not Jackson's wrapper around it,
     * which prefixes the useful text with parser coordinates.
     */
    private String rootMessage(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Malformed request" : message;
    }
}
