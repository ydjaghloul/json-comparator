package com.example.comparator;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;

/**
 * One difference between source (the reference) and target. Values are kept as {@link JsonNode} so objects and
 * arrays are rendered as JSON, in full. A missing value is serialized as {@code null}; the {@code type} tells
 * whether it is missing or an actual JSON {@code null}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({ "jsonPath", "type", "source", "target", "message" })
public record Difference(String jsonPath, DifferenceType type, JsonNode source, JsonNode target, String message) {

    static final String STATUS_PATH = "$status";

    static Difference valueMismatch(String path, JsonNode source, JsonNode target) {
        return new Difference(path, DifferenceType.VALUE_MISMATCH, source, target,
                path + ": source=" + render(source) + ", target=" + render(target));
    }

    static Difference typeMismatch(String path, JsonNode source, JsonNode target) {
        return new Difference(path, DifferenceType.TYPE_MISMATCH, source, target,
                path + ": type differs, source=" + render(source) + " (" + typeName(source) + "), target="
                        + render(target) + " (" + typeName(target) + ")");
    }

    static Difference missingInTarget(String path, JsonNode source) {
        return new Difference(path, DifferenceType.MISSING_IN_TARGET, source, null,
                path + ": present in source (" + render(source) + "), missing in target");
    }

    static Difference missingInSource(String path, JsonNode target) {
        return new Difference(path, DifferenceType.MISSING_IN_SOURCE, null, target,
                path + ": missing in source, present in target (" + render(target) + ")");
    }

    static Difference statusMismatch(int sourceStatus, int targetStatus) {
        return new Difference(STATUS_PATH, DifferenceType.STATUS_MISMATCH, IntNode.valueOf(sourceStatus),
                IntNode.valueOf(targetStatus), STATUS_PATH + ": source=" + sourceStatus + ", target=" + targetStatus);
    }

    /** Full JSON text of the value, never truncated. */
    private static String render(JsonNode node) {
        return node == null ? "missing" : node.toString();
    }

    private static String typeName(JsonNode node) {
        return node.getNodeType().name().toLowerCase(Locale.ROOT);
    }
}
