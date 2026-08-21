package com.influencer.webe.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one property whose value has to be a name a BROWSER can resolve.
 *
 * <p>{@code web-experience.dps-base-url} has exactly one consumer:
 * {@link OAuthFlowService}'s {@code redirectToDps}, which puts it in a 302 {@code Location} header.
 * That makes it an instruction to the browser, not a server-to-server address — and it therefore
 * cannot be a Docker service name, however much it looks like its neighbours in the compose file.
 *
 * <p>It was {@code http://dps:8090} in production until 2026-08-13. Every social sign-in completed
 * successfully and then dead-ended: the provider authenticated the user, the callback exchanged the
 * code and created the session, and the last redirect pointed the browser at a hostname that exists
 * only inside the container network. Facebook rendered that as a {@code /dialog/close/} URL, which
 * reads like a rejection by Meta and is nothing of the sort.
 *
 * <p>This asserts against the deployment template rather than mocking the service, because the bug
 * was never in the Java: {@code redirectToDps} did exactly what it was told. Only the configuration
 * was wrong, so only the configuration is worth guarding.
 */
class OAuthRedirectTargetTest {

    private static final Path COMPOSE_TEMPLATE = Path.of(
            "..", "infrastructure", "test", "terraform", "templates", "docker-compose.yml.tftpl");

    @Test
    @DisplayName("the DPS redirect target is a browser-reachable origin, not a container hostname")
    void dpsBaseUrlIsNotAServiceName() throws IOException {
        if (!Files.exists(COMPOSE_TEMPLATE)) {
            // The BFF is also built standalone, where the infrastructure tree is not checked out.
            // Skipping beats failing on a path that says nothing about this module's correctness.
            return;
        }

        String compose = Files.readString(COMPOSE_TEMPLATE, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("^\\s*WEBE_DPS_BASE_URL:\\s*(\\S+)\\s*$", Pattern.MULTILINE)
                .matcher(compose);

        assertTrue(matcher.find(), "WEBE_DPS_BASE_URL must be set for the web-experience service");
        String value = matcher.group(1);

        // The failure mode this exists for: any http://<service-name>:<port> form. Checking for the
        // literal previous value would pass the moment someone wrote http://dps:8091 instead.
        assertFalse(
                value.matches("http://[a-z0-9-]+:\\d+"),
                "WEBE_DPS_BASE_URL is " + value + ", which is a container-internal hostname. It ends "
                        + "up in a 302 Location header, so a browser has to be able to resolve it — "
                        + "use the public origin (${public_base_url}); Caddy routes /dps/* to the DPS.");
    }
}
