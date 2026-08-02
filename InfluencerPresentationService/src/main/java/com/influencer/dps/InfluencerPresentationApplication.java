package com.influencer.dps;

import com.influencer.dps.config.DpsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Digital Presentation Service.
 *
 * <p>The single authentication and authorization entry point for every micro-frontend origin. Holds
 * the browser session server-side so no token reaches JavaScript, brokers authentication to the
 * Identity context through the BFF, and hosts the login-time cache.
 */
@SpringBootApplication
@EnableConfigurationProperties(DpsProperties.class)
public class InfluencerPresentationApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluencerPresentationApplication.class, args);
    }
}
