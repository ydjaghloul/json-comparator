package com.example.comparator.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Application-wide beans: configuration properties and the clock. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComparatorProperties.class)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
