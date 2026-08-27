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
            // Phase H. A liveness probe cannot hold a token, so this has to be reachable
            // unauthenticated. `show-details=when-authorized` keeps the response to a bare
            // status for anonymous callers — an unauthenticated probe learns "up", not which
            // dependency is failing. The richer actuator endpoints stay authenticated.
            "/actuator/health",
            "/s/**",                      // public creator landing pages — served to anonymous visitors
            // Asset bytes referenced BY those landing pages. They must load for anonymous
            // visitors or every published page renders with broken images. Keys are random
            // UUIDs under a brand prefix, so an asset cannot be enumerated or guessed — being
            // shown a page that references it is the only way to learn its URL.
            "/assets/**",
            // The operator's deletion approval link, clicked from an email client that holds no
            // session. The 256-bit single-use token IS the credential -- same structural reason as
            // /api/auth/verify-email below. It expires after 7 days and every refusal (unknown
            // token, expired, already used, requester owns a workspace) happens before anything is
            // destroyed.
            "/api/deletion-requests/approve",
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
            // SNS posts here when SES receives a deletion request. It cannot hold a session or a
            // bearer token: the caller is AWS infrastructure. The protections are the subscription
            // handshake, a check that SubscribeURL names an sns.amazonaws.com host under this
            // account, and the fact that the endpoint only RECORDS and notifies -- it deletes
            // nothing, so a forged notification costs an operator one email, not any data.
            "/api/deletion-requests",
            // Both unauthenticated by necessity: the holder cannot sign in yet, which is the whole
            // reason they are here. The 256-bit single-use token IS the credential for verify, and
            // resend answers identically for every address so it reveals nothing.
            "/api/auth/verify-email",
            "/api/auth/verify-email/resend",
            // Creator signup from a published landing page. A creator applying to a campaign has
            // no account, so this cannot require a token. The owning brand is derived from the
            // page slug (never from the body), only published pages accept signups, and the row
            // is created as status=lead — so this grants nothing without a brand decision.
            "/api/public/landing/*/signup",
            "/api/webhooks/**",           // marketplace callbacks — authenticated by provider signature
            // Subscription events (M2.2). Unauthenticated by necessity — Stripe holds no user
            // token — so the HMAC signature over the raw body IS the authentication, verified in
            // BillingWebhookController before anything is parsed or applied. With no signing
            // secret configured the endpoint refuses everything with 503 rather than lying open.
            "/api/billing/webhooks/**",
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
     * <p><b>These require authentication (PR-40).</b> They used to be {@code permitAll()} here and
     * checked inside each controller instead, which worked only for as long as every author
     * remembered to write the check — and a forgotten one is not a wrong answer, it is an
     * unauthenticated endpoint serving unpublished pages, indistinguishable from a correct handler
     * on review. {@link CreatorTokenAuthenticationFilter} now resolves the header, so a handler
     * that forgets its check <b>fails closed</b> rather than open.
     *
     * <p>The controller checks stay: this chain answers "is this a creator at all", and the
     * controllers answer "is it THIS creator's page", which is a question no filter can answer.
     *
     * <p>Signup, login and logout are deliberately NOT in this list — they are the paths that
     * mint the credential, so requiring it would make the portal unreachable. They sit in
     * {@code PUBLIC_POST_PATHS} above, enumerated individually rather than as a wildcard for the
     * reason given there.
     */
    private static final String[] CREATOR_PORTAL_PATHS = {
            "/api/creator-portal/me",
            "/api/creator-portal/collaborations",
            "/api/creator-portal/claims",
            // Phase G co-editing. Listed explicitly, like the rest: the controller resolves the
            // portal session and then checks a collaborator grant against a confirmed identity
            // link, so permitting the path here grants nothing on its own.
            "/api/creator-portal/pages",
            "/api/creator-portal/pages/*"
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
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           CreatorTokenAuthenticationFilter creatorTokenAuthenticationFilter)
            throws Exception {
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
                        // PR-40: authenticated, not permitAll. CreatorTokenAuthenticationFilter
                        // resolves X-Creator-Token into an authentication, so a creator handler
                        // that forgets its own check is refused here instead of serving data.
                        .requestMatchers(CREATOR_PORTAL_PATHS).authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // AFTER the operator filter: it establishes the richer credential, and the creator
                // filter deliberately declines to overwrite an existing authentication. Ordering
                // them the other way round would let a request carrying both be resolved as a
                // creator, which is a downgrade the operator paths would then refuse confusingly.
                .addFilterAfter(creatorTokenAuthenticationFilter, JwtAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint())
                        .accessDeniedHandler(new JsonAccessDeniedHandler()));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // A LIST, because the same site is served from more than one hostname. CloudFront answers on
        // both tejdux.com and www.tejdux.com, and only the apex was allowed here — so a visitor who
        // typed the www prefix loaded the page, ticked the consent box, pressed Create workspace,
        // and had the request blocked before it left the browser. The UI reported "Failed to fetch",
        // which reads like a network fault rather than a configuration one.
        //
        // No wildcard is possible: allowCredentials is true below, and the CORS spec forbids
        // Access-Control-Allow-Origin: * alongside credentials. Enumerating origins is the only
        // correct form, which is why this parses a list rather than taking a single value.
        String uiOrigin = properties.getUiBaseUrl();
        configuration.setAllowedOrigins(uiOrigin == null || uiOrigin.isBlank()
                ? List.of("http://localhost:5173")
                : java.util.Arrays.stream(uiOrigin.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Creator-Token added for OP-18. The creator portal authenticates with it instead of a
        // bearer, and it will be served from its own origin, so without it here every creator
        // request fails at the preflight — as a CORS error in the browser console, with nothing
        // in the server logs at all, which is the most misleading way for this to break.
        configuration.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Brand-Id", "X-Creator-Token"));
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
