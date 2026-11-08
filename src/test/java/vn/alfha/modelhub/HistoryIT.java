package vn.alfha.modelhub;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class HistoryIT {
    @Test void migratesPersistsGenerationAndComparisonAndPaginatesRealPostgres() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            var stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            stub.createContext("/", exchange -> {
                boolean cloud = exchange.getRequestURI().getPath().equals("/chat");
                boolean reviewer = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes())
                        .path("model").asString().equals("local-reviewer");
                boolean fail = cloud || reviewer;
                byte[] bytes = (fail ? "private-upstream-secret" : "{\"response\":\"Hello\",\"done\":true}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            stub.start();
            String origin = "http://localhost:" + stub.getAddress().getPort();
            String[] args = {"--server.port=0", "--spring.autoconfigure.exclude=", "--modelhub.history.enabled=true",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(), "--modelhub.ollama.model=qwen3:4b",
                    "--modelhub.ollama.base-url=" + origin, "--modelhub.ollama.secondary-model=local-reviewer", "--modelhub.openrouter.enabled=true",
                    "--modelhub.openrouter.model=vendor/model:free", "--modelhub.openrouter.api-key=test-secret-key",
                    "--modelhub.openrouter.endpoint=" + origin + "/chat"};
            String workflowPayload = "{\"proposerModel\":\"qwen3:4b\",\"reviewerModel\":\"local-reviewer\",\"prompt\":\"Workflow task\"}";
            try {
                try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run(args);
                     var client = HttpClient.newHttpClient()) {
                    String base = "http://localhost:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                    var mapper = new ObjectMapper();
                    var generated = client.send(post(base + "/api/generate", "{\"model\":\"qwen3:4b\",\"prompt\":\"Hi ' quoted\"}"), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, generated.statusCode());
                    String id = mapper.readTree(generated.body()).path("requestId").asString();
                    assertFalse(id.isBlank());
                    var comparison = client.send(post(base + "/api/compare", "{\"models\":[\"qwen3:4b\",\"vendor/model:free\"],\"prompt\":\"Compare\"}"), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, comparison.statusCode());
                    for (int page = 0; page < 3; page++) {
                        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/runs?page=" + page + "&size=1")).GET().build(), HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode());
                        var body = mapper.readTree(response.body());
                        assertEquals(3, body.path("totalElements").asInt());
                        assertEquals(3, body.path("totalPages").asInt());
                        assertEquals(1, body.path("content").size());
                        assertFalse(response.body().contains("test-secret-key"));
                        assertFalse(response.body().contains("private-upstream-secret"));
                        if (page == 2) {
                            var run = body.path("content").path(0);
                            assertEquals(id, run.path("requestId").asString());
                            assertEquals("Hi ' quoted", run.path("prompt").asString());
                            assertEquals("Hello", run.path("response").asString());
                            assertEquals("SUCCESS", run.path("status").asString());
                            assertFalse(run.path("timestamp").asString().isBlank());
                        }
                    }
                    var jdbc = context.getBean(JdbcTemplate.class);
                    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM execution_runs WHERE status='FAILED' AND error_code='PROVIDER_ERROR'", Long.class));
                    for (String query : new String[] {"page=-1", "size=0", "size=101", "page=abc"}) {
                        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/runs?" + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
                        assertEquals(400, response.statusCode(), response.body());
                    }
                    var empty = client.send(HttpRequest.newBuilder(URI.create(base + "/api/runs?page=10&size=20")).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(0, mapper.readTree(empty.body()).path("content").size());
                    var workflow = client.send(post(base + "/api/workflows/proposal-review", workflowPayload), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, workflow.statusCode(), workflow.body());
                    var steps = mapper.readTree(workflow.body());
                    assertEquals("SUCCESS", steps.path("proposal").path("status").asString());
                    assertEquals("PROVIDER_ERROR", steps.path("review").path("error").path("code").asString());
                    var page = client.send(HttpRequest.newBuilder(URI.create(base + "/api/runs?size=2")).GET().build(), HttpResponse.BodyHandlers.ofString());
                    var history = mapper.readTree(page.body()).path("content");
                    for (var role : new String[] {"proposal", "review"}) {
                        var step = steps.path(role);
                        var saved = history.valueStream().filter(row -> row.path("requestId").equals(step.path("requestId")))
                                .findFirst().orElseThrow();
                        for (var field : new String[] {"model", "response", "durationMs", "status", "error"})
                            assertEquals(step.path(field), saved.path(field), field);
                        assertTrue(saved.path("prompt").asString().contains("Workflow task"));
                        if (role.equals("review")) assertTrue(saved.path("prompt").asString().contains("Hello"));
                    }
                    // Simulate storage failure only inside the disposable test database.
                    jdbc.execute("ALTER TABLE execution_runs RENAME TO temporarily_unavailable_runs");
                    var failedSave = client.send(post(base + "/api/generate", "{\"model\":\"qwen3:4b\",\"prompt\":\"Hi\"}"), HttpResponse.BodyHandlers.ofString());
                    assertEquals(503, failedSave.statusCode());
                    assertEquals("Hello", mapper.readTree(failedSave.body()).path("response").asString());
                    assertTrue(failedSave.body().contains("HISTORY_UNAVAILABLE"));
                    var workflowFailedSave = client.send(post(base + "/api/workflows/proposal-review", workflowPayload), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, workflowFailedSave.statusCode(), workflowFailedSave.body());
                    var unavailable = mapper.readTree(workflowFailedSave.body());
                    assertEquals("HISTORY_UNAVAILABLE", unavailable.path("proposal").path("error").path("code").asString());
                    assertEquals("Hello", unavailable.path("proposal").path("response").asString());
                    assertEquals("SKIPPED", unavailable.path("review").path("status").asString());
                    jdbc.execute("ALTER TABLE temporarily_unavailable_runs RENAME TO execution_runs");
                }
                // Restart verifies durable history and that Flyway does not recreate existing rows.
                try (var restarted = new SpringApplicationBuilder(ModelHubApplication.class).run(args)) {
                    assertEquals(5L, restarted.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM execution_runs", Long.class));
                }
            } finally { stub.stop(0); }
        }
    }
    private HttpRequest post(String url, String payload) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    }
}
