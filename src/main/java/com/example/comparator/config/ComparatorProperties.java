package com.example.comparator.config;

import com.example.comparator.model.NullMatching;
import com.example.comparator.model.TypeMatching;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
        comparison = comparison == null ? new Comparison(null, null, null, null, null, null) : comparison;
    }

    /**
     * Connection settings for one service. {@code headers} are extra headers sent on every call to the configured
     * base URL (values are secrets: only the names are ever printed). Credentials and {@code trustedBaseUrls} are only used for the source
     * service: {@code /compare/custom} sends credentials only to {@code baseUrl} or a trusted base URL.
     */
    public record ServiceProperties(
            String baseUrl,
            String clientId,
            String clientSecret,
            Duration connectTimeout,
            Duration readTimeout,
            List<String> trustedBaseUrls,
            Map<String, String> headers) {

        public ServiceProperties {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
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
                    + ", trustedBaseUrls=" + trustedBaseUrls
                    + ", headers=" + headers.keySet() + "]";
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
            List<UnorderedArray> unorderedArrays,
            List<String> idProperties) {

        /** Candidate id properties proposed when none are configured. */
        public static final List<String> DEFAULT_ID_PROPERTIES = List.of("id", "uuid", "guid", "key", "code", "sku");

        public Comparison(TypeMatching typeMatching, NullMatching nullMatching, List<String> ignorePaths,
                ArrayOrder arrayOrder, List<UnorderedArray> unorderedArrays) {
            this(typeMatching, nullMatching, ignorePaths, arrayOrder, unorderedArrays, null);
        }

        public Comparison {
            typeMatching = typeMatching == null ? TypeMatching.STRICT : typeMatching;
            nullMatching = nullMatching == null ? NullMatching.STRICT : nullMatching;
            ignorePaths = ignorePaths == null ? List.of() : List.copyOf(ignorePaths);
            arrayOrder = arrayOrder == null ? ArrayOrder.UNORDERED : arrayOrder;
            unorderedArrays = unorderedArrays == null ? List.of() : List.copyOf(unorderedArrays);
            idProperties = idProperties == null ? DEFAULT_ID_PROPERTIES : List.copyOf(idProperties);
        }
    }

    /**
     * An array compared without regard to element order.
     *
     * @param path JSON path of the array, e.g. {@code $.items} or {@code $.orders[*].lines}
     * @param key  optional field used to pair elements of the two arrays (e.g. {@code id}); when absent the id is
     *             detected among {@code comparison.id-properties}
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
