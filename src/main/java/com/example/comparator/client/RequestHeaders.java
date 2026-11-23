package com.example.comparator.client;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation of HTTP headers given in a request ({@code sourceHeaders} / {@code targetHeaders}). Values are
 * secrets (tokens, cookies): they are never logged, saved or echoed back.
 */
public final class RequestHeaders {

    public static final int MAX_HEADERS = 20;

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    /** Set by the HTTP client itself or changing how the message is framed. */
    private static final Set<String> FORBIDDEN = Set.of("host", "content-length", "transfer-encoding", "connection",
            "upgrade", "te", "trailer", "expect");

    private RequestHeaders() {
    }

    /**
     * @return a copy of the headers, never {@code null}
     * @throws IllegalArgumentException with a message that never contains a header value
     */
    public static Map<String, String> validate(String field, Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        if (headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException(field + " must not contain more than " + MAX_HEADERS + " headers");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey() == null ? "" : header.getKey().trim();
            String value = header.getValue();
            if (!TOKEN.matcher(name).matches()) {
                throw new IllegalArgumentException(field + " contains an invalid header name");
            }
            if (FORBIDDEN.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(field + " must not set the header " + name);
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(field + " contains the header " + name + " twice");
            }
            if (value == null || value.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
                throw new IllegalArgumentException(field + " has an invalid value for the header " + name);
            }
            result.put(name, value);
        }
        return result;
    }
}
