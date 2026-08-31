package com.influencer.webe.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The envelope sender must be declared exactly once, with a non-blank default.
 *
 * <p><b>Why this test exists.</b> {@code application.properties} declared
 * {@code web-experience.email.from} twice: once defaulting to {@code no-reply@tejdux.com} and, ~75
 * lines later, once defaulting to blank. Spring keeps the LAST declaration, so the blank one won.
 * {@code SesEmailSender.isConfigured()} requires a non-blank {@code from} — it returns false without
 * one — so a deployment could hold valid SES keys, a verified domain and {@code provider=ses}, and
 * still send nothing. Every other setting looked correct, which is what made it expensive: the
 * failure is silent, and the symptom ("email doesn't work") points at the provider rather than at a
 * duplicated line in a config file.
 *
 * <p>A duplicate key is not a syntax error in a properties file and no compiler will object, so the
 * invariant has to be asserted here or not at all. The two assertions below are deliberately
 * different questions:
 *
 * <ul>
 *   <li><b>Declared once</b> — catches the reintroduction of a second declaration anywhere in the
 *       file, including one that happens to carry a working default. Two declarations are a bug even
 *       when the surviving value is right, because the next edit to the wrong block is silent.</li>
 *   <li><b>Non-blank default</b> — catches the case where the single remaining declaration is
 *       emptied, which reproduces the original outage without duplicating anything.</li>
 * </ul>
 *
 * <p>This reads the raw file rather than a Spring context on purpose: the point is what the file
 * says, and loading a context would resolve placeholders against the environment and hide it.
 */
class EmailFromPropertyTest {

    private static final String RESOURCE = "/application.properties";
    private static final String KEY = "web-experience.email.from";

    @Test
    @DisplayName("web-experience.email.from is declared exactly once")
    void declaredExactlyOnce() throws IOException {
        List<String> declarations = new ArrayList<>();
        for (String line : readLines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            // Match the key only up to its delimiter, so `...from.override=x` is not counted here.
            if (trimmed.startsWith(KEY + "=") || trimmed.startsWith(KEY + ":")) {
                declarations.add(trimmed);
            }
        }

        assertEquals(1, declarations.size(),
                "Expected exactly one declaration of " + KEY + " but found " + declarations.size()
                        + ": " + declarations + ". Spring keeps the last one, so a second "
                        + "declaration silently overrides the first — this is the defect that "
                        + "stopped SES sending while appearing configured.");
    }

    @Test
    @DisplayName("its default is not blank, so SesEmailSender can report itself configured")
    void defaultIsNotBlank() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " not found on the test classpath");
            properties.load(in);
        }

        String value = properties.getProperty(KEY);
        assertNotNull(value, KEY + " is not declared at all");

        // The value is a placeholder: ${WEBE_EMAIL_FROM:default}. The default is what a deployment
        // falls back to when the env var is unset, and a blank one is the outage.
        int separator = value.indexOf(':');
        String defaultValue = value.startsWith("${") && separator >= 0
                ? value.substring(separator + 1, value.length() - 1)
                : value;

        assertFalse(defaultValue.isBlank(),
                KEY + " resolves to a blank default. SesEmailSender.isConfigured() requires a "
                        + "non-blank from-address, so SES would accept the configuration and send "
                        + "nothing.");
    }

    private List<String> readLines() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " not found on the test classpath");
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .toList();
        }
    }
}
