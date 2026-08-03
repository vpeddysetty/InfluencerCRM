package com.influencer.webe.security;

import com.influencer.webe.config.WebExperienceProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Locks the BFF down to authenticated callers, with a small, explicit public allowlist.
 *
 * <p>Before this existed, every endpoint was reachable unauthenticated and tenancy was taken from
 * whatever {@code userId} the caller supplied. Authentication is now mandatory by default —
 * {@code anyRequest().authenticated()} — so a newly added endpoint is protected unless someone
 * deliberately adds it to the allowlist below.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Endpoints that must remain reachable without a user token, and why. */
    private static final String[] PUBLIC_GET_PATHS = {
            "/health",
            "/s/**",                      // public creator landing pages — served to anonymous visitors
            // Public keys, by definition. Every JWKS endpoint is unauthenticated; the response
            // contains only public halves and is what lets another service verify tokens itself.
            "/.well-known/jwks.json"
    };

    private static final String[] PUBLIC_POST_PATHS = {
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/google/signup",
            "/api/auth/facebook/signup",
            // Redeemed by the DPS mid-OAuth, when it holds no user token yet — so this cannot
            // require one. The 256-bit single-use code IS the credential: it is unguessable, valid
            // for 60 seconds, consumed on first read, and issued only to the provider's redirect.
            // Same reasoning as /api/auth/refresh, which is public for the same structural reason.
            "/api/auth/oauth/handoff",
            "/api/webhooks/**",           // marketplace callbacks — authenticated by provider signature
            // Creator portal sign-in. A creator has no account, no brand and no account_role, so
            // they can never present the operator JWT this chain expects — these routes are how
            // they obtain their own portal token instead. Listed individually rather than as
            // /api/creator-portal/**, which would also expose the claim and collaboration routes
            // that must stay behind a creator session.
            "/api/creator-portal/auth/signup",
            "/api/creator-portal/auth/login",
            "/api/creator-portal/auth/logout"
    };

    /**
     * Creator-portal routes authenticated by {@code X-Creator-Token} rather than the operator JWT.
     *
     * <p>Permitted by this filter chain and then checked inside the controller, which resolves the
     * portal session itself. The alternative — teaching {@code JwtAuthenticationFilter} about a
     * second credential type — would put creator rules inside the operator auth path, which is
     * precisely where they must not be.
     */
    private static final String[] CREATOR_PORTAL_PATHS = {
            "/api/creator-portal/me",
            "/api/creator-portal/collaborations",
            "/api/creator-portal/claims"
    };

    /**
     * The browser-redirect legs of the OAuth flow, which arrive with no credential by nature: the
     * user is being bounced here by the provider. Protected by the OAuth state parameter.
     *
     * <p>Listed individually rather than as {@code /api/auth/oauth/**}. The wildcard would sweep in
     * any future endpoint under that prefix by default — including
     * {@code /api/auth/oauth/handoff}, which exchanges a code for real tokens. That one is
     * deliberately allowed above, on the strength of its own single-use credential; the point here
     * is that it should be an explicit decision rather than something a path prefix grants
     * silently.
     */
    private static final String[] PUBLIC_OAUTH_PATHS = {
            "/api/auth/oauth/google/start",
            "/api/auth/oauth/google/callback",
            "/api/auth/oauth/facebook/start",
            "/api/auth/oauth/facebook/callback"
    };

    private final WebExperienceProperties properties;

    public SecurityConfig(WebExperienceProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                // Stateless bearer-token API: there is no session or login form for CSRF to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Spring re-runs the filter chain on the ERROR dispatch, where
                        // OncePerRequestFilter skips itself — leaving the forwarded request
                        // unauthenticated. Without this, a controller's honest 404 comes back to the
                        // caller as a confusing 403. Authorization already ran on the REQUEST dispatch.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
                        .requestMatchers(PUBLIC_OAUTH_PATHS).permitAll()
                        // Authenticated by the controller against the creator session store, not
                        // by this chain — see CREATOR_PORTAL_PATHS.
                        .requestMatchers(CREATOR_PORTAL_PATHS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint())
                        .accessDeniedHandler(new JsonAccessDeniedHandler()));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        String uiOrigin = properties.getUiBaseUrl();
        configuration.setAllowedOrigins(uiOrigin == null || uiOrigin.isBlank()
                ? List.of("http://localhost:5173")
                : List.of(uiOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Brand-Id"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
