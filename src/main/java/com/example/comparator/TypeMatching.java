package com.example.comparator;

/**
 * How strictly JSON types are compared.
 * <ul>
 * <li>{@code STRICT}: {@code 11} and {@code "11"} are a {@code TYPE_MISMATCH}.</li>
 * <li>{@code LENIENT}: a string is converted before comparing it with a number or a boolean, so {@code "11"}
 * equals {@code 11} and {@code "true"} equals {@code true}. {@code null}, objects and arrays stay strict.</li>
 * </ul>
 */
public enum TypeMatching {
    STRICT,
    LENIENT
}
