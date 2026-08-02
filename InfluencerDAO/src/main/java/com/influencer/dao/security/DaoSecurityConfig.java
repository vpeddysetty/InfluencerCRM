package com.influencer.dao.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Restricts the DAO to authenticated service callers.
 *
 * <p>There is no user-facing surface here — the DAO must only ever be called by the BFF, never by a
 * browser. {@link ServiceTokenFilter} performs the check; this configuration ensures nothing slips
 * past it.
 */
@Configuration
@EnableWebSecurity
public class DaoSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ServiceTokenFilter serviceTokenFilter) throws Exception {
        http
                // Stateless service-to-service API authenticated by a header credential, not a cookie.
                .csrf(AbstractHttpConfigurer::disable)
                // No browser origin should ever reach the DAO directly.
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Spring re-runs the filter chain on the ERROR dispatch. OncePerRequestFilter
                        // deliberately skips itself on that second pass, so the forwarded request has
                        // no authentication and would be rejected — turning an honest 404 from a
                        // controller into a misleading 403. Authorization already happened on the
                        // original REQUEST dispatch; the error view itself carries no data.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        // Must mirror ServiceTokenFilter#shouldNotFilter: skipping the filter leaves
                        // the request anonymous, so it needs permitAll here too or it 403s.
                        .requestMatchers("/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(serviceTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
