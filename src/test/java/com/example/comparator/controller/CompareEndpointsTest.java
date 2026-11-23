package com.example.comparator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercises the three endpoints against a fake source and target service (JDK HTTP server on a random port).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompareEndpointsTest {

    static final String CLIENT_ID = "test-client-id";
    static final String CLIENT_SECRET = "test-client-secret-value";

    static final String ORDERS_42_SOURCE = """
            {"total":100,"items":[{"sku":"A","qty":1},{"sku":"B","qty":2,"discount":5}],"customer":42}""";
    static final String ORDERS_42_TARGET = """
            {"customer":42,"items":[{"qty":1,"sku":"A"},{"qty":2,"sku":"B"}],"total":105}""";

    static final Path OUTPUT_DIR = createTempDirectory();
    static final FakeServices SERVICES = FakeServices.start();

    static {
        SERVICES.respond("source", "/products/1001", 200, "{\"id\":1001,\"name\":\"Pen\",\"price\":1.5}", 0);
        SERVICES.respond("target", "/products/1001", 200, "{\"price\":1.50,\"name\":\"Pen\",\"id\":1001}", 0);
        SERVICES.respond("source", "/customers/42/orders", 200, ORDERS_42_SOURCE, 0);
        SERVICES.respond("target", "/customers/42/orders", 200, ORDERS_42_TARGET, 0);
        SERVICES.respond("source", "/customers/43/orders", 200, "{\"orders\":[]}", 0);
        SERVICES.respond("target", "/customers/43/orders", 500, "<html>Internal Server Error</html>", 0);
        SERVICES.respond("source", "/status/check", 200, "{\"state\":\"ok\"}", 0);
        SERVICES.respond("target", "/status/check", 404, "{\"error\":\"not found\"}", 0);
        SERVICES.respond("source", "/slow", 200, "{}", 0);
        SERVICES.respond("target", "/slow", 200, "{}", 3000);
        SERVICES.respond("alt", "/products/1001", 200, "{\"id\":\"1001\",\"name\":\"Pen\",\"price\":\"1.5\"}", 0);
        SERVICES.respond("alt", "/customers/42/orders", 200, ORDERS_42_SOURCE, 0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("source_service.base-url", () -> SERVICES.baseUrl() + "/source");
        registry.add("source_service.client-id", () -> CLIENT_ID);
        registry.add("source_service.client-secret", () -> CLIENT_SECRET);
        registry.add("source_service.read-timeout", () -> "1s");
        registry.add("target_service.base-url", () -> SERVICES.baseUrl() + "/target");
        registry.add("target_service.read-timeout", () -> "1s");
        registry.add("paths[0]", () -> "/products/1001");
        registry.add("paths[1]", () -> "/customers/42/orders");
        registry.add("paths[2]", () -> "/customers/43/orders");
        registry.add("paths[3]", () -> "/status/check");
        registry.add("paths[4]", () -> "/slow");
        registry.add("output.directory", OUTPUT_DIR::toString);
    }

    @AfterAll
    static void stopServices() {
        SERVICES.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Nested
    class LiveCompare {

        @Test
        void reportsEveryPathWithFullDetail() throws Exception {
            JsonNode report = call("/compare", null, status().isOk());

            assertThat(report.at("/summary")).isEqualTo(json(
                    "{\"total\":5,\"identical\":1,\"different\":2,\"errors\":2,\"typeMatching\":\"STRICT\",\"nullMatching\":\"STRICT\",\"orderStrict\":false}"));

            JsonNode identical = result(report, "/products/1001");
            assertThat(identical.get("status").asText()).isEqualTo("IDENTICAL");
            assertThat(identical.get("summary").asText()).isEqualTo("identical");
            assertThat(identical.has("differences")).isFalse();
            assertThat(identical.has("differenceCount")).isFalse();

            JsonNode different = result(report, "/customers/42/orders");
            assertThat(different.get("status").asText()).isEqualTo("DIFFERENT");
            assertThat(different.get("summary").asText()).isEqualTo("2 differences");
            assertThat(different.get("differenceCount").asInt()).isEqualTo(2);
            assertThat(different.get("byType")).isEqualTo(json(
                    "{\"VALUE_MISMATCH\":1,\"MISSING_IN_TARGET\":1}"));
            assertThat(different.get("differences")).isEqualTo(json("""
                    [{"jsonPath":"$.total","type":"VALUE_MISMATCH","source":100,"target":105,
                      "message":"$.total: source=100, target=105"},
                     {"jsonPath":"$.items[1].discount","location":"$.items[sku=B].discount","type":"MISSING_IN_TARGET",
                      "source":5,"target":null,
                      "message":"$.items[sku=B].discount: present in source (5), missing in target"}]"""));

            JsonNode error = result(report, "/customers/43/orders");
            assertThat(error.get("status").asText()).isEqualTo("ERROR");
            assertThat(error.get("summary").asText()).isEqualTo("target_service returned HTTP 500");
            assertThat(error.has("sourceFile")).isTrue();
            assertThat(error.has("targetFile")).isFalse();

            JsonNode statusMismatch = result(report, "/status/check");
            assertThat(statusMismatch.get("status").asText()).isEqualTo("DIFFERENT");
            assertThat(statusMismatch.get("differences").get(0)).isEqualTo(json("""
                    {"jsonPath":"$status","type":"STATUS_MISMATCH","source":200,"target":404,
                     "message":"$status: source=200, target=404"}"""));

            JsonNode timeout = result(report, "/slow");
            assertThat(timeout.get("status").asText()).isEqualTo("ERROR");
            assertThat(timeout.get("summary").asText()).startsWith("target_service call failed");
        }

        @Test
        void savesResponsesAsPrettyPrintedJson() throws Exception {
            JsonNode report = call("/compare", null, status().isOk());
            JsonNode different = result(report, "/customers/42/orders");

            String sourceFile = different.get("sourceFile").asText();
            String targetFile = different.get("targetFile").asText();
            assertThat(sourceFile).matches("\\d{8}-\\d{6}(-\\d+)?/customers_42_orders/source\\.json");
            assertThat(targetFile).matches("\\d{8}-\\d{6}(-\\d+)?/customers_42_orders/target\\.json");
            String savedSource = Files.readString(OUTPUT_DIR.resolve(sourceFile));
            assertThat(savedSource).contains(System.lineSeparator());
            assertThat(json(savedSource)).isEqualTo(json(ORDERS_42_SOURCE));
            assertThat(json(Files.readString(OUTPUT_DIR.resolve(targetFile)))).isEqualTo(json(ORDERS_42_TARGET));

            // A non-2xx response is saved when its body is JSON
            JsonNode statusMismatch = result(report, "/status/check");
            assertThat(OUTPUT_DIR.resolve(statusMismatch.get("targetFile").asText())).exists();
        }

        @Test
        void savesAFolderPerPathWithBothResponsesAndItsReport() throws Exception {
            JsonNode report = call("/compare", null, status().isOk());
            JsonNode different = result(report, "/customers/42/orders");

            String reportFile = different.get("reportFile").asText();
            assertThat(reportFile).matches("\\d{8}-\\d{6}(-\\d+)?/customers_42_orders/report\\.json");
            java.nio.file.Path folder = OUTPUT_DIR.resolve(reportFile).getParent();
            assertThat(folder.resolve("source.json")).exists();
            assertThat(folder.resolve("target.json")).exists();
            // the saved report is the result of this path, including where its files are
            assertThat(json(Files.readString(OUTPUT_DIR.resolve(reportFile)))).isEqualTo(different);

            // an ERROR result and an IDENTICAL result are saved too
            assertThat(OUTPUT_DIR.resolve(result(report, "/customers/43/orders").get("reportFile").asText()))
                    .exists();
            assertThat(OUTPUT_DIR.resolve(result(report, "/products/1001").get("reportFile").asText())).exists();

            // the whole run is saved at the root of the run folder
            assertThat(json(Files.readString(folder.getParent().resolve("report.json")))).isEqualTo(report);
        }

        @Test
        void compareAcceptsHeadersInTheBodyForThisRunOnly() throws Exception {
            int sourceBefore = SERVICES.requests("source").size();

            call("/compare", "{\"sourceHeaders\":{\"X-Run\":\"r1\"},\"targetHeaders\":{\"X-Run\":\"r2\"}}",
                    status().isOk());
            call("/compare", null, status().isOk());

            var sourceRequests = SERVICES.requests("source");
            assertThat(sourceRequests.subList(sourceBefore, sourceRequests.size()))
                    .anySatisfy(headers -> assertThat(headers.get("x-run")).isEqualTo("r1"))
                    .anySatisfy(headers -> assertThat(headers).doesNotContainKey("x-run"));
            assertThat(SERVICES.requests("target")).anySatisfy(headers -> assertThat(headers.get("x-run")).isEqualTo("r2"));
            call("/compare", "{\"sourceHeaders\":{\"Host\":\"x\"}}", status().isBadRequest());
        }

        @Test
        void credentialsAreOnlySentToSourceAndNeverSaved() throws Exception {
            SERVICES.clearRequests(); // other tests send their own headers
            call("/compare", null, status().isOk());

            assertThat(SERVICES.requests("source")).isNotEmpty().allSatisfy(headers -> {
                assertThat(headers.get("client_id")).isEqualTo(CLIENT_ID);
                assertThat(headers.get("client_secret")).isEqualTo(CLIENT_SECRET);
            });
            assertThat(SERVICES.requests("target")).isNotEmpty().allSatisfy(headers -> {
                assertThat(headers).doesNotContainKey("client_id");
                assertThat(headers).doesNotContainKey("client_secret");
            });
            try (Stream<Path> files = Files.walk(OUTPUT_DIR)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file)).doesNotContain(CLIENT_SECRET).doesNotContain(CLIENT_ID);
                }
            }
        }
    }

    @Nested
    class ReportViewer {

        @Test
        void savedRunsAndFilesAreReadable() throws Exception {
            JsonNode report = call("/compare", null, status().isOk());
            JsonNode different = result(report, "/customers/42/orders");
            String sourceFile = different.get("sourceFile").asText();
            String run = sourceFile.substring(0, sourceFile.indexOf('/'));

            assertThat(json(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/reports/runs")).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString()).toString()).contains(run);
            String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/reports/file").param("path", sourceFile)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(json(body)).isEqualTo(json(ORDERS_42_SOURCE));
            // the run report can be opened by the viewer
            JsonNode runReport = json(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/reports/file").param("path", run + "/report.json"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(runReport.at("/summary/total").asInt()).isEqualTo(report.at("/summary/total").asInt());
        }

        @Test
        void onlyJsonFilesBelowTheOutputFolderCanBeRead() throws Exception {
            call("/compare", null, status().isOk());
            for (String bad : new String[] { "../secret.json", "/etc/passwd.json", "a/../../b.json", "x\\y.json",
                    "report.txt", "", "a//b.json", "./a.json", "../b.json" }) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/reports/file").param("path", bad)).andExpect(status().isBadRequest());
            }
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/reports/file").param("path", "20000101-000000/missing.json"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void theViewerIsServedAsStaticPages() throws Exception {
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/index.html")).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString()).contains("Comparison report viewer").contains("js/app.js");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/js/app.js")).andExpect(status().isOk());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/css/report.css")).andExpect(status().isOk());
        }
    }

    @Nested
    class CompareFiles {

        @Test
        void identicalFiles() throws Exception {
            JsonNode report = upload("", file("sourceFile", "orders.source.json", "{\"b\":[1,2],\"a\":1}"),
                    file("targetFile", "orders.target.json", "{\"a\":1,\"b\":[1,2]}"), status().isOk());

            assertThat(report).isEqualTo(json("""
                    {"summary":{"total":1,"identical":1,"different":0,"errors":0,
                                "typeMatching":"STRICT","nullMatching":"STRICT","orderStrict":false},
                     "results":[{"path":"orders.source.json","status":"IDENTICAL","summary":"identical"}]}"""));
        }

        @Test
        void differentFilesHaveTheSameDetailAsLiveCompare() throws Exception {
            JsonNode report = upload("", file("sourceFile", "s.json", ORDERS_42_SOURCE),
                    file("targetFile", "t.json", ORDERS_42_TARGET), status().isOk());

            assertThat(report.at("/summary/different").asInt()).isEqualTo(1);
            JsonNode result = report.at("/results/0");
            assertThat(result.get("status").asText()).isEqualTo("DIFFERENT");
            assertThat(result.get("differenceCount").asInt()).isEqualTo(2);
            assertThat(result.get("differences")).extracting(d -> d.get("jsonPath").asText())
                    .containsExactly("$.total", "$.items[1].discount");
            assertThat(result.has("sourceFile")).isFalse();
            assertThat(result.has("targetFile")).isFalse();
        }

        @Test
        void nothingIsSaved() throws Exception {
            long before = countFiles();

            upload("", file("sourceFile", "s.json", "{\"a\":1}"), file("targetFile", "t.json", "{\"a\":2}"),
                    status().isOk());

            assertThat(countFiles()).isEqualTo(before);
        }

        @Test
        void invalidOrEmptyJsonIsAnErrorResult() throws Exception {
            JsonNode invalid = upload("", file("sourceFile", "s.json", "{\"a\":1}"),
                    file("targetFile", "t.json", "{not json"), status().isOk());
            JsonNode empty = upload("", file("sourceFile", "s.json", ""),
                    file("targetFile", "t.json", "{}"), status().isOk());

            assertThat(invalid.at("/summary/errors").asInt()).isEqualTo(1);
            assertThat(invalid.at("/results/0/status").asText()).isEqualTo("ERROR");
            assertThat(invalid.at("/results/0/summary").asText()).startsWith("target file t.json is not valid JSON");
            assertThat(empty.at("/results/0/summary").asText()).isEqualTo("source file s.json is not valid JSON: file is empty");
        }

        @Test
        void directoryPartOfTheFileNameIsDropped() throws Exception {
            JsonNode report = upload("", file("sourceFile", "C:\\data\\run1\\orders.json", "{}"),
                    file("targetFile", "t.json", "{}"), status().isOk());

            assertThat(report.at("/results/0/path").asText()).isEqualTo("orders.json");
        }

        @Test
        void missingFileIsBadRequest() throws Exception {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/compare/files")
                    .file(file("sourceFile", "s.json", "{}"))).andExpect(status().isBadRequest());
        }

        @Test
        void jsonBodyIsNotAccepted() throws Exception {
            call("/compare/files", "{\"sourceFile\":\"a.json\",\"targetFile\":\"b.json\"}",
                    status().isUnsupportedMediaType());
        }
    }

    @Nested
    class CompareJson {

        @Test
        void identicalDocumentsWithDifferentPropertyOrder() throws Exception {
            JsonNode report = call("/compare/json", """
                    {"source":{"total":100,"items":[1,2]},"target":{"items":[1,2],"total":100}}""",
                    status().isOk());

            assertThat(report).isEqualTo(json("""
                    {"summary":{"total":1,"identical":1,"different":0,"errors":0,"typeMatching":"STRICT","nullMatching":"STRICT","orderStrict":false},
                     "results":[{"path":"inline","status":"IDENTICAL","summary":"identical"}]}"""));
        }

        @Test
        void differentDocuments() throws Exception {
            JsonNode report = call("/compare/json", """
                    {"name":"my check","source":{"total":100,"items":[1,2]},"target":{"items":[1,2],"total":105}}""",
                    status().isOk());

            assertThat(report).isEqualTo(json("""
                    {"summary":{"total":1,"identical":0,"different":1,"errors":0,"typeMatching":"STRICT","nullMatching":"STRICT","orderStrict":false},
                     "results":[{"path":"my check","status":"DIFFERENT","summary":"1 difference",
                       "differenceCount":1,"byType":{"VALUE_MISMATCH":1},
                       "differences":[{"jsonPath":"$.total","type":"VALUE_MISMATCH","source":100,"target":105,
                                       "message":"$.total: source=100, target=105"}]}]}"""));
        }

        @Test
        void anyJsonValueIsAccepted() throws Exception {
            assertThat(call("/compare/json", "{\"source\":null,\"target\":null}", status().isOk())
                    .at("/results/0/status").asText()).isEqualTo("IDENTICAL");
            assertThat(call("/compare/json", "{\"source\":\"1\",\"target\":1}", status().isOk())
                    .at("/results/0/differences/0/type").asText()).isEqualTo("TYPE_MISMATCH");
        }

        @Test
        void missingSourceOrTargetIsBadRequest() throws Exception {
            call("/compare/json", "{\"target\":{}}", status().isBadRequest());
            call("/compare/json", "{\"source\":{}}", status().isBadRequest());
            call("/compare/json", "[1,2]", status().isBadRequest());
        }

        @Test
        void reportFormatMatchesLiveCompare() throws Exception {
            JsonNode live = result(call("/compare", null, status().isOk()), "/customers/42/orders");

            ObjectNode request = mapper.createObjectNode();
            request.put("name", "/customers/42/orders");
            request.set("source", json(ORDERS_42_SOURCE));
            request.set("target", json(ORDERS_42_TARGET));
            JsonNode inline = call("/compare/json", request.toString(), status().isOk()).at("/results/0");

            ObjectNode liveWithoutFiles = live.deepCopy();
            liveWithoutFiles.remove(List.of("sourceFile", "targetFile", "reportFile"));
            assertThat(inline).isEqualTo(liveWithoutFiles);
        }
    }

    @Nested
    class CompareCustom {

        @Test
        void usesBaseUrlsAndPathsFromTheRequest() throws Exception {
            JsonNode report = call("/compare/custom", customRequest(SERVICES.baseUrl() + "/alt",
                    SERVICES.baseUrl() + "/target", "/customers/42/orders", "/customers/43/orders"), status().isOk());

            assertThat(report.at("/summary")).isEqualTo(json(
                    "{\"total\":2,\"identical\":0,\"different\":1,\"errors\":1,\"typeMatching\":\"STRICT\",\"nullMatching\":\"STRICT\",\"orderStrict\":false}"));
            JsonNode different = result(report, "/customers/42/orders");
            assertThat(different.get("differences")).extracting(d -> d.get("jsonPath").asText())
                    .containsExactly("$.total", "$.items[1].discount");
            assertThat(OUTPUT_DIR.resolve(different.get("sourceFile").asText())).exists();
            assertThat(OUTPUT_DIR.resolve(different.get("targetFile").asText())).exists();
            // /customers/43/orders is not served by "alt": 404 without body
            assertThat(result(report, "/customers/43/orders").get("summary").asText())
                    .contains("source_service returned HTTP 404");
        }

        @Test
        void credentialsAreOnlySentToTrustedSourceUrls() throws Exception {
            int altBefore = SERVICES.requests("alt").size();
            int sourceBefore = SERVICES.requests("source").size();

            call("/compare/custom", customRequest(SERVICES.baseUrl() + "/alt", SERVICES.baseUrl() + "/target",
                    "/products/1001"), status().isOk());
            call("/compare/custom", customRequest(SERVICES.baseUrl() + "/source/", SERVICES.baseUrl() + "/target",
                    "/products/1001"), status().isOk());

            assertThat(SERVICES.requests("alt").subList(altBefore, SERVICES.requests("alt").size()))
                    .singleElement().satisfies(headers -> {
                        assertThat(headers).doesNotContainKey("client_id");
                        assertThat(headers).doesNotContainKey("client_secret");
                    });
            assertThat(SERVICES.requests("source").subList(sourceBefore, SERVICES.requests("source").size()))
                    .singleElement().satisfies(headers -> {
                        assertThat(headers.get("client_id")).isEqualTo(CLIENT_ID);
                        assertThat(headers.get("client_secret")).isEqualTo(CLIENT_SECRET);
                    });
            assertThat(SERVICES.requests("target")).allSatisfy(headers -> {
                assertThat(headers).doesNotContainKey("client_id");
                assertThat(headers).doesNotContainKey("client_secret");
            });
        }

        @Test
        void headersOfTheRequestAreSentToTheirSideOnlyAndNeverSavedOrReturned() throws Exception {
            int altBefore = SERVICES.requests("alt").size();
            int targetBefore = SERVICES.requests("target").size();
            ObjectNode request = (ObjectNode) json(customRequest(SERVICES.baseUrl() + "/alt",
                    SERVICES.baseUrl() + "/target", "/products/1001"));
            request.putObject("sourceHeaders").put("Authorization", "Bearer tok-source-123").put("X-Trace", "t1");
            request.putObject("targetHeaders").put("X-Api-Key", "key-target-456");

            JsonNode report = call("/compare/custom", request.toString(), status().isOk());

            assertThat(SERVICES.requests("alt").subList(altBefore, SERVICES.requests("alt").size()))
                    .singleElement().satisfies(headers -> {
                        assertThat(headers.get("authorization")).isEqualTo("Bearer tok-source-123");
                        assertThat(headers.get("x-trace")).isEqualTo("t1");
                        assertThat(headers).doesNotContainKey("x-api-key");
                        assertThat(headers).doesNotContainKey("client_id"); // untrusted source: no credentials
                    });
            assertThat(SERVICES.requests("target").subList(targetBefore, SERVICES.requests("target").size()))
                    .singleElement().satisfies(headers -> {
                        assertThat(headers.get("x-api-key")).isEqualTo("key-target-456");
                        assertThat(headers).doesNotContainKey("authorization");
                    });
            assertThat(report.toString()).doesNotContain("tok-source-123").doesNotContain("key-target-456");
            try (var files = Files.walk(OUTPUT_DIR)) {
                for (java.nio.file.Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file)).doesNotContain("tok-source-123")
                            .doesNotContain("key-target-456");
                }
            }
        }

        @Test
        void aRequestHeaderWinsOverTheConfiguredCredentialWithTheSameName() throws Exception {
            int before = SERVICES.requests("source").size();
            ObjectNode request = (ObjectNode) json(customRequest(SERVICES.baseUrl() + "/source",
                    SERVICES.baseUrl() + "/target", "/products/1001"));
            request.putObject("sourceHeaders").put("Client_Id", "other-client");

            call("/compare/custom", request.toString(), status().isOk());

            assertThat(SERVICES.requests("source").subList(before, SERVICES.requests("source").size()))
                    .singleElement().satisfies(headers -> {
                        assertThat(headers.get("client_id")).isEqualTo("other-client");
                        assertThat(headers.get("client_secret")).isEqualTo(CLIENT_SECRET);
                    });
        }

        @Test
        void invalidHeadersAreRejectedWithoutEchoingTheirValue() throws Exception {
            for (String[] bad : new String[][] { { "bad name", "v" }, { "Host", "evil" },
                    { "X-A", "line1\r\nInjected: yes" }, { "", "v" } }) {
                ObjectNode request = (ObjectNode) json(customRequest(SERVICES.baseUrl() + "/alt",
                        SERVICES.baseUrl() + "/target", "/products/1001"));
                request.putObject("sourceHeaders").put(bad[0], bad[1]);
                String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/compare/custom").contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString())).andExpect(status().isBadRequest()).andReturn().getResponse()
                        .getContentAsString();
                assertThat(body).doesNotContain("evil").doesNotContain("Injected");
            }
        }

        @Test
        void invalidRequestsAreRejected() throws Exception {
            String target = SERVICES.baseUrl() + "/target";
            call("/compare/custom", "{\"targetBaseUrl\":\"" + target + "\",\"paths\":[\"/a\"]}",
                    status().isBadRequest());
            call("/compare/custom", customRequest("ftp://example.com", target, "/a"), status().isBadRequest());
            call("/compare/custom", customRequest("not a url", target, "/a"), status().isBadRequest());
            call("/compare/custom", customRequest("/relative", target, "/a"), status().isBadRequest());
            call("/compare/custom", customRequest(target, target), status().isBadRequest());
            call("/compare/custom", customRequest(target, target, " "), status().isBadRequest());
            call("/compare/custom", "{\"sourceBaseUrl\":\"" + target + "\",\"targetBaseUrl\":\"" + target + "\"}",
                    status().isBadRequest());
        }

        private String customRequest(String sourceBaseUrl, String targetBaseUrl, String... paths) {
            ObjectNode request = mapper.createObjectNode();
            request.put("sourceBaseUrl", sourceBaseUrl);
            request.put("targetBaseUrl", targetBaseUrl);
            var array = request.putArray("paths");
            for (String path : paths) {
                array.add(path);
            }
            return request.toString();
        }
    }

    @Nested
    class TypeMatchingParameter {

        @Test
        void jsonEndpoint() throws Exception {
            String body = "{\"source\":{\"n\":\"11\",\"ok\":\"true\"},\"target\":{\"n\":11,\"ok\":true}}";

            JsonNode strict = call("/compare/json", body, status().isOk());
            JsonNode lenient = call("/compare/json?typeMatching=LENIENT", body, status().isOk());

            assertThat(strict.at("/summary/typeMatching").asText()).isEqualTo("STRICT");
            assertThat(strict.at("/results/0/byType")).isEqualTo(json("{\"TYPE_MISMATCH\":2}"));
            assertThat(lenient.at("/summary/typeMatching").asText()).isEqualTo("LENIENT");
            assertThat(lenient.at("/results/0/status").asText()).isEqualTo("IDENTICAL");
        }

        @Test
        void filesEndpoint() throws Exception {
            assertThat(upload("", file("sourceFile", "s.json", "{\"id\":\"7\"}"),
                    file("targetFile", "t.json", "{\"id\":7}"), status().isOk()).at("/results/0/status").asText())
                    .isEqualTo("DIFFERENT");
            assertThat(upload("?typeMatching=LENIENT", file("sourceFile", "s.json", "{\"id\":\"7\"}"),
                    file("targetFile", "t.json", "{\"id\":7}"), status().isOk()).at("/results/0/status").asText())
                    .isEqualTo("IDENTICAL");
        }

        @Test
        void liveEndpoints() throws Exception {
            // "alt" serves /products/1001 with numbers as strings; target serves them as numbers
            ObjectNode request = mapper.createObjectNode();
            request.put("sourceBaseUrl", SERVICES.baseUrl() + "/alt");
            request.put("targetBaseUrl", SERVICES.baseUrl() + "/target");
            request.putArray("paths").add("/products/1001");

            assertThat(call("/compare/custom", request.toString(), status().isOk()).at("/results/0/byType"))
                    .isEqualTo(json("{\"TYPE_MISMATCH\":2}"));
            assertThat(call("/compare/custom?typeMatching=LENIENT", request.toString(), status().isOk())
                    .at("/results/0/status").asText()).isEqualTo("IDENTICAL");
            assertThat(call("/compare?typeMatching=LENIENT", null, status().isOk())
                    .at("/summary/typeMatching").asText()).isEqualTo("LENIENT");
        }

        @Test
        void orderStrictAndIdPropertiesParameters() throws Exception {
            String body = "{\"source\":{\"l\":[{\"ref\":1,\"v\":1},{\"ref\":2,\"v\":2}]},"
                    + "\"target\":{\"l\":[{\"ref\":2,\"v\":2},{\"ref\":1,\"v\":1}]}}";

            JsonNode relaxed = call("/compare/json?idProperties=ref", body, status().isOk());
            assertThat(relaxed.at("/summary/orderStrict").asBoolean()).isFalse();
            assertThat(relaxed.at("/summary/identical").asInt()).isEqualTo(1);

            JsonNode strict = call("/compare/json?orderStrict=true", body, status().isOk());
            assertThat(strict.at("/summary/orderStrict").asBoolean()).isTrue();
            assertThat(strict.at("/summary/different").asInt()).isEqualTo(1);

            assertThat(call("/compare/json?orderStrict=maybe", body, status().isBadRequest())).isNotNull();
        }

        @Test
        void nullMatchingParameter() throws Exception {
            String body = "{\"source\":{\"a\":null,\"b\":1},\"target\":{\"b\":1,\"c\":null}}";

            JsonNode strict = call("/compare/json", body, status().isOk());
            JsonNode lenient = call("/compare/json?nullMatching=LENIENT", body, status().isOk());

            assertThat(strict.at("/summary/nullMatching").asText()).isEqualTo("STRICT");
            assertThat(strict.at("/results/0/byType")).isEqualTo(json(
                    "{\"MISSING_IN_TARGET\":1,\"MISSING_IN_SOURCE\":1}"));
            assertThat(lenient.at("/summary/nullMatching").asText()).isEqualTo("LENIENT");
            assertThat(lenient.at("/results/0/status").asText()).isEqualTo("IDENTICAL");
            assertThat(call("/compare/json?nullMatching=MAYBE", body, status().isBadRequest())).isNotNull();
        }

        @Test
        void bothParametersTogether() throws Exception {
            JsonNode report = upload("?typeMatching=LENIENT&nullMatching=LENIENT",
                    file("sourceFile", "s.json", "{\"id\":\"7\",\"note\":null}"),
                    file("targetFile", "t.json", "{\"id\":7}"), status().isOk());

            assertThat(report.at("/results/0/status").asText()).isEqualTo("IDENTICAL");
            assertThat(report.at("/summary/typeMatching").asText()).isEqualTo("LENIENT");
            assertThat(report.at("/summary/nullMatching").asText()).isEqualTo("LENIENT");
        }

        @Test
        void invalidValueIsBadRequest() throws Exception {
            call("/compare/json?typeMatching=SOMETIMES", "{\"source\":1,\"target\":1}", status().isBadRequest());
        }
    }

    @Nested
    class Swagger {

        @Test
        void openApiDescriptionListsTheFourEndpoints() throws Exception {
            String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            JsonNode api = mapper.readTree(body);

            assertThat(api.at("/info/title").asText()).isEqualTo("REST Service Comparator");
            for (String path : List.of("/compare", "/compare/custom", "/compare/files", "/compare/json")) {
                JsonNode post = api.path("paths").path(path).path("post");
                assertThat(post.isMissingNode()).as(path).isFalse();
                assertThat(post.path("parameters").findValuesAsText("name")).as(path)
                        .contains("typeMatching", "nullMatching");
            }
        }

        @Test
        void swaggerUiIsServed() throws Exception {
            int status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/swagger-ui.html")).andReturn().getResponse().getStatus();

            assertThat(status).isNotEqualTo(404);
        }
    }

    @Nested
    class DifferenceDetail {

        @Test
        void everyDifferenceIsCompleteAndCountsMatch() throws Exception {
            String longText = "x".repeat(10_000);
            ObjectNode bigObject = mapper.createObjectNode();
            for (int i = 0; i < 500; i++) {
                bigObject.put("field" + i, "value-" + i);
            }
            ObjectNode source = mapper.createObjectNode();
            source.put("text", longText);
            source.set("details", bigObject);
            source.put("id", 1);
            source.putArray("tags").add("a").add("b");
            source.putNull("comment");
            ObjectNode target = mapper.createObjectNode();
            target.put("text", longText + "!");
            target.put("id", "1");
            target.putArray("tags").add("a");
            target.put("extra", true);
            target.putNull("comment");
            ObjectNode request = mapper.createObjectNode();
            request.set("source", source);
            request.set("target", target);

            JsonNode result = call("/compare/json", request.toString(), status().isOk()).at("/results/0");
            JsonNode differences = result.get("differences");

            assertThat(differences).hasSize(5);
            for (JsonNode difference : differences) {
                assertThat(difference.has("jsonPath")).isTrue();
                assertThat(difference.has("type")).isTrue();
                assertThat(difference.has("source")).isTrue();
                assertThat(difference.has("target")).isTrue();
                assertThat(difference.get("message").asText()).startsWith(difference.get("jsonPath").asText() + ":");
            }
            JsonNode text = byPath(differences, "$.text");
            assertThat(text.get("source").asText()).isEqualTo(longText);
            assertThat(text.get("target").asText()).isEqualTo(longText + "!");
            assertThat(text.get("message").asText()).contains(longText + "!");
            JsonNode details = byPath(differences, "$.details");
            assertThat(details.get("type").asText()).isEqualTo("MISSING_IN_TARGET");
            assertThat(details.get("source")).isEqualTo(bigObject);
            assertThat(details.get("target").isNull()).isTrue();
            assertThat(details.get("message").asText()).contains(bigObject.toString());

            assertThat(result.get("differenceCount").asInt()).isEqualTo(differences.size());
            Map<String, Integer> expectedByType = new HashMap<>();
            differences.forEach(d -> expectedByType.merge(d.get("type").asText(), 1, Integer::sum));
            Map<String, Integer> byType = new HashMap<>();
            result.get("byType").fields().forEachRemaining(e -> byType.put(e.getKey(), e.getValue().asInt()));
            assertThat(byType).isEqualTo(expectedByType).isEqualTo(Map.of(
                    "VALUE_MISMATCH", 1, "MISSING_IN_TARGET", 2, "TYPE_MISMATCH", 1, "MISSING_IN_SOURCE", 1));
        }

        private JsonNode byPath(JsonNode differences, String path) {
            for (JsonNode difference : differences) {
                if (path.equals(difference.get("jsonPath").asText())) {
                    return difference;
                }
            }
            throw new AssertionError("No difference at " + path + " in " + differences);
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private JsonNode call(String uri, String body, ResultMatcher expectedStatus) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String response = mvc.perform(request).andExpect(expectedStatus).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return response.isEmpty() ? mapper.nullNode() : mapper.readTree(response);
    }

    private JsonNode upload(String query, MockMultipartFile source, MockMultipartFile target,
            ResultMatcher expectedStatus) throws Exception {
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/compare/files" + query).file(source).file(target))
                .andExpect(expectedStatus).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return response.isEmpty() ? mapper.nullNode() : mapper.readTree(response);
    }

    private static MockMultipartFile file(String part, String fileName, String content) {
        return new MockMultipartFile(part, fileName, MediaType.APPLICATION_JSON_VALUE,
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static long countFiles() throws IOException {
        if (!Files.exists(OUTPUT_DIR)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(OUTPUT_DIR)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static JsonNode result(JsonNode report, String path) {
        for (JsonNode result : report.get("results")) {
            if (path.equals(result.get("path").asText())) {
                return result;
            }
        }
        throw new AssertionError("No result for " + path + " in " + report);
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("comparator-test").resolve("output");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fake source and target services. Records the headers of every request, per side. */
    static final class FakeServices {

        private final HttpServer server;
        private final Map<String, Reply> replies = new ConcurrentHashMap<>();
        private final Map<String, List<Map<String, String>>> requests = new ConcurrentHashMap<>();

        private record Reply(int status, String body, long delayMillis) {
        }

        private FakeServices(HttpServer server) {
            this.server = server;
        }

        static FakeServices start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeServices services = new FakeServices(server);
                server.createContext("/source", exchange -> services.handle("source", exchange));
                server.createContext("/target", exchange -> services.handle("target", exchange));
                server.createContext("/alt", exchange -> services.handle("alt", exchange));
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                return services;
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void respond(String side, String path, int status, String body, long delayMillis) {
            replies.put(side + path, new Reply(status, body, delayMillis));
        }

        List<Map<String, String>> requests(String side) {
            return requests.getOrDefault(side, List.of());
        }

        void clearRequests() {
            requests.clear();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(String side, HttpExchange exchange) throws IOException {
            Map<String, String> headers = new HashMap<>();
            for (Iterator<String> names = exchange.getRequestHeaders().keySet().iterator(); names.hasNext();) {
                String name = names.next();
                headers.put(name.toLowerCase(), exchange.getRequestHeaders().getFirst(name));
            }
            requests.computeIfAbsent(side, k -> new CopyOnWriteArrayList<>()).add(headers);

            String path = exchange.getRequestURI().getPath().substring(("/" + side).length());
            Reply reply = replies.getOrDefault(side + path, new Reply(404, "", 0));
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    reply.body().startsWith("<") ? "text/html" : "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
