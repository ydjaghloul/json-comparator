package com.example.comparator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Layers (sub packages): {@code controller} (HTTP API), {@code service} (use cases, storage), {@code client}
 * (calls to the compared services), {@code comparator} (JSON comparison), {@code model} (options, report),
 * {@code config} (properties and beans).
 */
@SpringBootApplication
public class RestComparatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestComparatorApplication.class, args);
    }
}
