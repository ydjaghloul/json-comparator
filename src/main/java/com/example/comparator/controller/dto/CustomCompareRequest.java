package com.example.comparator.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * Request body of {@code POST /compare/custom}. {@code sourceHeaders} / {@code targetHeaders} are optional
 * headers sent with every call to the source / target of this request.
 */
public record CustomCompareRequest(String sourceBaseUrl, String targetBaseUrl, List<String> paths,
        Map<String, String> sourceHeaders, Map<String, String> targetHeaders) {
}
