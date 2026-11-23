package com.example.comparator.model;

import java.util.List;

/**
 * Per-comparison options, given as query parameters on every endpoint. A {@code null} field means "use the
 * configured default" ({@code comparison.type-matching}, {@code comparison.null-matching},
 * {@code comparison.order-strict}, {@code comparison.id-properties}).
 *
 * @param orderStrict  {@code true}: array elements are compared position by position; {@code false}: the order of
 *                     array elements does not matter and elements are paired by id (see {@code idProperties}),
 *                     then by equality
 * @param idProperties candidate id properties, in priority order, used to pair elements of unordered arrays
 */
public record CompareOptions(TypeMatching typeMatching, NullMatching nullMatching, Boolean orderStrict,
        List<String> idProperties) {

    public CompareOptions {
        idProperties = idProperties == null ? null : List.copyOf(idProperties);
    }

    public CompareOptions(TypeMatching typeMatching, NullMatching nullMatching) {
        this(typeMatching, nullMatching, null, null);
    }

    /** Fills the unset options from the given defaults. */
    public CompareOptions withDefaults(CompareOptions defaults) {
        return new CompareOptions(typeMatching == null ? defaults.typeMatching() : typeMatching,
                nullMatching == null ? defaults.nullMatching() : nullMatching,
                orderStrict == null ? defaults.orderStrict() : orderStrict,
                idProperties == null ? defaults.idProperties() : idProperties);
    }
}
