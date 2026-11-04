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
            stub.createContext("/", exchange -> {
                calls.incrementAndGet();
                boolean cloud = exchange.getRequestURI().getPath().equals("/chat");
                byte[] bytes = (cloud ? "private-error-body" : "{\"response\":\"42\",\"done\":true}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(cloud ? 500 : 200, bytes.length);
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
