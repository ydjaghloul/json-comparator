package com.example.comparator.controller;

import com.example.comparator.client.RequestHeaders;
import com.example.comparator.controller.dto.CustomCompareRequest;
import com.example.comparator.controller.dto.HeadersRequest;
import com.example.comparator.model.CompareOptions;
import com.example.comparator.model.CompareReport;
import com.example.comparator.model.NullMatching;
import com.example.comparator.model.TypeMatching;
import com.example.comparator.service.CompareService;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * All endpoints accept the optional query parameters {@code typeMatching=STRICT|LENIENT} and
 * {@code nullMatching=STRICT|LENIENT}, {@code orderStrict=true|false} and {@code idProperties=id,code,...}
 * (defaults: {@code comparison.type-matching}, {@code comparison.null-matching})
 * and return the same {@link CompareReport}.
 */
@RestController
@Tag(name = "Comparison")
public class CompareController {

    static final String DEFAULT_INLINE_NAME = "inline";
    static final String TYPE_MATCHING = "typeMatching";
    static final String TYPE_MATCHING_DOC = "STRICT: 11 != \"11\". LENIENT: \"11\" == 11, \"true\" == true. "
            + "Default: comparison.type-matching";
    static final String NULL_MATCHING = "nullMatching";
    static final String NULL_MATCHING_DOC = "STRICT: {\"a\": null} vs {} is MISSING_IN_TARGET. LENIENT: a property that "
            + "is null on one side and absent on the other is not a difference. Default: comparison.null-matching";

    static final String ORDER_STRICT = "orderStrict";
    static final String ORDER_STRICT_DOC = "true: array elements are compared by position. false: the order of "
            + "array elements does not matter; elements are paired by id, then by equality. "
            + "Default: comparison.array-order (UNORDERED unless configured otherwise)";
    static final String ID_PROPERTIES = "idProperties";
    static final String ID_PROPERTIES_DOC = "Comma-separated candidate id properties, in priority order, used to "
            + "pair array elements when order is not strict (e.g. id,code). The first one present with a unique "
            + "value in every element of both arrays is used. Default: comparison.id-properties";

    static final String HEADERS_DOC = "Optional. sourceHeaders / targetHeaders: extra HTTP headers sent with every "
            + "call to the source / target of this run, on top of the configured ones (same name: this one wins). "
            + "Values are secrets: never logged, saved or returned.";

    private final CompareService compareService;

    public CompareController(CompareService compareService) {
        this.compareService = compareService;
    }

