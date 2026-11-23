package com.example.comparator.comparator;

import com.example.comparator.model.CompareOptions;
import com.example.comparator.model.Difference;
import com.example.comparator.model.NullMatching;
import com.example.comparator.model.TypeMatching;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.comparator.config.ComparatorProperties.ArrayOrder;
import com.example.comparator.config.ComparatorProperties.Comparison;
import com.example.comparator.config.ComparatorProperties.UnorderedArray;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;

/**
 * Recursive semantic comparison of two JSON trees. Source is the reference: every difference is reported with
 * its exact JSON path and both (original) values.
 * <ul>
 * <li>Object property order is ignored.</li>
 * <li>Arrays: unless {@code orderStrict} is set, element order does not matter. Elements are paired by the
 * configured key of the path or by an id property detected among the candidates, then by equality.</li>
 * <li>Types: see {@link TypeMatching}. Numbers compare by value ({@code 1} equals {@code 1.0}).</li>
 * <li>A property {@code null} on one side and absent on the other: see {@link NullMatching}.</li>
 * <li>Configured ignore paths (and everything below them) are skipped.</li>
 * </ul>
 * A {@code null} (or missing) document is treated as absent.
 */
public class JsonComparator {

    /** A JSON number, exactly: no spaces, no leading '+', no leading zeros. */
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    /** Share of leaf values two unpaired objects must have in common to be compared property by property. */
    static final double MIN_SIMILARITY = 0.5;

    private final CompareOptions defaults;
    private final List<JsonPath> ignorePatterns;
    private final ArrayOrder defaultArrayOrder;
    private final List<ArrayRule> arrayRules;

    public JsonComparator(Comparison settings) {
        this.defaults = new CompareOptions(settings.typeMatching(), settings.nullMatching(),
                settings.arrayOrder() == ArrayOrder.ORDERED, settings.idProperties());
        this.ignorePatterns = settings.ignorePaths().stream().map(JsonPath::parse).toList();
        this.defaultArrayOrder = settings.arrayOrder();
        this.arrayRules = settings.unorderedArrays().stream().map(ArrayRule::of).toList();
    }

    /** Options used when a comparison does not set them ({@code comparison.*} configuration). */
    public CompareOptions defaults() {
        return defaults;
    }

    public TypeMatching defaultTypeMatching() {
        return defaults.typeMatching();
    }

    /** Compares with the configured default options. */
    public List<Difference> compare(JsonNode source, JsonNode target) {
        return compare(source, target, (CompareOptions) null);
    }

    /** Compares with the given type matching ({@code null} = configured default) and the default null matching. */
    public List<Difference> compare(JsonNode source, JsonNode target, TypeMatching typeMatching) {
        return compare(source, target, new CompareOptions(typeMatching, null));
    }

    /** Compares with the given options; unset options use the configured defaults. */
    public List<Difference> compare(JsonNode source, JsonNode target, CompareOptions options) {
        CompareOptions resolved = options == null ? defaults : options.withDefaults(defaults);
        Mode mode = new Mode(resolved.typeMatching() == TypeMatching.LENIENT,
                resolved.nullMatching() == NullMatching.LENIENT, Boolean.TRUE.equals(resolved.orderStrict()),
                resolved.idProperties());
        List<Difference> differences = new ArrayList<>();
        compare(Cursor.ROOT, source, target, mode, differences);
        return differences;
    }

    private void compare(Cursor at, JsonNode source, JsonNode target, Mode mode, List<Difference> out) {
        if (isIgnored(at.path())) {
            return;
        }
        boolean sourceMissing = isMissing(source);
        boolean targetMissing = isMissing(target);
        if (sourceMissing && targetMissing) {
            return;
        }
        if (sourceMissing) {
            out.add(Difference.missingInSource(at.path().toString(), at.targetPath().toString(), at.label(), target, at.element()));
            return;
        }
        if (targetMissing) {
            out.add(Difference.missingInTarget(at.path().toString(), at.label(), source, at.element()));
            return;
        }
        JsonNodeType type = source.getNodeType();
        if (type != target.getNodeType()) {
            if (source.isNull() || target.isNull()) {
                // null is a value like another: "abc" vs null is a different value, not a different type
                out.add(Difference.valueMismatch(at.path().toString(), at.targetPath().toString(), at.label(), source, target));
                return;
            }
            Boolean convertedEqual = mode.lenientTypes() ? lenientScalarEquals(source, target) : null;
            if (convertedEqual == null) {
                out.add(Difference.typeMismatch(at.path().toString(), at.targetPath().toString(), at.label(), source, target));
            }
            else if (!convertedEqual) {
                out.add(Difference.valueMismatch(at.path().toString(), at.targetPath().toString(), at.label(), source, target));
            }
            return;
        }
        switch (type) {
            case OBJECT -> compareObjects(at, source, target, mode, out);
            case ARRAY -> compareArrays(at, source, target, mode, out);
            case NULL -> {
                // null == null
            }
            case NUMBER -> {
                if (!numbersEqual(source, target)) {
                    out.add(Difference.valueMismatch(at.path().toString(), at.targetPath().toString(), at.label(), source, target));
                }
            }
            default -> {
                if (!source.equals(target)) {
                    out.add(Difference.valueMismatch(at.path().toString(), at.targetPath().toString(), at.label(), source, target));
                }
            }
        }
    }

