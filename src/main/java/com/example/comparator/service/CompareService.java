package com.example.comparator.service;

import com.example.comparator.client.RestClientFactory;
import com.example.comparator.comparator.JsonComparator;
import com.example.comparator.config.ComparatorProperties;
import com.example.comparator.model.CompareOptions;
import com.example.comparator.model.CompareReport;
import com.example.comparator.model.Difference;
import com.example.comparator.model.PathResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import com.example.comparator.service.FileStorageService.RunFolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

/**
 * Runs comparisons: live (configured or request-supplied services, every path), uploaded (two JSON files) and
 * inline (two JSON documents). All use the same {@link JsonComparator} and produce the same report.
 */
@Service
public class CompareService {

    static final String SOURCE_SERVICE = "source_service";
    static final String TARGET_SERVICE = "target_service";

    private static final Logger log = LoggerFactory.getLogger(CompareService.class);

    private final RestClient sourceClient;
    private final RestClient targetClient;
    private final ComparatorProperties properties;
    private final JsonComparator comparator;
    private final FileStorageService storage;
    private final RestClientFactory clientFactory;
    private final ObjectReader jsonReader;

    public CompareService(@Qualifier("sourceRestClient") RestClient sourceClient,
            @Qualifier("targetRestClient") RestClient targetClient, ComparatorProperties properties,
            JsonComparator comparator, FileStorageService storage, RestClientFactory clientFactory,
            ObjectMapper objectMapper) {
        this.sourceClient = sourceClient;
        this.targetClient = targetClient;
        this.properties = properties;
        this.comparator = comparator;
        this.storage = storage;
        this.clientFactory = clientFactory;
        // Reject bodies with trailing garbage after the JSON value.
        this.jsonReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Options to use: the requested ones, completed with the configured defaults. */
    public CompareOptions resolve(CompareOptions requested) {
        return requested == null ? comparator.defaults() : requested.withDefaults(comparator.defaults());
    }

    /** Calls the configured services for every configured path. A failing path never stops the run. */
    public CompareReport compareAll(CompareOptions options) {
        return compareAll(options, Map.of(), Map.of());
    }

    /** Same, with extra headers for this run only (sent on top of the configured ones). */
    public CompareReport compareAll(CompareOptions options, Map<String, String> sourceHeaders,
            Map<String, String> targetHeaders) {
        if (sourceHeaders.isEmpty() && targetHeaders.isEmpty()) {
            return compareLive(sourceClient, targetClient, properties.sourceService().baseUrl(),
                    properties.targetService().baseUrl(), properties.paths(), resolve(options));
        }
        return compareLive(clientFactory.sourceClient(properties.sourceService().baseUrl(), sourceHeaders),
                clientFactory.targetClient(properties.targetService().baseUrl(), properties.targetService(),
                        targetHeaders),
                properties.sourceService().baseUrl(), properties.targetService().baseUrl(), properties.paths(),
                resolve(options));
    }

    /**
     * Calls the given services for the given paths. Clients are built like the configured ones; credentials are
     * only sent to a trusted source URL (see {@link RestClientFactory#sourceClient(String)}).
     */
    public CompareReport compareCustom(String sourceBaseUrl, String targetBaseUrl, List<String> paths,
            CompareOptions options) {
        return compareCustom(sourceBaseUrl, targetBaseUrl, paths, options, Map.of(), Map.of());
    }

    /** Same, with headers given for this call: {@code sourceHeaders} to the source, {@code targetHeaders} to the target. */
    public CompareReport compareCustom(String sourceBaseUrl, String targetBaseUrl, List<String> paths,
            CompareOptions options, Map<String, String> sourceHeaders, Map<String, String> targetHeaders) {
        RestClient source = clientFactory.sourceClient(sourceBaseUrl, sourceHeaders);
        RestClient target = clientFactory.targetClient(targetBaseUrl, properties.targetService(), targetHeaders);
        return compareLive(source, target, sourceBaseUrl, targetBaseUrl, paths, resolve(options));
    }

    private CompareReport compareLive(RestClient source, RestClient target, String sourceBaseUrl,
            String targetBaseUrl, List<String> paths, CompareOptions options) {
        RunFolder run = storage.startRun();
        List<PathResult> results = new ArrayList<>();
        for (String path : paths) {
            results.add(comparePath(source, target, sourceBaseUrl, targetBaseUrl, run, path, options));
        }
        CompareReport report = CompareReport.of(results, options);
        storage.save(run, FileStorageService.REPORT_FILE, report);
        return report;
    }

    /** The URL a path is fetched from: base URL and path joined by a single slash (informational). */
    static String calledUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        String p = path == null ? "" : path.trim();
        return base + (p.startsWith("/") ? p : "/" + p);
    }

