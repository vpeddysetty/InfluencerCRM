package com.influencer.dps.session;

import com.influencer.dps.config.DpsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the default {@link SessionStore}.
 *
 * <p>Declared on an {@code @Bean} method rather than by annotating the implementation: a
 * {@code @ConditionalOnMissingBean} on a {@code @Component} is evaluated during component scanning,
 * before that bean is registered, so it excludes itself and nothing is created.
 *
 * <p>The condition is what makes the Redis swap a drop-in — define a {@code SessionStore} bean
 * anywhere and this one steps aside, with no caller affected.
 */
@Configuration
public class SessionStoreConfig {

    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    public SessionStore inMemorySessionStore(DpsProperties properties) {
        return new InMemorySessionStore(properties);
    }
}
