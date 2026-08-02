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
            "/s/**"                       // public creator landing pages — served to anonymous visitors
    };

    private static final String[] PUBLIC_POST_PATHS = {
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/google/signup",
            "/api/auth/facebook/signup",
            "/api/webhooks/**"            // marketplace callbacks — authenticated by provider signature
    };

    private static final String[] PUBLIC_OAUTH_PATHS = {
            "/api/auth/oauth/**"          // browser redirect flow; protected by the OAuth state parameter
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
