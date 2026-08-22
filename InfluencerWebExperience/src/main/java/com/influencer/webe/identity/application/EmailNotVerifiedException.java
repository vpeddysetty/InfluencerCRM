package com.influencer.webe.identity.application;

import java.util.UUID;

/**
 * Sign-in refused because the address has not been proven.
 *
 * <p>A distinct type rather than a generic credentials failure, because the two need opposite
 * treatment. "Invalid credentials" is deliberately vague — telling someone which half was wrong
 * helps them guess. This one has to be specific: the person holds the right password, and the only
 * way out is a link in an inbox they may need resent. A vague error here strands a real customer.
 *
 * <p>It is thrown only <em>after</em> the password has been checked, so it never reveals whether an
 * address has an account.
 *
 * <p>Carries the user id so the API can offer a resend without a second lookup, and without the
 * client having to name a user it has not authenticated.
 */
public class EmailNotVerifiedException extends RuntimeException {

    private final UUID userId;
    private final String email;

    public EmailNotVerifiedException(UUID userId, String email) {
        super("Confirm your email address to finish signing in.");
        this.userId = userId;
        this.email = email;
    }

    public UUID userId() {
        return userId;
    }

    public String email() {
        return email;
    }
}
