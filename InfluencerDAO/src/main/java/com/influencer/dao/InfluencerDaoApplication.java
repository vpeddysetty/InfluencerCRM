package com.influencer.dao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Drives the outbox relay. The relay itself is still gated behind
// influencrm.events.relay.enabled, so scheduling here starts nothing on its own.
@EnableScheduling
public class InfluencerDaoApplication {
    public static void main(String[] args) {
        SpringApplication.run(InfluencerDaoApplication.class, args);
    }
}
