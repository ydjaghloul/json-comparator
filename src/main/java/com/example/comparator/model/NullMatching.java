package com.example.comparator.model;

/**
 * How a property that is {@code null} on one side and absent on the other is treated.
 * <ul>
 * <li>{@code STRICT}: {@code {"a": null}} vs {@code {}} is a {@code MISSING_IN_*} difference.</li>
 * <li>{@code LENIENT}: it is not a difference. Only object properties whose value is exactly {@code null} are
 * concerned: a missing non-null value, or a missing array element, is still reported.</li>
 * </ul>
 */
public enum NullMatching {
    STRICT,
    LENIENT
}
