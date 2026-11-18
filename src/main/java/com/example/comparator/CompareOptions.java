package com.example.comparator;

/**
 * Per-comparison options, given as query parameters on every endpoint. A {@code null} field means "use the
 * configured default" ({@code comparison.type-matching}, {@code comparison.null-matching}).
 */
public record CompareOptions(TypeMatching typeMatching, NullMatching nullMatching) {

    /** Fills the unset options from the given defaults. */
    public CompareOptions withDefaults(CompareOptions defaults) {
        return new CompareOptions(typeMatching == null ? defaults.typeMatching() : typeMatching,
                nullMatching == null ? defaults.nullMatching() : nullMatching);
    }
}
