package com.example.comparator.model;

import java.util.List;

/** Report returned by all endpoints: a summary plus one result per compared path. */
public record CompareReport(Summary summary, List<PathResult> results) {

    public record Summary(int total, int identical, int different, int errors, TypeMatching typeMatching,
            NullMatching nullMatching, boolean orderStrict) {
    }

    /** {@code options} must be resolved (no null field): the report states the options actually used. */
    public static CompareReport of(List<PathResult> results, CompareOptions options) {
        int identical = 0;
        int different = 0;
        int errors = 0;
        for (PathResult result : results) {
            switch (result.status()) {
                case IDENTICAL -> identical++;
                case DIFFERENT -> different++;
                case ERROR -> errors++;
            }
        }
        return new CompareReport(new Summary(results.size(), identical, different, errors,
                options.typeMatching(), options.nullMatching(), Boolean.TRUE.equals(options.orderStrict())),
                List.copyOf(results));
    }
}
