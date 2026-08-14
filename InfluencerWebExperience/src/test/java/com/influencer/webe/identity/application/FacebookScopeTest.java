package com.influencer.webe.identity.application;

import com.influencer.webe.config.WebExperienceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the scope list sent to Meta, which is constrained by the app's TYPE rather than by anything
 * in this codebase.
 *
 * <p>Sign-in runs against a <b>Consumer</b>-type app, where {@code scope} is the mechanism and
 * {@code email,public_profile} is exactly what a sign-in needs. A <b>Business</b>-type app is not a
 * variation on that: Meta's documentation states {@code config_id} <em>replaces</em> {@code scope},
 * so a scope parameter there is refused with {@code Invalid Scopes: email} whatever it contains.
 *
 * <p>That distinction is the reason these assertions are worth writing down. An earlier attempt to
 * fix the Business-app rejection by ADDING {@code pages_show_list} to this list failed, because the
 * problem was never which permissions were listed — it was that the parameter itself is not how a
 * Business app asks. Instagram does need a Business app, but as a separate integration on a separate
 * app (roadmap M6); reading creator metrics and signing a brand owner in are not the same flow.
 */
class FacebookScopeTest {

    @Test
    @DisplayName("the default scope is the Consumer-login pair, with no business permissions")
    void defaultScopeIsTheConsumerPair() {
        String scope = new WebExperienceProperties.Facebook().getScope();

        assertTrue(scope.contains("email"), "email is what the account is keyed on");
        assertTrue(scope.contains("public_profile"), "public_profile supplies the display name");

        // A business permission here means someone is trying to make a Business app work by editing
        // the scope, which cannot succeed — that app type ignores scope in favour of config_id.
        for (String businessPermission : new String[] {
                "pages_show_list", "pages_read_engagement", "business_management", "instagram_basic"}) {
            assertFalse(
                    scope.contains(businessPermission),
                    "scope carries " + businessPermission + ", a business permission. If this is an "
                            + "attempt to satisfy a Business-type app, it will not work: config_id "
                            + "replaces scope there, and any scope is rejected as 'Invalid Scopes'. "
                            + "Sign-in belongs on a Consumer app.");
        }
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
                text.contains("FACEBOOK_OAUTH_SCOPE: email,public_profile"),
                "the compose template must request the same scopes as the in-code default; a "
                        + "deployment that quietly asks for something else fails at the consent screen");
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
