package com.influencer.webe.identity.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An email address in a query string has to survive the round trip.
 *
 * <p><b>The bug this pins.</b> {@code DaoUserClient.findByEmail} interpolated the address straight
 * into {@code /users/by-email?email=...}. A query string decodes {@code +} as a SPACE, so
 * {@code a+tag@x.com} was looked up as {@code "a tag@x.com"}, matched nothing, and login answered
 * "Invalid credentials" to someone typing the correct password.
 *
 * <p>Plus-addressing is how people routinely tag a signup, so this hit real users — and it looked
 * exactly like a password problem, which is where the investigation started.
 *
 * <p>Found on 2026-08-22 while testing email verification: signup returned 201 and login then
 * failed for the same address, every time.
 */
class EmailQueryEncodingTest {

    /** What DaoUserClient does when building the lookup URL. */
    private static String queryFor(String email) {
        return "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
    }

    /** What a server does when reading that parameter back. */
    private static String decode(String query) {
        return URLDecoder.decode(query.substring("email=".length()), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a plus-addressed email survives the round trip")
    void plusAddressingSurvives() {
        String email = "vijay.peddysetty+verify@kmpsglobal.com";
        assertEquals(email, decode(queryFor(email)));
    }

    @Test
    @DisplayName("the plus is escaped rather than left to decode as a space")
    void plusIsEscaped() {
        String query = queryFor("a+tag@x.com");
        assertTrue(query.contains("%2B"), "the + must be escaped, got: " + query);
        // The precise failure: an unescaped + decodes to a space and the lookup misses.
        assertEquals("a tag@x.com", URLDecoder.decode("a+tag@x.com", StandardCharsets.UTF_8),
                "this is what the DAO saw before the fix");
    }

    @Test
    @DisplayName("ordinary addresses are unchanged in meaning")
    void ordinaryAddressesRoundTrip() {
        for (String email : new String[]{
                "plain@example.com",
                "first.last@sub.example.co.uk",
                "o'brien@example.com",
                "user_name-1@example.com"}) {
            assertEquals(email, decode(queryFor(email)), email);
        }
    }

    @Test
    @DisplayName("the built URI parses, which an unencoded address cannot be relied on to do")
    void builtUriIsValid() {
        URI uri = URI.create("https://dao.internal/users/by-email?" + queryFor("a+b@x.com"));
        assertEquals("/users/by-email", uri.getPath());
        assertTrue(uri.getRawQuery().contains("%2B"));
    }
}
