package com.example.comparator.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.comparator.service.FileStorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Read access to what live runs saved under {@code output.directory}, used by the report viewer (UI) to open a
 * saved run and to show the full source and target documents next to each other.
 */
@RestController
@Tag(name = "Saved reports")
public class ReportFilesController {

    private final FileStorageService storage;

    public ReportFilesController(FileStorageService storage) {
        this.storage = storage;
    }

    @Operation(summary = "List the saved runs, newest first",
            description = "Names of the run folders under output.directory, e.g. 20261123-101500.")
    @GetMapping("/reports/runs")
    public List<String> runs() {
        return storage.listRuns();
    }

    @Operation(summary = "Read a saved JSON file",
            description = "path is relative to output.directory, as given in sourceFile, targetFile and reportFile "
                    + "(e.g. 20261123-101500/customers_42_orders/source.json). Only .json files below "
                    + "output.directory can be read.")
    @GetMapping(value = "/reports/file", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> file(
            @Parameter(description = "File path relative to output.directory") @RequestParam("path") String path) {
        try {
            return storage.readSaved(path).map(content -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header("X-Content-Type-Options", "nosniff").body(content))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such saved file"));
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
