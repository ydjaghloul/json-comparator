package com.example.comparator.controller.dto;

import java.util.Map;

/** Optional body of {@code POST /compare}: headers for this run only. */
public record HeadersRequest(Map<String, String> sourceHeaders, Map<String, String> targetHeaders) {
}
