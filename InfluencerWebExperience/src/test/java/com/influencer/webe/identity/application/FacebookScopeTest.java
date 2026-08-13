package com.influencer.webe.identity.application;

import com.influencer.webe.config.WebExperienceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the scope list sent to Meta, which is constrained by the app's TYPE rather than by anything
 * in this codebase.
 *
 * <p>TejDux is a Business-type app, so it uses <em>Facebook Login for Business</em>. That flavour
 * requires at least one business permission alongside {@code email} and {@code public_profile};
 * asking for only those two is refused before the consent screen with {@code Invalid Scopes: email}.
 * A Consumer-type app behaves the opposite way. The type is not interchangeable here — Business is
 * what the Instagram Graph API requires, and Instagram is the reason the Meta app exists.
 *
 * <p>{@code pages_show_list} is the permission chosen to satisfy the rule: no App Review at Standard
 * Access, and already on the submission list, because reaching an Instagram Business account goes
 * through the Facebook Page it is linked to.
 */
class FacebookScopeTest {

    @Test
    @DisplayName("the default scope carries a business permission, not just email and public_profile")
    void defaultScopeSatisfiesLoginForBusiness() {
        String scope = new WebExperienceProperties.Facebook().getScope();

        assertTrue(scope.contains("email"), "email is what the account is keyed on");
        assertTrue(scope.contains("public_profile"), "public_profile supplies the display name");
        assertTrue(
                scope.contains("pages_show_list"),
                "a Business-type app refuses email+public_profile alone with 'Invalid Scopes: email'; "
                        + "at least one business permission has to accompany them");
    }

    @Test
    @DisplayName("the deployed scope matches the default, so the two cannot silently drift")
    void deployedScopeMatchesTheDefault() throws IOException {
        Path compose = Path.of("..", "infrastructure", "test", "terraform", "templates",
                "docker-compose.yml.tftpl");
        if (!Files.exists(compose)) {
            // Built standalone, without the infrastructure tree checked out.
            return;
        }

        String text = Files.readString(compose, StandardCharsets.UTF_8);
        assertTrue(
                text.contains("FACEBOOK_OAUTH_SCOPE: email,public_profile,pages_show_list"),
                "the compose template must request the same scopes as the in-code default; a "
                        + "deployment that quietly asks for less fails at the consent screen");
    }

    @Test
    @DisplayName("a hand-written scope list is accepted with either separator")
    void separatorsAreTolerated() {
        // The property is edited by hand, and Meta's own docs use commas while OAuth generally uses
        // spaces. Both arrive here; neither should produce an empty or malformed permission.
        assertEquals("email,public_profile", normalize("email public_profile"));
        assertEquals("email,public_profile", normalize("email,public_profile"));
        assertEquals("email,public_profile", normalize("  email ,  public_profile , "));
    }

    /** Mirrors {@code OAuthFlowService#normalizeScopes}, which is private. */
    private String normalize(String configured) {
        String[] parts = configured.trim().split("[,\\s]+");
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(part);
        }
        return joined.toString();
    }
}
