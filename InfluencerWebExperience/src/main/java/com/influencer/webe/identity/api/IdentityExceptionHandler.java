package com.influencer.webe.identity.api;

import com.influencer.webe.identity.application.EmailNotVerifiedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Identity-specific error mapping.
 *
 * <p><b>Here rather than in {@code shared.api.ApiExceptionHandler}</b>, which is where this was
 * first written. That package is cross-cutting and the architecture test forbids it from depending
 * on any single context — a rule worth keeping: an exception handler that reaches into identity is
 * how {@code shared} slowly becomes a second home for every context's types. Advice is discovered
 * by annotation, not by package, so a context-local handler works exactly as well.
 */
@RestControllerAdvice
public class IdentityExceptionHandler {

    /**
     * 403 with a machine-readable code, not 401.
     *
     * <p>The credentials were correct — 401 would tell a client to prompt for the password again,
     * which cannot help and reads as "wrong password" to the person holding the right one. The
     * distinct {@code errorCode} is what lets the UI offer "resend the link" instead of a generic
     * failure, so it is part of the contract rather than a message string.
     */
    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<Map<String, Object>> handleEmailNotVerified(EmailNotVerifiedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "errorCode", "EMAIL_NOT_VERIFIED",
                "message", exception.getMessage(),
                "details", Map.of("email", exception.email())));
    }
}
