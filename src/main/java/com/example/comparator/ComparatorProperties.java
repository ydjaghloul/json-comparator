package com.example.comparator;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the root-level application configuration ({@code source_service}, {@code target_service},
 * {@code paths}, {@code output}, {@code comparison}).
 */
@ConfigurationProperties
public record ComparatorProperties(
        ServiceProperties sourceService,
        ServiceProperties targetService,
        List<String> paths,
        Output output,
        Comparison comparison) {

    public ComparatorProperties {
        if (sourceService == null || isBlank(sourceService.baseUrl())) {
            throw new IllegalArgumentException("source_service.base-url must be configured");
        }
        if (targetService == null || isBlank(targetService.baseUrl())) {
            throw new IllegalArgumentException("target_service.base-url must be configured");
        }
        paths = paths == null ? List.of() : List.copyOf(paths);
        output = output == null ? new Output(null) : output;
        comparison = comparison == null ? new Comparison(null, null, null, null, null) : comparison;
    }

    /**
     * Connection settings for one service. Credentials and {@code trustedBaseUrls} are only used for the source
     * service: {@code /compare/custom} sends credentials only to {@code baseUrl} or a trusted base URL.
     */
    public record ServiceProperties(
            String baseUrl,
            String clientId,
            String clientSecret,
            Duration connectTimeout,
            Duration readTimeout,
            List<String> trustedBaseUrls) {

        public ServiceProperties {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
            trustedBaseUrls = trustedBaseUrls == null ? List.of() : List.copyOf(trustedBaseUrls);
        }

        /** Never expose credentials, even when the record is logged by accident. */
        @Override
        public String toString() {
            return "ServiceProperties[baseUrl=" + baseUrl
                    + ", clientId=" + (isBlank(clientId) ? "<unset>" : "****")
                    + ", clientSecret=" + (isBlank(clientSecret) ? "<unset>" : "****")
                    + ", connectTimeout=" + connectTimeout
                    + ", readTimeout=" + readTimeout
                    + ", trustedBaseUrls=" + trustedBaseUrls + "]";
        }
    }

    /** Root folder for saved responses, also the only folder offline comparison may read from. */
    public record Output(String directory) {

        public Output {
            directory = isBlank(directory) ? "./output" : directory;
        }
    }

    /** Settings shared by every comparison (live, files and inline JSON). */
    public record Comparison(
            TypeMatching typeMatching,
            NullMatching nullMatching,
            List<String> ignorePaths,
            ArrayOrder arrayOrder,
            List<UnorderedArray> unorderedArrays) {

        public Comparison {
            typeMatching = typeMatching == null ? TypeMatching.STRICT : typeMatching;
            nullMatching = nullMatching == null ? NullMatching.STRICT : nullMatching;
            ignorePaths = ignorePaths == null ? List.of() : List.copyOf(ignorePaths);
            arrayOrder = arrayOrder == null ? ArrayOrder.ORDERED : arrayOrder;
            unorderedArrays = unorderedArrays == null ? List.of() : List.copyOf(unorderedArrays);
        }
    }

    /**
     * An array compared without regard to element order.
     *
     * @param path JSON path of the array, e.g. {@code $.items} or {@code $.orders[*].lines}
     * @param key  optional field used to pair elements of the two arrays (e.g. {@code id})
     */
    public record UnorderedArray(String path, String key) {
    }

    public enum ArrayOrder {
        ORDERED, UNORDERED
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