    PathResult comparePath(RestClient sourceService, RestClient targetService, String sourceBaseUrl,
            String targetBaseUrl, RunFolder run, String path, CompareOptions options) {
        String sourceUrl = calledUrl(sourceBaseUrl, path);
        String targetUrl = calledUrl(targetBaseUrl, path);
        try {
            Response source = fetch(sourceService, SOURCE_SERVICE, path);
            Response target = fetch(targetService, TARGET_SERVICE, path);

            String folder = run.reserveName(path) + "/";
            String sourceFile = storage.save(run, folder + FileStorageService.SOURCE_FILE, source.json()).orElse(null);
            String targetFile = storage.save(run, folder + FileStorageService.TARGET_FILE, target.json()).orElse(null);

            PathResult result;
            if (source.error() != null || target.error() != null) {
                result = PathResult.error(path, joinErrors(source.error(), target.error()), sourceFile, targetFile);
            }
            else {
                List<Difference> differences = new ArrayList<>();
                if (source.status() != target.status()) {
                    differences.add(Difference.statusMismatch(source.status(), target.status()));
                }
                differences.addAll(comparator.compare(source.json(), target.json(), options));
                result = PathResult.compared(path, differences, sourceFile, targetFile);
            }
            result = result.withUrls(sourceUrl, targetUrl).withDurations(source.durationMillis(), target.durationMillis());
            // The report is saved with its own location, so the file says where its siblings are
            String reportFile = storage.relativize(
                    run.folder() == null ? null : run.folder().resolve(folder + FileStorageService.REPORT_FILE));
            PathResult withReport = reportFile == null ? result : result.withReportFile(reportFile);
            return storage.save(run, folder + FileStorageService.REPORT_FILE, withReport).isPresent() ? withReport
                    : result;
        }
        catch (RuntimeException e) {
            log.warn("Comparison failed for {}: {}", path, e.toString());
            return PathResult.error(path, "comparison failed: " + e.getMessage(), null, null).withUrls(sourceUrl, targetUrl);
        }
    }

    /**
     * Compares two uploaded JSON files. Nothing is called and nothing is saved. A file that is empty or not valid
     * JSON gives an {@code ERROR} result.
     *
     * @param sourceName name of the source file, used as {@code path} in the result
     */
    public CompareReport compareFiles(String sourceName, byte[] sourceContent, String targetName,
            byte[] targetContent, CompareOptions options) {
        CompareOptions resolved = resolve(options);
        String sourceError = null;
        String targetError = null;
        JsonNode source = null;
        JsonNode target = null;
        try {
            source = parseUpload(sourceContent);
        }
        catch (IOException e) {
            sourceError = "source file " + sourceName + " is not valid JSON: " + describe(e);
        }
        try {
            target = parseUpload(targetContent);
        }
        catch (IOException e) {
            targetError = "target file " + targetName + " is not valid JSON: " + describe(e);
        }
        PathResult result = sourceError != null || targetError != null
                ? PathResult.error(sourceName, joinErrors(sourceError, targetError), null, null)
                : PathResult.compared(sourceName, comparator.compare(source, target, resolved), null, null);
        return CompareReport.of(List.of(result), resolved);
    }

    /** Compares two JSON documents given inline. Nothing is called and nothing is saved. */
    public CompareReport compareJson(String name, JsonNode source, JsonNode target, CompareOptions options) {
        CompareOptions resolved = resolve(options);
        return CompareReport.of(
                List.of(PathResult.compared(name, comparator.compare(source, target, resolved), null, null)), resolved);
    }

    /**
     * One GET. The duration is the time to extract the JSON: from sending the request to having the body read and
     * parsed (connection, waiting for the service and transfer included). A failed call is timed until it failed.
     */
    private Response fetch(RestClient client, String service, String path) {
        long start = System.nanoTime();
        Response response;
        try {
            response = client.get()
                    .uri(path)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, reply) -> toResponse(service, reply.getStatusCode().value(),
                            readBody(reply)));
        }
        catch (RuntimeException e) {
            // Connection refused, timeout, invalid URL... Message never includes request headers.
            log.warn("{} call failed for {}: {}", service, path, e.toString());
            response = Response.failed(service + " call failed: " + describe(e));
        }
        long millis = Math.round((System.nanoTime() - start) / 1_000_000.0);
        log.debug("{} answered {} in {} ms", service, path, millis);
        return response.withDuration(millis);
    }

    private Response toResponse(String service, int status, String body) {
        boolean success = status >= 200 && status < 300;
        log.debug("{} answered HTTP {}", service, status);
        if (body == null || body.isBlank()) {
            return success ? new Response(status, null, null)
                    : Response.failed(status, service + " returned HTTP " + status);
        }
        try {
            return new Response(status, jsonReader.readTree(body), null);
        }
        catch (JsonProcessingException e) {
            return Response.failed(status, success
                    ? service + " returned a body that is not valid JSON (HTTP " + status + ")"
                    : service + " returned HTTP " + status);
        }
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        MediaType contentType = response.getHeaders().getContentType();
        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset() : StandardCharsets.UTF_8;
        try (InputStream body = response.getBody()) {
            return StreamUtils.copyToString(body, charset);
        }
    }

    private JsonNode parseUpload(byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            throw new IOException("file is empty");
        }
        // Reading from bytes lets Jackson detect the encoding (UTF-8/16/32).
        JsonNode node = jsonReader.readTree(new ByteArrayInputStream(content));
        if (node == null || node.isMissingNode()) {
            throw new IOException("file is empty");
        }
        return node;
    }

    private static String joinErrors(String sourceError, String targetError) {
        if (sourceError == null) {
            return targetError;
        }
        return targetError == null ? sourceError : sourceError + "; " + targetError;
    }

    private static String describe(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (cause instanceof JsonProcessingException json) {
            message = json.getOriginalMessage();
        }
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** Outcome of one HTTP call: status, parsed body (null when there is none) or an error message. */
    private record Response(int status, JsonNode json, String error, long durationMillis) {

        Response(int status, JsonNode json, String error) {
            this(status, json, error, 0);
        }

        static Response failed(String error) {
            return new Response(-1, null, error);
        }

        static Response failed(int status, String error) {
            return new Response(status, null, error);
        }

        Response withDuration(long millis) {
            return new Response(status, json, error, millis);
        }
    }
}
