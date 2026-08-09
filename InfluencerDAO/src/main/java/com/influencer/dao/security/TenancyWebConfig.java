package com.influencer.dao.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers {@link SignedTenantArgumentResolver} ahead of Spring's own {@code @RequestParam}
 * binding.
 *
 * <p>Order is the whole point: custom resolvers are consulted before the built-in ones, so this
 * gets to answer for {@code brandId} first. Registered after them, Spring would already have bound
 * the caller's raw query parameter and this would never run.
 */
@Configuration
public class TenancyWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new SignedTenantArgumentResolver());
    }
}
