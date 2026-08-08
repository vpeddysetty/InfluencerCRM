package com.influencer.webe;

import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <p>{@code @EnableScheduling} is required by {@code HostingExpiryScheduler} (M5.6). The scheduled
 * job itself is still off unless {@code web-experience.hosting.expiry-warnings.enabled=true}, so
 * enabling the infrastructure here starts nothing on its own.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WebExperienceProperties.class)
public class InfluencerWebExperienceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InfluencerWebExperienceApplication.class, args);
    }
}
