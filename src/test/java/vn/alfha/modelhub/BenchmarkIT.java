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
            stub.createContext("/", exchange -> {
                calls.incrementAndGet();
                boolean cloud = exchange.getRequestURI().getPath().equals("/chat");
                int status = cloud ? 500 : 200;
                String body = cloud ? "private-error-body" : "{\"response\":\"42\",\"done\":true}";
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
            var mapper = new ObjectMapper();
            try {
                try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run(args);
                     var client = HttpClient.newHttpClient()) {
                    String base = base(context);
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
                try (var restarted = new SpringApplicationBuilder(ModelHubApplication.class).run(args);
                     var client = HttpClient.newHttpClient()) {
                    var retrieved = get(client, base(restarted) + reportPath);
                    assertEquals(200, retrieved.statusCode());
                    assertEquals(mapper.readTree(reportJson), mapper.readTree(retrieved.body()));
                    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE model='legacy-model'", Long.class));
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
}
