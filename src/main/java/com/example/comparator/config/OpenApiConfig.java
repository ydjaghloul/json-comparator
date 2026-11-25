package com.example.comparator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/** Swagger UI: http://localhost:8080/swagger-ui.html — OpenAPI JSON: http://localhost:8080/v3/api-docs */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI comparatorOpenApi() {
        return new OpenAPI().info(new Info()
                .title("REST Service Comparator")
                .version("0.9")
                .description("Calls two versions of a REST service and reports semantic JSON differences. "
                        + "Every endpoint accepts typeMatching=STRICT|LENIENT and nullMatching=STRICT|LENIENT."));
    }
}
