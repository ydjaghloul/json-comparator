package com.example.comparator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Result for one compared path (or file pair, or inline document pair). Fields that do not apply are omitted:
 * {@code IDENTICAL} results have no {@code differences}, {@code ERROR} results have no counts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "path", "status", "summary", "differenceCount", "byType", "differences", "sourceFile",
        "targetFile", "reportFile" })
public record PathResult(
        String path,
        ResultStatus status,
        String summary,
        Integer differenceCount,
        Map<DifferenceType, Integer> byType,
        List<Difference> differences,
        String sourceFile,
        String targetFile,
        String reportFile) {

    /** IDENTICAL when the list is empty, otherwise DIFFERENT with the full list, its count and counts by type. */
    static PathResult compared(String path, List<Difference> differences, String sourceFile, String targetFile) {
        if (differences.isEmpty()) {
            return new PathResult(path, ResultStatus.IDENTICAL, "identical", null, null, null, sourceFile, targetFile,
                    null);
        }
        Map<DifferenceType, Integer> byType = new LinkedHashMap<>();
        for (Difference difference : differences) {
            byType.merge(difference.type(), 1, Integer::sum);
        }
        int count = differences.size();
        String summary = count + (count == 1 ? " difference" : " differences");
        return new PathResult(path, ResultStatus.DIFFERENT, summary, count, byType, List.copyOf(differences),
                sourceFile, targetFile, null);
    }

    static PathResult error(String path, String message, String sourceFile, String targetFile) {
        return new PathResult(path, ResultStatus.ERROR, message, null, null, null, sourceFile, targetFile, null);
    }

    /** Same result, with the location of its saved report (relative to {@code output.directory}). */
    PathResult withReportFile(String reportFile) {
        return new PathResult(path, status, summary, differenceCount, byType, differences, sourceFile, targetFile,
                reportFile);
    }
}
