package com.example.comparator.comparator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.comparator.config.ComparatorProperties;

@Configuration(proxyBeanMethods = false)
class ComparatorConfig {

    /** Same comparator (and settings) for live, file and inline comparisons. */
    @Bean
    JsonComparator jsonComparator(ComparatorProperties properties) {
        return new JsonComparator(properties.comparison());
    }
}