    private void compareObjects(Cursor at, JsonNode source, JsonNode target, Mode mode, List<Difference> out) {
        Iterator<String> sourceNames = source.fieldNames();
        while (sourceNames.hasNext()) {
            String name = sourceNames.next();
            JsonNode sourceValue = source.get(name);
            JsonNode targetValue = target.get(name);
            if (targetValue == null && mode.lenientNulls() && sourceValue.isNull()) {
                continue; // nullMatching=LENIENT: {"a": null} vs {} is not a difference
            }
            compare(at.field(name), sourceValue, targetValue, mode, out);
        }
        Iterator<String> targetNames = target.fieldNames();
        while (targetNames.hasNext()) {
            String name = targetNames.next();
            if (source.has(name)) {
                continue;
            }
            JsonNode targetValue = target.get(name);
            if (mode.lenientNulls() && targetValue.isNull()) {
                continue; // nullMatching=LENIENT: {} vs {"a": null} is not a difference
            }
            compare(at.field(name), null, targetValue, mode, out);
        }
    }

    private void compareArrays(Cursor at, JsonNode source, JsonNode target, Mode mode, List<Difference> out) {
        ArrayRule rule = arrayRule(at.path());
        // Strict order = position by position, except for paths explicitly configured as unordered
        if (rule == null && mode.orderStrict()) {
            int size = Math.max(source.size(), target.size());
            for (int i = 0; i < size; i++) {
                JsonNode element = i < source.size() ? source.get(i) : target.get(i);
                compare(at.element(i, i, element, null, mode), source.get(i), target.get(i), mode, out);
            }
        }
        else {
            String key = rule == null ? null : rule.key();
            if (key == null) {
                key = detectIdProperty(source, target, mode);
            }
            compareUnordered(at, source, target, key, mode, out);
        }
    }

    /**
     * Pairs elements regardless of position: first by id (explicit key or detected id property), then by full
     * equality. Paired elements are compared field by field (reported at the source index); unpaired source
     * elements are {@code MISSING_IN_TARGET} at their source index, unpaired target elements
     * {@code MISSING_IN_SOURCE} at their target index. Every element is identified by its id in the location.
     */
    private void compareUnordered(Cursor at, JsonNode source, JsonNode target, String key, Mode mode,
            List<Difference> out) {
        int sourceSize = source.size();
        int targetSize = target.size();
        boolean[] sourcePaired = new boolean[sourceSize];
        boolean[] targetPaired = new boolean[targetSize];

        if (key != null) {
            Map<String, Deque<Integer>> targetsByKey = new HashMap<>();
            for (int j = 0; j < targetSize; j++) {
                String keyValue = keyOf(target.get(j), key, mode.lenientTypes());
                if (keyValue != null) {
                    targetsByKey.computeIfAbsent(keyValue, k -> new ArrayDeque<>()).add(j);
                }
            }
            for (int i = 0; i < sourceSize; i++) {
                String keyValue = keyOf(source.get(i), key, mode.lenientTypes());
                Deque<Integer> candidates = keyValue == null ? null : targetsByKey.get(keyValue);
                if (candidates != null && !candidates.isEmpty()) {
                    int j = candidates.poll();
                    sourcePaired[i] = true;
                    targetPaired[j] = true;
                    compare(at.element(i, j, source.get(i), key, mode), source.get(i), target.get(j), mode, out);
                }
            }
        }

        for (int i = 0; i < sourceSize; i++) {
            if (sourcePaired[i]) {
                continue;
            }
            for (int j = 0; j < targetSize; j++) {
                if (!targetPaired[j]
                        && equivalent(at.element(i, j, source.get(i), key, mode), source.get(i), target.get(j), mode)) {
                    sourcePaired[i] = true;
                    targetPaired[j] = true;
                    break;
                }
            }
        }

        if (key == null) {
            pairBySimilarity(at, source, target, sourcePaired, targetPaired, mode, out);
        }

        for (int i = 0; i < sourceSize; i++) {
            if (!sourcePaired[i]) {
                compare(at.element(i, i, source.get(i), key, mode), source.get(i), null, mode, out);
            }
        }
        for (int j = 0; j < targetSize; j++) {
            if (!targetPaired[j]) {
                compare(at.element(j, j, target.get(j), key, mode), null, target.get(j), mode, out);
            }
        }
    }