    /** Calls the configured services for every configured path, saves the responses, reports the differences. */
    @Operation(summary = "Compare the configured services for every configured path",
            description = "Responses are saved under output.directory.")
    @PostMapping("/compare")
    public CompareReport compare(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false, description = HEADERS_DOC,
                    content = @Content(examples = @ExampleObject(value = """
                            {"sourceHeaders": {"Authorization": "Bearer <token>"},
                             "targetHeaders": {"X-Api-Key": "<key>"}}"""))) @RequestBody(required = false) HeadersRequest headers,
            @Parameter(description = TYPE_MATCHING_DOC) @RequestParam(name = TYPE_MATCHING, required = false) TypeMatching typeMatching,
            @Parameter(description = NULL_MATCHING_DOC) @RequestParam(name = NULL_MATCHING, required = false) NullMatching nullMatching,
            @Parameter(description = ORDER_STRICT_DOC) @RequestParam(name = ORDER_STRICT, required = false) Boolean orderStrict,
            @Parameter(description = ID_PROPERTIES_DOC) @RequestParam(name = ID_PROPERTIES, required = false) List<String> idProperties) {
        HeadersRequest given = headers == null ? new HeadersRequest(null, null) : headers;
        return compareService.compareAll(options(typeMatching, nullMatching, orderStrict, idProperties),
                validHeaders("sourceHeaders", given.sourceHeaders()), validHeaders("targetHeaders", given.targetHeaders()));
    }

    /** Same as {@code /compare}, with both base URLs and the paths taken from the request body. */
    @Operation(summary = "Compare two services given in the request, for the given paths",
            description = "Credentials are only sent to source_service.base-url or a trusted base URL. "
                    + "Responses are saved under output.directory.")
    @PostMapping("/compare/custom")
    public CompareReport compareCustom(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
                    value = """
                            {"sourceBaseUrl": "https://source.example.com",
                             "targetBaseUrl": "https://target.example.com",
                             "paths": ["/customers/42/orders", "/products/1001"],
                             "sourceHeaders": {"Authorization": "Bearer <token>"},
                             "targetHeaders": {"X-Api-Key": "<key>"}}"""))) @RequestBody CustomCompareRequest request,
            @Parameter(description = TYPE_MATCHING_DOC) @RequestParam(name = TYPE_MATCHING, required = false) TypeMatching typeMatching,
            @Parameter(description = NULL_MATCHING_DOC) @RequestParam(name = NULL_MATCHING, required = false) NullMatching nullMatching,
            @Parameter(description = ORDER_STRICT_DOC) @RequestParam(name = ORDER_STRICT, required = false) Boolean orderStrict,
            @Parameter(description = ID_PROPERTIES_DOC) @RequestParam(name = ID_PROPERTIES, required = false) List<String> idProperties) {
        requireHttpUrl("sourceBaseUrl", request.sourceBaseUrl());
        requireHttpUrl("targetBaseUrl", request.targetBaseUrl());
        if (request.paths() == null || request.paths().isEmpty()) {
            throw badRequest("paths is required and must not be empty");
        }
        if (request.paths().stream().anyMatch(path -> path == null || path.isBlank())) {
            throw badRequest("paths must not contain blank entries");
        }
        return compareService.compareCustom(request.sourceBaseUrl().trim(), request.targetBaseUrl().trim(),
                List.copyOf(request.paths()), options(typeMatching, nullMatching, orderStrict, idProperties),
                validHeaders("sourceHeaders", request.sourceHeaders()),
                validHeaders("targetHeaders", request.targetHeaders()));
    }

    /** Compares two files already saved under {@code output.directory}. */
    @Operation(summary = "Compare two uploaded JSON files",
            description = "multipart/form-data with two file parts, sourceFile and targetFile. "
                    + "Nothing is called and nothing is saved. A file that is empty or not valid JSON gives an ERROR result.")
    @PostMapping(value = "/compare/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompareReport compareFiles(
            @Parameter(description = "Source (reference) JSON file") @RequestPart("sourceFile") MultipartFile sourceFile,
            @Parameter(description = "Target JSON file") @RequestPart("targetFile") MultipartFile targetFile,
            @Parameter(description = TYPE_MATCHING_DOC) @RequestParam(name = TYPE_MATCHING, required = false) TypeMatching typeMatching,
            @Parameter(description = NULL_MATCHING_DOC) @RequestParam(name = NULL_MATCHING, required = false) NullMatching nullMatching,
            @Parameter(description = ORDER_STRICT_DOC) @RequestParam(name = ORDER_STRICT, required = false) Boolean orderStrict,
            @Parameter(description = ID_PROPERTIES_DOC) @RequestParam(name = ID_PROPERTIES, required = false) List<String> idProperties) {
        return compareService.compareFiles(fileName(sourceFile, "source"), content(sourceFile),
                fileName(targetFile, "target"), content(targetFile), options(typeMatching, nullMatching, orderStrict, idProperties));
    }

    /**
     * Compares two JSON documents from the request body: {@code {"name": "...", "source": ..., "target": ...}}.
     * {@code source} and {@code target} may be any JSON value, including {@code null}, but must be present.
     */
    @Operation(summary = "Compare two JSON documents sent in the request body",
            description = "source and target are required and can be any JSON value. Nothing is called or saved.")
    @PostMapping("/compare/json")
    public CompareReport compareJson(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    schema = @Schema(type = "object"), examples = @ExampleObject(value = """
                            {"name": "demo",
                             "source": {"total": "100", "items": [1, 2]},
                             "target": {"items": [1, 2], "total": 100}}"""))) @RequestBody JsonNode body,
            @Parameter(description = TYPE_MATCHING_DOC) @RequestParam(name = TYPE_MATCHING, required = false) TypeMatching typeMatching,
            @Parameter(description = NULL_MATCHING_DOC) @RequestParam(name = NULL_MATCHING, required = false) NullMatching nullMatching,
            @Parameter(description = ORDER_STRICT_DOC) @RequestParam(name = ORDER_STRICT, required = false) Boolean orderStrict,
            @Parameter(description = ID_PROPERTIES_DOC) @RequestParam(name = ID_PROPERTIES, required = false) List<String> idProperties) {
        if (body == null || !body.isObject() || !body.has("source") || !body.has("target")) {
            throw badRequest("source and target are required");
        }
        JsonNode nameNode = body.get("name");
        String name = nameNode == null || nameNode.isNull() || nameNode.asText().isBlank()
                ? DEFAULT_INLINE_NAME : nameNode.asText();
        return compareService.compareJson(name, body.get("source"), body.get("target"), options(typeMatching, nullMatching, orderStrict, idProperties));
    }

    /** Original file name without any directory part; {@code side.json} when the client sent none. */
    private static String fileName(MultipartFile file, String side) {
        String name = StringUtils.getFilename(StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/')));
        return name == null || name.isBlank() ? side + ".json" : name;
    }

    private static byte[] content(MultipartFile file) {
        try {
            return file.getBytes();
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file", e);
        }
    }

    private static void requireHttpUrl(String field, String value) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw badRequest(field + " must be an absolute http or https URL");
            }
        }
        catch (IllegalArgumentException e) {
            throw badRequest(field + " must be an absolute http or https URL");
        }
    }

    private static CompareOptions options(TypeMatching typeMatching, NullMatching nullMatching, Boolean orderStrict,
            List<String> idProperties) {
        List<String> ids = idProperties == null ? null
                : idProperties.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        return new CompareOptions(typeMatching, nullMatching, orderStrict, ids == null || ids.isEmpty() ? null : ids);
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static Map<String, String> validHeaders(String field, Map<String, String> headers) {
        try {
            return RequestHeaders.validate(field, headers);
        }
        catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }
}
