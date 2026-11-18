package com.example.comparator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.example.comparator.ComparatorProperties.Output;
import com.example.comparator.ComparatorProperties.ServiceProperties;
import com.example.comparator.FileStorageService.RunFolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class FileStorageServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T13:50:00Z"), ZoneId.of("UTC"));

    @TempDir
    Path tempDir;

    private Path outputDir;
    private FileStorageService storage;

    @BeforeEach
    void setUp() throws Exception {
        outputDir = Files.createDirectories(tempDir.resolve("output"));
        ComparatorProperties properties = new ComparatorProperties(
                new ServiceProperties("http://source", "id", "secret", null, null, null),
                new ServiceProperties("http://target", null, null, null, null, null),
                List.of(), new Output(outputDir.toString()), null);
        storage = new FileStorageService(properties, MAPPER, CLOCK);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/customers/42/orders     | customers_42_orders",
            "/products/1001           | products_1001",
            "/                        | root",
            "''                       | root",
            "/search?q=a b&lang=fr    | search_q_a_b_lang_fr",
            "/v1.2/items-list_x       | v1.2_items-list_x",
            "//double                 | _double",
            "/café/über:1   | caf___ber_1",
            "/../etc/passwd           | .._etc_passwd" })
    void sanitizesPathIntoFileName(String path, String expected) {
        assertThat(FileStorageService.sanitize(path)).isEqualTo(expected);
    }

    @Test
    void savesPrettyPrintedSourceAndTargetFilesInRunFolder() throws Exception {
        RunFolder run = storage.startRun();
        String name = run.reserveName("/customers/42/orders");
        JsonNode body = MAPPER.readTree("{\"total\":100,\"items\":[1,2]}");

        String sourceFile = storage.save(run, name + FileStorageService.SOURCE_SUFFIX, body).orElseThrow();
        String targetFile = storage.save(run, name + FileStorageService.TARGET_SUFFIX, body).orElseThrow();

        assertThat(sourceFile).isEqualTo("20261118-135000/customers_42_orders.source.json");
        assertThat(targetFile).isEqualTo("20261118-135000/customers_42_orders.target.json");
        String written = Files.readString(outputDir.resolve(sourceFile));
        assertThat(written).contains("\n");
        assertThat(MAPPER.readTree(written)).isEqualTo(body);
    }

    @Test
    void secondRunInTheSameSecondGetsItsOwnFolder() {
        RunFolder first = storage.startRun();
        RunFolder second = storage.startRun();

        assertThat(first.folder().getFileName()).hasToString("20261118-135000");
        assertThat(second.folder().getFileName()).hasToString("20261118-135000-2");
    }

    @Test
    void pathsWithTheSameSanitizedNameDoNotOverwriteEachOther() {
        RunFolder run = storage.startRun();

        assertThat(run.reserveName("/a/b")).isEqualTo("a_b");
        assertThat(run.reserveName("/a_b")).isEqualTo("a_b_2");
    }

    @Test
    void nothingIsSavedWithoutBody() {
        RunFolder run = storage.startRun();

        assertThat(storage.save(run, "x.source.json", null)).isEmpty();
    }

    @Test
    void saveFailureIsNotFatal() throws Exception {
        Path notADirectory = Files.writeString(tempDir.resolve("file"), "x");
        ComparatorProperties properties = new ComparatorProperties(
                new ServiceProperties("http://source", null, null, null, null, null),
                new ServiceProperties("http://target", null, null, null, null, null),
                List.of(), new Output(notADirectory.resolve("sub").toString()), null);
        FileStorageService broken = new FileStorageService(properties, MAPPER, CLOCK);

        RunFolder run = broken.startRun();

        assertThat(run.folder()).isNull();
        assertThat(broken.save(run, "x.source.json", MAPPER.readTree("{}"))).isEmpty();
    }
}
