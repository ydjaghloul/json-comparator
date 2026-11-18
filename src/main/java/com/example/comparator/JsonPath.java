package com.example.comparator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal JSON path used by the comparator: a concrete location such as {@code $.items[1].discount}, or a pattern
 * from the configuration where {@code [*]} matches any array index and {@code .*} any property name.
 * Property names that are not plain identifiers are written in bracket notation: {@code $['first name']}.
 */
final class JsonPath {

    static final JsonPath ROOT = new JsonPath(null, null, 0);

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JsonPath parent;
    private final Segment segment;
    private final int depth;

    private JsonPath(JsonPath parent, Segment segment, int depth) {
        this.parent = parent;
        this.segment = segment;
        this.depth = depth;
    }

    JsonPath field(String name) {
        return new JsonPath(this, new Segment(name, -1, false), depth + 1);
    }

    JsonPath index(int index) {
        return new JsonPath(this, new Segment(null, index, false), depth + 1);
    }

    /** True when this path, used as a pattern, matches the given concrete path. */
    boolean matches(JsonPath concrete) {
        if (concrete.depth != depth) {
            return false;
        }
        JsonPath pattern = this;
        JsonPath actual = concrete;
        while (pattern.segment != null) {
            if (!pattern.segment.matches(actual.segment)) {
                return false;
            }
            pattern = pattern.parent;
            actual = actual.parent;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        appendTo(out);
        return out.toString();
    }

    private void appendTo(StringBuilder out) {
        if (parent == null) {
            out.append('$');
            return;
        }
        parent.appendTo(out);
        segment.appendTo(out);
    }

    /**
     * Parses a path such as {@code $.items[*].id}, {@code $.meta.*}, {@code $['odd name'][0]}.
     *
     * @throws IllegalArgumentException if the expression is not a supported path
     */
    static JsonPath parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("JSON path must not be empty");
        }
        String expr = expression.trim();
        if (expr.charAt(0) != '$') {
            throw invalid(expression, "must start with '$'");
        }
        List<Segment> segments = new ArrayList<>();
        int i = 1;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '.') {
                i++;
                int start = i;
                while (i < expr.length() && expr.charAt(i) != '.' && expr.charAt(i) != '[') {
                    i++;
                }
                String name = expr.substring(start, i);
                if (name.isEmpty()) {
                    throw invalid(expression, "empty property name at position " + start);
                }
                segments.add("*".equals(name) ? new Segment(null, -1, true) : new Segment(name, -1, false));
            }
            else if (c == '[') {
                int close;
                if (i + 1 < expr.length() && (expr.charAt(i + 1) == '\'' || expr.charAt(i + 1) == '"')) {
                    char quote = expr.charAt(i + 1);
                    StringBuilder name = new StringBuilder();
                    int j = i + 2;
                    while (j < expr.length() && expr.charAt(j) != quote) {
                        if (expr.charAt(j) == '\\' && j + 1 < expr.length()) {
                            j++;
                        }
                        name.append(expr.charAt(j));
                        j++;
                    }
                    if (j + 1 >= expr.length() || expr.charAt(j + 1) != ']') {
                        throw invalid(expression, "unterminated quoted property name at position " + i);
                    }
                    segments.add(new Segment(name.toString(), -1, false));
                    close = j + 1;
                }
                else {
                    close = expr.indexOf(']', i);
                    if (close < 0) {
                        throw invalid(expression, "missing ']' at position " + i);
                    }
                    String content = expr.substring(i + 1, close).trim();
                    if ("*".equals(content)) {
                        segments.add(new Segment(null, -1, true));
                    }
                    else if (content.matches("\\d+")) {
                        segments.add(new Segment(null, Integer.parseInt(content), false));
                    }
                    else {
                        throw invalid(expression, "unsupported index '" + content + "'");
                    }
                }
                i = close + 1;
            }
            else {
                throw invalid(expression, "unexpected character '" + c + "' at position " + i);
            }
        }
        JsonPath path = ROOT;
        for (Segment segment : segments) {
            path = new JsonPath(path, segment, path.depth + 1);
        }
        return path;
    }

    private static IllegalArgumentException invalid(String expression, String reason) {
        return new IllegalArgumentException("Invalid JSON path '" + expression + "': " + reason);
    }

    /**
     * One step of a path: a property ({@code name != null}), an array index ({@code index >= 0}), or a wildcard.
     * A {@code .*} wildcard matches any property, a {@code [*]} wildcard matches any index.
     */
    private record Segment(String name, int index, boolean wildcard) {

        boolean matches(Segment actual) {
            if (wildcard) {
                return true;
            }
            if (name != null) {
                return name.equals(actual.name);
            }
            return actual.name == null && index == actual.index;
        }

        void appendTo(StringBuilder out) {
            if (wildcard) {
                out.append("[*]");
            }
            else if (name == null) {
                out.append('[').append(index).append(']');
            }
            else if (IDENTIFIER.matcher(name).matches()) {
                out.append('.').append(name);
            }
            else {
                out.append("['").append(name.replace("\\", "\\\\").replace("'", "\\'")).append("']");
            }
        }
    }
}
