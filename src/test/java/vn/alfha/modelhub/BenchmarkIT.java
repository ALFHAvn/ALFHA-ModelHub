package vn.alfha.modelhub;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkIT {
    @Test void upgradesV1AndPersistsBenchmarkSnapshotAcrossRestart() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("1").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            jdbc.update("""
                    INSERT INTO execution_runs VALUES
                    (?, 'legacy-model', 'legacy prompt', 'legacy answer', 1, 'SUCCESS', null, null, now())
                    """, java.util.UUID.randomUUID());
            var stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var scheduledCloudCalls = new java.util.concurrent.atomic.AtomicInteger(-1);
            var cloudStarts = new java.util.concurrent.CopyOnWriteArrayList<Long>();
            var localAnswer = new java.util.concurrent.atomic.AtomicReference<>("42");
            var localStatus = new java.util.concurrent.atomic.AtomicInteger(200);
            var mapper = new ObjectMapper();
            stub.createContext("/", exchange -> {
                calls.incrementAndGet();
                boolean cloud = exchange.getRequestURI().getPath().equals("/chat");
                int status = cloud ? 500 : localStatus.get();
                String body = cloud ? "private-error-body" : mapper.writeValueAsString(
                        java.util.Map.of("response", localAnswer.get(), "done", true));
                if (cloud && scheduledCloudCalls.get() >= 0) {
                    cloudStarts.add(System.nanoTime());
                    int attempt = scheduledCloudCalls.getAndIncrement();
                    status = attempt == 1 ? 200 : 429;
                    if (status == 429) exchange.getResponseHeaders().set("Retry-After", attempt == 0 ? "1" : "31");
                    else body = "{\"choices\":[{\"message\":{\"content\":\"42\"},\"finish_reason\":\"stop\"}]}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            stub.start();
            String origin = "http://localhost:" + stub.getAddress().getPort();
            String[] args = {"--server.port=0", "--spring.autoconfigure.exclude=", "--modelhub.history.enabled=true",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(), "--modelhub.ollama.model=qwen3:4b",
                    "--modelhub.ollama.base-url=" + origin, "--modelhub.openrouter.enabled=true",
                    "--modelhub.openrouter.model=vendor/model:free", "--modelhub.openrouter.api-key=fake-secret",
                    "--modelhub.openrouter.endpoint=" + origin + "/chat"};
            String reportJson;
            String reportPath;
            try {
                try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run(args);
                     var client = HttpClient.newHttpClient()) {
                    String base = base(context);
                    assertEquals(404, get(client, base + "/api/routing/preview?profile=basic").statusCode());
                    assertEquals(404, routed(client, base, "basic", "test").statusCode());
                    assertEquals(503, coding(client, base).statusCode());
                    assertEquals(0, calls.get());
                    var dataset = get(client, base + "/api/benchmarks/dataset");
                    assertEquals(200, dataset.statusCode());
                    assertEquals(10, mapper.readTree(dataset.body()).path("tasks").size());
                    assertEquals("1", mapper.readTree(dataset.body()).path("version").asString());
                    assertEquals("2", mapper.readTree(get(client, base + "/api/benchmarks/dataset?version=2").body()).path("version").asString());
                    assertEquals(400, get(client, base + "/api/benchmarks/dataset?version=unknown").statusCode());
                    var created = client.send(HttpRequest.newBuilder(URI.create(base + "/api/benchmarks"))
                            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"datasetId\":\"basics\",\"datasetVersion\":\"2\",\"models\":[\"qwen3:4b\",\"vendor/model:free\"]}")).build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(201, created.statusCode(), created.body());
                    reportJson = created.body();
                    reportPath = created.headers().firstValue("Location").orElseThrow();
                    var report = mapper.readTree(reportJson);
                    assertEquals("2", report.path("dataset").path("version").asString());
                    assertEquals(20, report.path("results").size());
                    assertEquals(1, report.path("summaries").path(0).path("passed").asInt());
                    assertEquals(10, report.path("summaries").path(1).path("failed").asInt());
                    assertTrue(report.path("summaries").path(1).path("averageSuccessfulDurationMs").isNull());
                    assertEquals(64, report.path("datasetSha256").asString().length());
                    assertFalse(reportJson.contains("fake-secret"));
                    assertFalse(reportJson.contains("private-error-body"));
                    assertEquals(report, mapper.readTree(get(client, base + reportPath).body()));
                    assertEquals(21L, jdbc.queryForObject("SELECT count(*) FROM execution_runs", Long.class));
                    assertEquals(20, calls.get());
                    for (var result : report.path("results")) {
                        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE request_id = ?::uuid", Long.class,
                                result.path("execution").path("requestId").asString()));
                    }
                    assertEquals(400, get(client, base + "/api/benchmarks/not-a-uuid").statusCode());
                    assertEquals(404, get(client, base + "/api/benchmarks/" + java.util.UUID.randomUUID()).statusCode());
                    scheduledCloudCalls.set(0);
                    var scheduled = client.send(HttpRequest.newBuilder(URI.create(base + "/api/benchmarks"))
                            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"datasetId":"basics","datasetVersion":"2","models":["qwen3:4b","vendor/model:free"],
                                     "schedule":{"minIntervalMs":250,"maxRetries":1}}
                                    """)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(201, scheduled.statusCode(), scheduled.body());
                    var paced = mapper.readTree(scheduled.body());
                    assertEquals(8, paced.path("skippedCloudTasks").size());
                    assertEquals(10, paced.path("summaries").path(0).path("attempted").asInt());
                    assertEquals(2, paced.path("summaries").path(1).path("attempted").asInt());
                    var retry = paced.path("results").path(1);
                    assertEquals("PASS", retry.path("score").asString());
                    assertEquals(2, retry.path("attempts").size());
                    var initial = retry.path("attempts").path(0);
                    assertEquals(1000, initial.path("error").path("retryAfterMs").asLong());
                    assertEquals(1000L, jdbc.queryForObject("SELECT retry_after_ms FROM execution_runs WHERE request_id = ?::uuid",
                            Long.class, initial.path("requestId").asString()));
                    assertEquals(3, scheduledCloudCalls.get());
                    assertTrue(cloudStarts.get(1) - cloudStarts.get(0) >= Duration.ofSeconds(1).toNanos());
                    // Starts are paced on the client, so allow a small difference in loopback transit.
                    assertTrue(cloudStarts.get(2) - cloudStarts.get(1) >= Duration.ofMillis(225).toNanos());
                    assertEquals(paced, mapper.readTree(get(client, base + scheduled.headers().firstValue("Location").orElseThrow()).body()));

                    var engineeringDataset = get(client, base + "/api/benchmarks/dataset?datasetId=software-engineering&version=2");
                    assertEquals(200, engineeringDataset.statusCode());
                    assertEquals(400, get(client, base + "/api/benchmarks/dataset?datasetId=software-engineering&version=3").statusCode());
                    scheduledCloudCalls.set(-1);
                    var engineering = client.send(HttpRequest.newBuilder(URI.create(base + "/api/benchmarks"))
                            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"datasetId":"software-engineering","datasetVersion":"2","models":["qwen3:4b","vendor/model:free"]}
                                    """)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(201, engineering.statusCode(), engineering.body());
                    var engineeringReport = mapper.readTree(engineering.body());
                    assertEquals(mapper.readTree(engineeringDataset.body()), engineeringReport.path("dataset"));
                    assertEquals(48, engineeringReport.path("results").size());
                    assertEquals("PASS", engineeringReport.path("results").path(0).path("score").asString());
                    assertEquals("NOT_SCORED", engineeringReport.path("results").path(1).path("score").asString());
                    assertEquals("FAIL", engineeringReport.path("results").path(2).path("score").asString());
                    assertEquals(23, engineeringReport.path("summaries").path(0).path("scoringFailures").asInt());
                    assertEquals(24, engineeringReport.path("summaries").path(1).path("failed").asInt());
                    assertEquals(engineeringReport, mapper.readTree(get(client,
                            base + engineering.headers().firstValue("Location").orElseThrow()).body()));

                    // Reopen the first report in its pre-scheduling JSON shape.
                    var legacy = (tools.jackson.databind.node.ObjectNode) mapper.readTree(reportJson);
                    legacy.remove("schedule");
                    legacy.remove("skippedCloudTasks");
                    for (var result : legacy.path("results")) {
                        ((tools.jackson.databind.node.ObjectNode) result).remove("attempts");
                    }
                    jdbc.update("UPDATE benchmark_reports SET report = ?::jsonb WHERE id = ?::uuid",
                            mapper.writeValueAsString(legacy), legacy.path("id").asString());
                }
                boolean realCodingRunner = Boolean.getBoolean("modelhub.test.coding-docker");
                String[] restartArgs = java.util.Arrays.copyOf(args, args.length + 5);
                restartArgs[args.length] = "--modelhub.coding.runner=" + (realCodingRunner
                        ? "scripts/run-java-coding-pilot.ps1" : "target/nonexistent-coding-runner.ps1");
                String approvedId = mapper.readTree(reportJson).path("id").asString();
                String missingId = java.util.UUID.randomUUID().toString();
                restartArgs[args.length + 1] = "--modelhub.routing.preview.enabled=true";
                restartArgs[args.length + 2] = "--modelhub.routing.preview.cutoff=2020-01-01T00:00:00Z";
                restartArgs[args.length + 3] = "--modelhub.routing.preview.report-ids=" + approvedId + "," + missingId;
                restartArgs[args.length + 4] = "--modelhub.routing.generation.enabled=true";
                try (var restarted = new SpringApplicationBuilder(ModelHubApplication.class).run(restartArgs);
                     var client = HttpClient.newHttpClient()) {
                    var retrieved = get(client, base(restarted) + reportPath);
                    assertEquals(200, retrieved.statusCode());
                    assertEquals(mapper.readTree(reportJson), mapper.readTree(retrieved.body()));
                    int callsBeforePreview = calls.get();
                    String previewUrl = base(restarted) + "/api/routing/preview?profile=";
                    var preview = get(client, previewUrl + "basic");
                    assertEquals(200, preview.statusCode(), preview.body());
                    var decision = mapper.readTree(preview.body());
                    assertEquals("FALLBACK_LOCAL", decision.path("outcome").asString());
                    assertEquals("qwen3:4b", decision.path("selectedModel").asString());
                    assertEquals(1, decision.path("reportIds").size());
                    assertEquals(approvedId, decision.path("reportIds").path(0).asString());
                    assertEquals(missingId, decision.path("exclusions").path(0).path("reportId").asString());
                    assertEquals("REPORT_NOT_FOUND", decision.path("exclusions").path(0).path("reason").asString());
                    assertEquals(10, decision.path("candidates").path(1).path("metrics").path("providerFailures").asInt());
                    assertFalse(preview.body().contains("private-error-body"));
                    assertFalse(preview.body().contains("fake-secret"));
                    assertEquals(400, get(client, previewUrl + "unknown").statusCode());
                    assertEquals(400, get(client, base(restarted) + "/api/routing/preview").statusCode());
                    jdbc.update("UPDATE benchmark_reports SET report = '[]'::jsonb WHERE id = ?::uuid", approvedId);
                    try {
                        var unreadable = get(client, previewUrl + "basic");
                        assertEquals(200, unreadable.statusCode(), unreadable.body());
                        var fallback = mapper.readTree(unreadable.body());
                        assertEquals("FALLBACK_LOCAL", fallback.path("outcome").asString());
                        assertEquals("EVIDENCE_UNAVAILABLE", fallback.path("reason").asString());
                    } finally {
                        jdbc.update("UPDATE benchmark_reports SET report = ?::jsonb WHERE id = ?::uuid", reportJson, approvedId);
                    }
                    assertEquals(callsBeforePreview, calls.get());
                    String routedBase = base(restarted);
                    assertEquals(400, routed(client, routedBase, "basic", " ").statusCode());
                    assertEquals(400, routed(client, routedBase, "unknown", "test").statusCode());
                    assertEquals(callsBeforePreview, calls.get());
                    var routed = routed(client, routedBase, "basic", "test");
                    assertEquals(200, routed.statusCode(), routed.body());
                    var routedResult = mapper.readTree(routed.body());
                    assertEquals("FALLBACK_LOCAL", routedResult.path("decision").path("outcome").asString());
                    assertEquals("CLOUD_DISPATCH_DISABLED", routedResult.path("decision")
                            .path("candidates").path(1).path("reasons").path(0).asString());
                    assertEquals("qwen3:4b", routedResult.path("generation").path("model").asString());
                    assertEquals("42", routedResult.path("generation").path("response").asString());
                    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE request_id = ?::uuid",
                            Long.class, routedResult.path("generation").path("requestId").asString()));
                    assertEquals(callsBeforePreview + 1, calls.get());
                    localStatus.set(500);
                    try {
                        var failed = routed(client, routedBase, "basic", "test");
                        assertEquals(502, failed.statusCode(), failed.body());
                        var failedResult = mapper.readTree(failed.body());
                        assertEquals("qwen3:4b", failedResult.path("decision").path("selectedModel").asString());
                        assertEquals("FAILED", failedResult.path("generation").path("status").asString());
                        assertEquals(callsBeforePreview + 2, calls.get());
                    } finally {
                        localStatus.set(200);
                    }
                    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE model='legacy-model'", Long.class));
                    localAnswer.set("""
                            public class Main {
                                public static void main(String[] args) {
                                    int[] values = java.util.Arrays.stream(args).mapToInt(Integer::parseInt).sorted().toArray();
                                    System.out.println(values[1]);
                                }
                            }
                            """);
                    var codingResponse = coding(client, base(restarted));
                    assertEquals(201, codingResponse.statusCode(), codingResponse.body());
                    var codingReport = mapper.readTree(codingResponse.body());
                    assertEquals("java-coding", codingReport.path("dataset").path("id").asString());
                    assertEquals("1", codingReport.path("dataset").path("version").asString());
                    assertEquals("java-coding-v1", codingReport.path("scoringVersion").asString());
                    var local = codingReport.path("results").path(0);
                    assertEquals("median-of-three", local.path("taskId").asString());
                    assertEquals("qwen3:4b", local.path("execution").path("model").asString());
                    assertEquals("SUCCESS", local.path("execution").path("status").asString());
                    assertEquals(realCodingRunner ? "PASS" : "NOT_SCORED", local.path("score").asString());
                    assertEquals(realCodingRunner ? "PASS" : "INFRASTRUCTURE_ERROR",
                            local.path("codingEvaluation").path("outcome").asString());
                    assertEquals(realCodingRunner ? 6 : 0, local.path("codingEvaluation").path("cases").size());
                    var cloud = codingReport.path("results").path(1);
                    assertEquals("NOT_SCORED", cloud.path("score").asString());
                    assertFalse(cloud.has("codingEvaluation"));
                    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE request_id = ?::uuid",
                            Long.class, local.path("execution").path("requestId").asString()));
                    assertEquals(codingReport, mapper.readTree(get(client,
                            base(restarted) + codingResponse.headers().firstValue("Location").orElseThrow()).body()));
                    if (realCodingRunner) {
                        localAnswer.set("public class Main { invalid source }");
                        var invalid = coding(client, base(restarted));
                        assertEquals(201, invalid.statusCode(), invalid.body());
                        var invalidReport = mapper.readTree(invalid.body());
                        var result = invalidReport.path("results").path(0);
                        assertEquals("FAIL", result.path("score").asString());
                        assertEquals("COMPILATION_ERROR", result.path("codingEvaluation").path("outcome").asString());
                        assertEquals(invalidReport, mapper.readTree(get(client,
                                base(restarted) + invalid.headers().firstValue("Location").orElseThrow()).body()));
                    }
                }
            } finally { stub.stop(0); }
        }
    }
    private String base(org.springframework.context.ConfigurableApplicationContext context) {
        return "http://localhost:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }
    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> coding(HttpClient client, String base) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/benchmarks"))
                .timeout(Duration.ofSeconds(110)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"datasetId":"java-coding","datasetVersion":"1","models":["qwen3:4b","vendor/model:free"]}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> routed(HttpClient client, String base, String profile, String prompt) throws Exception {
        String body = new ObjectMapper().writeValueAsString(java.util.Map.of("profile", profile, "prompt", prompt));
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/routing/generate"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
