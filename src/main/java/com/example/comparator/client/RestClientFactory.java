package com.example.comparator.client;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import com.example.comparator.config.ComparatorProperties;
import com.example.comparator.config.ComparatorProperties.ServiceProperties;

/**
 * Builds {@link RestClient}s from the auto-configured {@link RestClient.Builder} (a new prototype instance each
 * time), with a base URL, the timeouts of a service and, for source clients only, the credential headers.
 */
@Component
public class RestClientFactory {

    static final String CLIENT_ID_HEADER = "client_id";
    static final String CLIENT_SECRET_HEADER = "client_secret";

    private static final Logger log = LoggerFactory.getLogger(RestClientFactory.class);

    private final ObjectProvider<RestClient.Builder> builders;
    private final ObjectProvider<ClientHttpRequestFactoryBuilder<?>> factoryBuilder;
    private final ObjectProvider<ClientHttpRequestFactorySettings> factorySettings;
    private final ServiceProperties source;
    private final ServiceProperties target;
    private final Map<String, String> credentials;

    public RestClientFactory(ObjectProvider<RestClient.Builder> builders,
            ObjectProvider<ClientHttpRequestFactoryBuilder<?>> factoryBuilder,
            ObjectProvider<ClientHttpRequestFactorySettings> factorySettings, ComparatorProperties properties) {
        this.builders = builders;
        this.factoryBuilder = factoryBuilder;
        this.factorySettings = factorySettings;
        this.source = properties.sourceService();
        this.target = properties.targetService();
        this.credentials = credentialHeaders(source);
    }

    /** Client for a source base URL, with credentials only when the URL is trusted. */
    public RestClient sourceClient(String baseUrl) {
        return sourceClient(baseUrl, Map.of());
    }

    /**
     * Client for a source base URL. Credentials and the configured {@code source_service.headers} are only sent to
     * a trusted URL; {@code requestHeaders} (given by the caller for this call) are always sent and win over the
     * configured ones with the same name.
     */
    public RestClient sourceClient(String baseUrl, Map<String, String> requestHeaders) {
        boolean trusted = isTrustedSourceUrl(baseUrl);
        if (!trusted) {
            log.warn("Source base URL {} is not source_service.base-url nor in source_service.trusted-base-urls: "
                    + "calling it without credentials", baseUrl);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (trusted) {
            headers.putAll(source.headers());
            headers.putAll(credentials);
        }
        return create(baseUrl, source, merge(headers, requestHeaders));
    }

    /** Client for a target base URL: never any credentials. */
    public RestClient targetClient(String baseUrl, ServiceProperties timeouts) {
        return targetClient(baseUrl, timeouts, Map.of());
    }

    /**
     * Client for a target base URL: never credentials. The configured {@code target_service.headers} are only sent
     * to {@code target_service.base-url}; {@code requestHeaders} are always sent.
     */
    public RestClient targetClient(String baseUrl, ServiceProperties timeouts, Map<String, String> requestHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (normalize(baseUrl) != null && normalize(baseUrl).equals(normalize(target.baseUrl()))) {
            headers.putAll(timeouts.headers());
        }
        return create(baseUrl, timeouts, merge(headers, requestHeaders));
    }

    /** Request headers replace configured ones with the same name, whatever the letter case. */
    private static Map<String, String> merge(Map<String, String> base, Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        overrides.forEach((name, value) -> {
            merged.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            merged.put(name, value);
        });
        return merged;
    }

    boolean isTrustedSourceUrl(String baseUrl) {
        String candidate = normalize(baseUrl);
        if (candidate == null) {
            return false;
        }
        if (candidate.equals(normalize(source.baseUrl()))) {
            return true;
        }
        return source.trustedBaseUrls().stream().map(RestClientFactory::normalize).anyMatch(candidate::equals);
    }

    private RestClient create(String baseUrl, ServiceProperties timeouts, Map<String, String> headers) {
        // Paths are appended to the base URL as written: no re-encoding of what is already in the path.
        DefaultUriBuilderFactory uriFactory = new DefaultUriBuilderFactory(baseUrl);
        uriFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);

        // Start from the Spring Boot HTTP client settings (spring.http.client.*) and apply the service timeouts.
        ClientHttpRequestFactorySettings settings = factorySettings
                .getIfAvailable(ClientHttpRequestFactorySettings::defaults)
                .withConnectTimeout(timeouts.connectTimeout())
                .withReadTimeout(timeouts.readTimeout());

        RestClient.Builder builder = builders.getObject()
                .uriBuilderFactory(uriFactory)
                .requestFactory(factoryBuilder.getIfAvailable(ClientHttpRequestFactoryBuilder::detect).build(settings));
        headers.forEach((name, value) -> builder.defaultHeader(name, value));
        return builder.build();
    }

    private static Map<String, String> credentialHeaders(ServiceProperties source) {
        Map<String, String> headers = new LinkedHashMap<>();
        addIfSet(headers, CLIENT_ID_HEADER, source.clientId(), "source_service.client-id (CLIENT_ID)");
        addIfSet(headers, CLIENT_SECRET_HEADER, source.clientSecret(), "source_service.client-secret (CLIENT_SECRET)");
        return Map.copyOf(headers);
    }

    private static void addIfSet(Map<String, String> headers, String header, String value, String setting) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            log.warn("{} is not set: header '{}' will not be sent to source_service", setting, header);
            return;
        }
        headers.put(header, value);
    }

    /** Scheme and host lower-cased, trailing slashes removed; null when the value is not an absolute URL. */
    static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port
                    + path + query;
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
}
