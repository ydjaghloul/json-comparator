package com.example.comparator.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.example.comparator.config.ComparatorProperties;

/** One {@link RestClient} bean per configured service, each with its own base URL and timeouts. */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    /** Configured source service: always sends {@code client_id} / {@code client_secret} when they are set. */
    @Bean
    RestClient sourceRestClient(RestClientFactory factory, ComparatorProperties properties) {
        return factory.sourceClient(properties.sourceService().baseUrl());
    }

    @Bean
    RestClient targetRestClient(RestClientFactory factory, ComparatorProperties properties) {
        return factory.targetClient(properties.targetService().baseUrl(), properties.targetService());
    }
}
