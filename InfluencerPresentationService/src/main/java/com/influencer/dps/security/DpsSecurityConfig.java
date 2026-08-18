package com.influencer.dps.security;

import com.influencer.dps.config.DpsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security for the DPS.
 *
 * <p>Two things matter here, and both follow from using cookies instead of bearer tokens.
 *
 * <p><strong>CORS must name every origin.</strong> A cookie is only sent cross-origin when
 * {@code Access-Control-Allow-Credentials} is true, and the specification forbids combining that
 * with {@code *}. The micro-frontend origins therefore have to be enumerated — a constraint, not a
 * precaution, though it happens to be the safer design anyway.
 *
 * <p><strong>CSRF protection becomes necessary.</strong> Bearer tokens are immune by construction:
 * a browser will not attach one automatically. Cookies are attached automatically, so a malicious
 * page could otherwise make authenticated requests on the user's behalf. The double-submit cookie
 * pattern restores the guarantee that was previously free.
 */
@Configuration
@EnableWebSecurity
public class DpsSecurityConfig {

    private final DpsProperties properties;

    public DpsSecurityConfig(DpsProperties properties) {
        this.properties = properties;
    }

    /**
     * The CSRF cookie, carrying the SAME Domain as the session cookie.
     *
     * <p><b>Why this is not the default.</b> Spring writes the token host-only, so on the deployed
     * stack it landed on {@code api.tejdux.com} while the SPA runs on {@code www.tejdux.com}. The
     * session cookie is shared across both because {@code dps.cookie-domain} puts it on
     * {@code .tejdux.com}; the token was not, so {@code document.cookie} was empty where the app
     * actually reads it. The header could never be sent, and every state-changing call through
     * {@code /dps/api/**} came back 403 with no body — a handle lookup, adding a brand and renaming
     * a workspace all failed identically, and none of them for the reason they appeared to.
     *
     * <p>Only a cookie-session user hit this. A bearer session sends no CSRF header at all and is
     * not asked to, so the failure was invisible to password sign-in and to every same-origin test.
     *
     * <p>The domain is applied only when configured, for the reason spelled out on the session
     * cookie: an empty Domain attribute is invalid and makes a browser drop the cookie outright,
     * which would break local development instead of merely leaving it host-only.
     */
    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        String domain = properties.getCookieDomain();
        if (domain != null && !domain.isBlank()) {
            String trimmed = domain.trim();
            repository.setCookieCustomizer(cookie -> cookie.domain(trimmed));
        }
        return repository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // The SPA reads the token from the cookie and echoes it in a header, so the raw value is
        // what must be compared.
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        // Readable by JavaScript on purpose: the SPA has to echo it back. This is
                        // the double-submit pattern — knowing the value proves same-origin script
                        // execution, which a cross-site attacker cannot achieve.
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(csrfHandler)
                        // Login and signup have no session to protect yet, and requiring a token
                        // before one exists would make first sign-in impossible. The OAuth legs are
                        // GET redirects arriving from the provider, which cannot carry a CSRF token
                        // — and have no session to protect either, for the same reason.
                        .ignoringRequestMatchers(
                                "/dps/auth/login",
                                "/dps/auth/signup",
                                "/dps/auth/oauth/**"))
                .sessionManagement(session -> session
                        // Sessions live in the DPS's own store, not in an HttpSession. Letting the
                        // container create one too would give us two competing session concepts.
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // These endpoints perform their own session checks and must be reachable
                        // anonymously: /session is how the SPA discovers it is *not* logged in.
                        .requestMatchers("/dps/auth/**", "/dps/session", "/actuator/health").permitAll()
                        .anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Requested-With"));
        // Without this the browser drops the session cookie on every cross-origin call, and the
        // whole design silently stops working.
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
