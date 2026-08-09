package com.influencer.dps.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.dps.config.DpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Chooses where sessions live.
 *
 * <p>Redis when {@code dps.session-store=redis}, Caffeine otherwise. The choice is explicit rather
 * than inferred from whether Redis happens to be reachable: silently degrading to in-memory in a
 * multi-instance deployment would appear to work and then log users out at random — the worst
 * failure mode available, because it presents as a mysterious application bug rather than a missing
 * dependency.
 *
 * <p>Both conditions sit on {@code @Bean} methods. A {@code @ConditionalOnMissingBean} on a
 * {@code @Component} is evaluated during component scanning, before that bean is registered, so it
 * excludes itself and nothing is created.
 */
@Configuration
public class SessionStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreConfig.class);

    /**
     * Redis-backed sessions, shared by every instance.
     *
     * <p>Connectivity is verified at startup rather than on first login: discovering an unreachable
     * Redis when a user tries to sign in is considerably worse than failing to start.
     */
    @Bean
    @ConditionalOnProperty(name = "dps.session-store", havingValue = "redis")
    public SessionStore redisSessionStore(StringRedisTemplate redis,
                                          ObjectMapper objectMapper,
                                          DpsProperties properties) {
        try {
            redis.hasKey("dps:startup-probe");
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "dps.session-store=redis but Redis is unreachable. Refusing to start rather "
                            + "than falling back to in-memory sessions, which would log users out "
                            + "whenever the load balancer moved them.", exception);
        }
        return new RedisSessionStore(redis, objectMapper, properties);
    }

    /**
     * In-memory sessions. Correct for a single instance and nothing more.
     *
     * <p>{@code @ConditionalOnMissingBean} means declaring any other {@code SessionStore} — Redis
     * above, or something else entirely — replaces this with no caller changing.
     */
    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    public SessionStore inMemorySessionStore(DpsProperties properties) {
        log.info("Using in-memory sessions. Set dps.session-store=redis before running more than "
                + "one DPS instance.");
        return new InMemorySessionStore(properties);
    }
}
