package com.example.comparator;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(ComparatorProperties.class)
public class RestComparatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestComparatorApplication.class, args);
    }

    /** Same comparator (and settings) for live, file and inline comparisons. */
    @Bean
    JsonComparator jsonComparator(ComparatorProperties properties) {
        return new JsonComparator(properties.comparison());
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
