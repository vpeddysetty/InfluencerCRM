package com.influencer.webe;

import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WebExperienceProperties.class)
public class InfluencerWebExperienceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InfluencerWebExperienceApplication.class, args);
    }
}