    /**
     * Last pairing step, only when the array has no usable id: objects that are still unpaired are matched with the
     * most similar object of the other side (at least {@value #MIN_SIMILARITY} of their leaf values in common), so
     * the report names the properties that differ instead of one missing and one extra object. Best pairs first;
     * ties are broken on the content of the two objects, never on their side, so swapping source and target
     * pairs the same objects.
     */
    private void pairBySimilarity(Cursor at, JsonNode source, JsonNode target, boolean[] sourcePaired,
            boolean[] targetPaired, Mode mode, List<Difference> out) {
        record Candidate(double score, String tie, int i, int j) {
        }
        Map<Integer, Set<String>> sourceLeaves = new HashMap<>();
        Map<Integer, Set<String>> targetLeaves = new HashMap<>();
        for (int i = 0; i < source.size(); i++) {
            if (!sourcePaired[i] && source.get(i).isObject()) {
                sourceLeaves.put(i, leaves(source.get(i)));
            }
        }
        for (int j = 0; j < target.size(); j++) {
            if (!targetPaired[j] && target.get(j).isObject()) {
                targetLeaves.put(j, leaves(target.get(j)));
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        sourceLeaves.forEach((i, a) -> targetLeaves.forEach((j, b) -> {
            double score = similarity(a, b);
            if (score >= MIN_SIMILARITY) {
                String x = source.get(i).toString();
                String y = target.get(j).toString();
                String tie = x.compareTo(y) <= 0 ? x + "\u0000" + y : y + "\u0000" + x;
                candidates.add(new Candidate(score, tie, i, j));
            }
        }));
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::tie)
                .thenComparingInt(Candidate::i).thenComparingInt(Candidate::j));
        for (Candidate candidate : candidates) {
            if (sourcePaired[candidate.i()] || targetPaired[candidate.j()]) {
                continue;
            }
            sourcePaired[candidate.i()] = true;
            targetPaired[candidate.j()] = true;
            compare(at.element(candidate.i(), candidate.j(), source.get(candidate.i()), null, mode),
                    source.get(candidate.i()),
                    target.get(candidate.j()), mode, out);
        }
    }

    private static double similarity(Set<String> a, Set<String> b) {
        int common = 0;
        for (String leaf : a) {
            if (b.contains(leaf)) {
                common++;
            }
        }
        int union = a.size() + b.size() - common;
        return union == 0 || common == 0 ? 0 : (double) common / union;
    }

    /** {@code path=value} of every scalar below the node; array positions are ignored (order does not matter). */
    private static Set<String> leaves(JsonNode node) {
        Set<String> out = new HashSet<>();
        collectLeaves("", node, out);
        return out;
    }

