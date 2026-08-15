package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consent is asked for at sign-up, not at every sign-in — and the flow that skips the question must
 * not be able to create an account.
 *
 * <p>Both halves matter, and they are the same rule seen from two sides. Demanding consent on every
 * social sign-in re-asks a question answered at registration, which trains people to tick without
 * reading and — because the Log in tab has no checkbox — refused the flow outright with
 * {@code "You must accept the Terms of Service and Privacy Policy to continue"}. But simply skipping
 * the check would be worse: {@code signupWithSocial} doubles as sign-in and creates an account when
 * it finds none, so a sign-in that consented to nothing could register someone silently.
 *
 * <p>So {@code signInOnly} is not permission to skip consent. It is a promise that no account will
 * be created, and {@code completeSocial} is where the promise is kept.
 */
class SignInConsentTest {

    private String read(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/influencer/webe", relativePath), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a sign-in does not have consent demanded of it")
    void signInSkipsTheConsentGate() throws IOException {
        String flow = read("identity/application/OAuthFlowService.java");

        // Guarded, not removed: a SIGN-UP must still be refused before the redirect, which is the
        // last moment a refusal costs the user nothing.
        assertTrue(
                flow.contains("if (!signInOnly) {\n            consentService.requireAccepted(acceptedTerms);"),
                "consent must be required for sign-up and skipped for sign-in");
    }

    @Test
    @DisplayName("a sign-in refuses to create an account rather than registering without consent")
    void signInCannotCreateAnAccount() throws IOException {
        String flow = read("identity/application/OAuthFlowService.java");

        assertTrue(flow.contains("request.signInOnly() && !authService.hasAccountFor(provider, accessToken)"),
                "a sign-in-only flow must check the account exists before signing anyone in");
        assertTrue(flow.contains("No account found for that "),
                "and refuse with something the user can act on");

        // The check has to precede account creation, or the refusal happens after the thing it was
        // meant to prevent.
        int check = flow.indexOf("request.signInOnly() && !authService.hasAccountFor");
        int signup = flow.indexOf("authService.signupWithGoogle(");
        assertTrue(check > -1 && signup > -1 && check < signup,
                "the existence check must run BEFORE signupWithSocial, which creates on miss");
    }

    @Test
    @DisplayName("an existing account with no consent on record gets one at sign-in")
    void missingConsentIsBackfilledOnSignIn() throws IOException {
        String flow = read("identity/application/OAuthFlowService.java");
        String consent = read("identity/application/ConsentService.java");

        assertTrue(flow.contains("consentService.recordIfMissing("),
                "an account that never recorded consent should record it when its owner signs in");
        assertTrue(consent.contains("public boolean recordIfMissing("),
                "ConsentService must expose the backfill");

        // A no-op when consent already exists. Writing on every sign-in would log a fresh act of
        // consent that never happened, which is worse than the gap it set out to close.
        assertTrue(consent.contains("if (existing != null && existing.isArray() && !existing.isEmpty()) {"),
                "the backfill must not re-record consent that already exists");
    }

    @Test
    @DisplayName("the intent survives the redirect server-side, not in a value the browser can flip")
    void intentIsHeldServerSide() throws IOException {
        String state = read("identity/application/OAuthStateService.java");

        // Same reasoning as the consent flag: "this was only a sign-in" is exactly the claim that
        // would let someone register without agreeing to anything, so it cannot round-trip through
        // the browser in the state parameter.
        assertTrue(state.contains("boolean signInOnly,"),
                "signInOnly belongs on the server-side pending request");
    }
}
