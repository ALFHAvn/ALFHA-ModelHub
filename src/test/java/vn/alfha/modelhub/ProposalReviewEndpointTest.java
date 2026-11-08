package vn.alfha.modelhub;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.ProposalReviewRequest;
import static org.junit.jupiter.api.Assertions.*;

class ProposalReviewEndpointTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void executesInOrderAndPreservesPartialResultsOverHttp() throws Exception {
        var calls = new CopyOnWriteArrayList<JsonNode>();
        var stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/api/generate", exchange -> {
            var call = mapper.readTree(exchange.getRequestBody().readAllBytes());
            calls.add(call);
            boolean reviewer = "a-reviewer".equals(call.path("model").asString());
            String prompt = call.path("prompt").asString();
            boolean fail = reviewer ? prompt.contains("fail review") : prompt.equals("fail proposal");
            String output = reviewer ? "Check the edge case" : switch (prompt) {
                case "long proposal" -> "x".repeat(32_000);
                case "empty proposal" -> " ";
                default -> "Initial \"answer\"\nwith code";
            };
            byte[] bytes = (fail ? "private upstream details" : mapper.writeValueAsString(Map.of("response", output, "done", true)))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run("--server.port=0",
                "--modelhub.ollama.model=z-proposer", "--modelhub.ollama.secondary-model=a-reviewer",
                "--modelhub.ollama.base-url=http://localhost:" + stub.getAddress().getPort(),
                "--modelhub.openrouter.enabled=false", "--modelhub.routing.preview.enabled=true");
             var client = HttpClient.newHttpClient()) {
            String base = "http://localhost:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String url = base + "/api/workflows/proposal-review";
            assertEquals("a-reviewer", get(client, base + "/api/models").path(1).path("model").asString());
            assertEquals("z-proposer", get(client, base + "/api/routing/preview?profile=basic").path("selectedModel").asString());

            var success = post(client, url, "z-proposer", "a-reviewer", "original \"task\"\nline two", 200);
            var proposal = success.path("proposal");
            var review = success.path("review");
            assertEquals("SUCCESS", proposal.path("status").asString());
            assertEquals("SUCCESS", review.path("status").asString());
            assertEquals("Check the edge case", review.path("response").asString());
            assertTrue(proposal.path("durationMs").asLong(-1) >= 0);
            assertTrue(review.path("durationMs").asLong(-1) >= 0);
            assertFalse(proposal.path("requestId").asString().isBlank());
            assertNotEquals(proposal.path("requestId").asString(), review.path("requestId").asString());
            assertEquals(List.of("z-proposer", "a-reviewer"), calls.stream().map(call -> call.path("model").asString()).toList());
            assertTrue(calls.stream().noneMatch(call -> call.has("format")));
            String reviewPrompt = calls.get(1).path("prompt").asString();
            var reviewInput = mapper.readTree(reviewPrompt.substring(reviewPrompt.indexOf('{')));
            assertEquals(calls.get(0).path("prompt").asString(), reviewInput.path("task").asString());
            assertEquals(proposal.path("response").asString(), reviewInput.path("proposal").asString());

            var partial = post(client, url, "z-proposer", "a-reviewer", "fail review", 200);
            assertEquals(proposal.path("response"), partial.path("proposal").path("response"));
            assertEquals("FAILED", partial.path("review").path("status").asString());
            assertEquals("PROVIDER_ERROR", partial.path("review").path("error").path("code").asString());
            assertFalse(partial.toString().contains("private upstream details"));

            for (var entry : Map.of("fail proposal", "PROPOSAL_FAILED", "long proposal", "REVIEW_INPUT_TOO_LONG",
                    "empty proposal", "EMPTY_PROPOSAL").entrySet()) {
                int before = calls.size();
                var result = post(client, url, "z-proposer", "a-reviewer", entry.getKey(), 200);
                assertEquals(before + 1, calls.size());
                assertEquals("SKIPPED", result.path("review").path("status").asString());
                assertEquals("a-reviewer", result.path("review").path("model").asString());
                assertTrue(result.path("review").path("requestId").isNull());
                assertEquals(entry.getValue(), result.path("review").path("error").path("code").asString());
                if (entry.getKey().equals("fail proposal")) {
                    assertEquals("FAILED", result.path("proposal").path("status").asString());
                    assertEquals("PROVIDER_ERROR", result.path("proposal").path("error").path("code").asString());
                }
            }

            int before = calls.size();
            assertEquals("UNKNOWN_MODEL", post(client, url, "z-proposer", "unknown", "task", 400).path("code").asString());
            assertEquals("INVALID_INPUT", post(client, url, "z-proposer", "z-proposer", "task", 400).path("code").asString());
            assertEquals("INVALID_INPUT", post(client, url, "z-proposer", "a-reviewer", " ", 400).path("code").asString());
            assertEquals(before, calls.size());
        } finally { stub.stop(0); }
    }

    @Test void validatesStructuredStepsWithoutRewritingTheirResponses() throws Exception {
        var calls = new CopyOnWriteArrayList<JsonNode>();
        String initial = " {\"defectIds\":[\"unverified-id\"]}\n";
        String fenced = "```json\n{\"defectIds\":[]}\n```";
        var malformed = Map.of("fenced", fenced, "missing", "{}", "number", "{\"defectIds\":[1]}",
                "duplicate", "{\"defectIds\":[\"A\",\"A\"]}", "extra", "{\"defectIds\":[],\"note\":\"x\"}");
        var stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/api/tags", exchange -> send(exchange, 200, """
                {"models":[{"name":"z-proposer"},{"name":"a-reviewer"}]}
                """));
        stub.createContext("/api/generate", exchange -> {
            var call = mapper.readTree(exchange.getRequestBody().readAllBytes());
            calls.add(call);
            boolean reviewer = "a-reviewer".equals(call.path("model").asString());
            String prompt = call.path("prompt").asString();
            if (reviewer && prompt.contains("provider failure")) {
                send(exchange, 500, "private upstream details");
                return;
            }
            String output = reviewer ? (prompt.contains("fenced review") ? fenced : "{\"defectIds\":[]}")
                    : malformed.getOrDefault(prompt, initial);
            send(exchange, 200, mapper.writeValueAsString(Map.of("response", output, "done", true)));
        });
        stub.start();
        try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run("--server.port=0",
                "--modelhub.ollama.model=z-proposer", "--modelhub.ollama.secondary-model=a-reviewer",
                "--modelhub.ollama.base-url=http://localhost:" + stub.getAddress().getPort(),
                "--modelhub.openrouter.enabled=true", "--modelhub.openrouter.model=openrouter/free");
             var client = HttpClient.newHttpClient()) {
            String url = "http://localhost:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort()
                    + "/api/workflows/proposal-review/defect-ids";
            assertEquals("INVALID_INPUT", post(client, url, "z-proposer", "openrouter/free", "task", 400).path("code").asString());
            assertTrue(calls.isEmpty());

            var result = post(client, url, "z-proposer", "a-reviewer", "task", 200);
            assertEquals("SUCCESS", result.path("proposal").path("status").asString());
            assertEquals(initial, result.path("proposal").path("response").asString());
            assertEquals("SUCCESS", result.path("review").path("status").asString());
            assertEquals("{\"defectIds\":[]}", result.path("review").path("response").asString());
            var schema = mapper.readTree("""
                    {"type":"object","properties":{"defectIds":{"type":"array","items":{"type":"string"}}},
                     "required":["defectIds"],"additionalProperties":false}
                    """);
            assertEquals(List.of(schema, schema), calls.stream().map(call -> call.path("format")).toList());
            String reviewPrompt = calls.get(1).path("prompt").asString();
            var reviewInput = mapper.readTree(reviewPrompt.substring(reviewPrompt.indexOf('{')));
            assertEquals("task", reviewInput.path("task").asString());
            assertEquals(initial, reviewInput.path("proposal").asString());

            for (var sample : malformed.entrySet()) {
                int before = calls.size();
                var rejected = post(client, url, "z-proposer", "a-reviewer", sample.getKey(), 200);
                assertEquals(sample.getValue(), rejected.path("proposal").path("response").asString());
                assertEquals("OUTPUT_FORMAT_ERROR", rejected.path("proposal").path("error").path("code").asString());
                assertEquals("SKIPPED", rejected.path("review").path("status").asString());
                assertEquals(before + 1, calls.size());
            }
            var rejectedReview = post(client, url, "z-proposer", "a-reviewer", "fenced review", 200);
            assertEquals("SUCCESS", rejectedReview.path("proposal").path("status").asString());
            assertEquals(fenced, rejectedReview.path("review").path("response").asString());
            assertEquals("OUTPUT_FORMAT_ERROR", rejectedReview.path("review").path("error").path("code").asString());
            var failedReview = post(client, url, "z-proposer", "a-reviewer", "provider failure", 200);
            assertEquals("PROVIDER_ERROR", failedReview.path("review").path("error").path("code").asString());
            assertTrue(failedReview.path("review").path("response").isNull());
        } finally { stub.stop(0); }
    }

    private void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private JsonNode post(HttpClient client, String url, String proposer, String reviewer, String task, int expected) throws Exception {
        var payload = mapper.writeValueAsString(new ProposalReviewRequest(proposer, reviewer, task, false));
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private JsonNode get(HttpClient client, String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
}