    private static void collectLeaves(String path, JsonNode node, Set<String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(f -> collectLeaves(path + "." + f.getKey(), f.getValue(), out));
        }
        else if (node.isArray()) {
            node.forEach(element -> collectLeaves(path + "[]", element, out));
        }
        else if (!node.isNull()) {
            String value = node.isNumber() ? node.decimalValue().stripTrailingZeros().toPlainString()
                    : node.toString();
            out.add(path + "=" + value);
        }
    }

    /**
     * Picks, among the candidate id properties (in priority order), the first one that every element of both
     * arrays carries as a scalar value, and whose values are unique within each array. Returns {@code null} when
     * none qualifies (elements are then paired by equality only).
     */
    private static String detectIdProperty(JsonNode source, JsonNode target, Mode mode) {
        if (source.isEmpty() || target.isEmpty()) {
            return null;
        }
        for (String candidate : mode.idProperties()) {
            if (usableId(source, candidate, mode) && usableId(target, candidate, mode)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean usableId(JsonNode array, String property, Mode mode) {
        Set<String> seen = new HashSet<>();
        for (JsonNode element : array) {
            if (element == null || !element.isObject() || !element.has(property)
                    || !element.get(property).isValueNode() || element.get(property).isNull()) {
                return false;
            }
            if (!seen.add(keyOf(element, property, mode.lenientTypes()))) {
                return false;
            }
        }
        return true;
    }

    private boolean equivalent(Cursor at, JsonNode source, JsonNode target, Mode mode) {
        List<Difference> differences = new ArrayList<>();
        compare(at, source, target, mode, differences);
        return differences.isEmpty();
    }

    /**
     * LENIENT mode, scalars of different types: a string is converted to the type of the other value.
     *
     * @return whether the converted values are equal, or {@code null} when the pair cannot be converted (then it
     *         stays a TYPE_MISMATCH)
     */
    private static Boolean lenientScalarEquals(JsonNode source, JsonNode target) {
        if (source.isTextual() && !target.isTextual()) {
            return convertedEquals(source.textValue(), target);
        }
        if (target.isTextual() && !source.isTextual()) {
            return convertedEquals(target.textValue(), source);
        }
        return null;
    }

    private static Boolean convertedEquals(String text, JsonNode other) {
        if (other.isNumber()) {
            BigDecimal number = parseNumber(text);
            return number == null ? null : number.compareTo(other.decimalValue()) == 0;
        }
        if (other.isBoolean()) {
            Boolean bool = parseBoolean(text);
            return bool == null ? null : bool == other.booleanValue();
        }
        return null;
    }

    private static BigDecimal parseNumber(String text) {
        if (text == null || !JSON_NUMBER.matcher(text).matches()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBoolean(String text) {
        if ("true".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Key used to pair elements of a keyed unordered array. {@code 1} and {@code 1.0} are the same key; {@code 1}
     * and {@code "1"} are the same key only in LENIENT mode.
     */
    private static String keyOf(JsonNode element, String key, boolean lenient) {
        if (element == null || !element.isObject() || !element.has(key)) {
            return null;
        }
        JsonNode value = element.get(key);
        BigDecimal number = value.isNumber() ? value.decimalValue()
                : lenient && value.isTextual() ? parseNumber(value.textValue()) : null;
        if (number != null) {
            return "NUMBER:" + (number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString());
        }
        if (lenient && value.isTextual() && parseBoolean(value.textValue()) != null) {
            return "BOOLEAN:" + value.textValue();
        }
        return value.getNodeType() + ":" + value;
    }

    private static boolean numbersEqual(JsonNode source, JsonNode target) {
        return source.decimalValue().compareTo(target.decimalValue()) == 0;
    }

    private static boolean isMissing(JsonNode node) {
        return node == null || node.isMissingNode();
    }

    private boolean isIgnored(JsonPath path) {
        for (JsonPath pattern : ignorePatterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private ArrayRule arrayRule(JsonPath path) {
        for (ArrayRule rule : arrayRules) {
            if (rule.pattern().matches(path)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Where the comparison is: the exact path, and the same path with every array element identified by its id
     * ({@code $.items[id=2].lines[id=7].qty}) or, without id, by its index and main scalar fields.
     */
    private record Cursor(JsonPath path, JsonPath targetPath, String label, boolean element) {

        static final Cursor ROOT = new Cursor(JsonPath.ROOT, JsonPath.ROOT, "$", false);

        Cursor field(String name) {
            return new Cursor(path.field(name), targetPath.field(name), label + JsonPath.fieldSegment(name), false);
        }

        /**
         * @param sourceIndex  position of the element in the source array
         * @param targetIndex  position of the paired element in the target array (same as {@code sourceIndex}
         *                     for an element that has no counterpart)
         * @param idProperty   the id property used to pair this array, or {@code null}
         */
        Cursor element(int sourceIndex, int targetIndex, JsonNode element, String idProperty, Mode mode) {
            return new Cursor(path.index(sourceIndex), targetPath.index(targetIndex),
                    label + describe(sourceIndex, element, idProperty, mode.idProperties()), true);
        }

        private static String describe(int index, JsonNode element, String idProperty, List<String> candidates) {
            if (element == null || !element.isObject()) {
                return "[" + index + "]";
            }
            List<String> properties = new ArrayList<>();
            if (idProperty != null) {
                properties.add(idProperty);
            }
            properties.addAll(candidates);
            for (String property : properties) {
                JsonNode value = element.get(property);
                if (value != null && value.isValueNode() && !value.isNull()) {
                    return "[" + property + "=" + scalar(value) + "]";
                }
            }
            StringBuilder fields = new StringBuilder();
            int shown = 0;
            Iterator<Map.Entry<String, JsonNode>> it = element.fields();
            while (it.hasNext() && shown < 3) {
                Map.Entry<String, JsonNode> field = it.next();
                if (field.getValue().isValueNode() && !field.getValue().isNull()) {
                    fields.append(shown++ == 0 ? "" : ", ").append(field.getKey()).append('=')
                            .append(scalar(field.getValue()));
                }
            }
            return shown == 0 ? "[" + index + "]" : "[" + index + ": " + fields + "]";
        }

        private static String scalar(JsonNode value) {
            String text = value.isTextual() ? value.textValue() : value.toString();
            return text.length() > 30 ? text.substring(0, 27) + "..." : text;
        }
    }

    /** Resolved options of one comparison. */
    private record Mode(boolean lenientTypes, boolean lenientNulls, boolean orderStrict,
            List<String> idProperties) {
    }

    private record ArrayRule(JsonPath pattern, String key) {

        static ArrayRule of(UnorderedArray config) {
            String key = config.key() == null || config.key().isBlank() ? null : config.key().trim();
            return new ArrayRule(JsonPath.parse(config.path()), key);
        }
    }
}
