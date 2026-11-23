package com.example.comparator.model;

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
@JsonPropertyOrder({ "jsonPath", "location", "type", "source", "target", "message" })
public record Difference(String jsonPath, @JsonInclude(JsonInclude.Include.NON_NULL) String location,
        DifferenceType type, JsonNode source, JsonNode target, String message) {

    static final String STATUS_PATH = "$status";

    /**
     * @param location the path with the identity of every array element on the way ({@code $.items[id=2].qty}),
     *                 or the same as {@code path} when no array is involved (then not serialized)
     */
    public static Difference valueMismatch(String path, String location, JsonNode source, JsonNode target) {
        return of(path, location, DifferenceType.VALUE_MISMATCH, source, target,
                "%s: source=%s, target=%s".formatted(shown(path, location), render(source), render(target)));
    }

    public static Difference typeMismatch(String path, String location, JsonNode source, JsonNode target) {
        return of(path, location, DifferenceType.TYPE_MISMATCH, source, target,
                "%s: type differs, source=%s (%s), target=%s (%s)".formatted(shown(path, location), render(source),
                        typeName(source), render(target), typeName(target)));
    }

    public static Difference missingInTarget(String path, String location, JsonNode source, boolean arrayElement) {
        String what = arrayElement ? "element " + render(source) + " is in source but has no match in target"
                : "present in source (" + render(source) + "), missing in target";
        return of(path, location, DifferenceType.MISSING_IN_TARGET, source, null,
                shown(path, location) + ": " + what);
    }

    public static Difference missingInSource(String path, String location, JsonNode target, boolean arrayElement) {
        String what = arrayElement ? "element " + render(target) + " is in target but has no match in source"
                : "missing in source, present in target (" + render(target) + ")";
        return of(path, location, DifferenceType.MISSING_IN_SOURCE, null, target,
                shown(path, location) + ": " + what);
    }

    private static Difference of(String path, String location, DifferenceType type, JsonNode source,
            JsonNode target, String message) {
        return new Difference(path, path.equals(location) ? null : location, type, source, target, message);
    }

    private static String shown(String path, String location) {
        return location == null ? path : location;
    }

    public static Difference statusMismatch(int sourceStatus, int targetStatus) {
        return new Difference(STATUS_PATH, null, DifferenceType.STATUS_MISMATCH, IntNode.valueOf(sourceStatus),
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
