package vn.alfha.modelhub;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

class GenerationEndpointTest {
    @Test void localIntegrationValidatesInventoryAndKeepsFailuresAndRoutingMetadata() throws Exception {
        var mapper = new ObjectMapper();
        var payload = new AtomicReference<JsonNode>();
        var inventory = new AtomicReference<>("""
                {"models":[{"name":"qwen3:4b"},{"name":"proxied","remote_model":"upstream"}]}
                """);
        var calls = new AtomicInteger();
        var releaseTimeout = new CountDownLatch(1);
        String output = "{\"tool\":\"docker\",\"arguments\":{}}";
        var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/tags", exchange -> send(exchange, 200, inventory.get()));
        stub.createContext("/api/generate", exchange -> {
            calls.incrementAndGet();
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            payload.set(request);
            if (request.path("prompt").asString().equals("slow")) {
                try {
                    releaseTimeout.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
                return;
            }
            send(exchange, 200, mapper.writeValueAsString(Map.of("response", output, "done", true)));
        });
        stub.start();
        try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run("--server.port=0",
                "--modelhub.ollama.base-url=http://127.0.0.1:" + stub.getAddress().getPort(),
                "--modelhub.ollama.model=qwen3:4b", "--modelhub.ollama.secondary-model=proxied",
                "--modelhub.ollama.timeout=1s", "--modelhub.openrouter.enabled=false",
                "--modelhub.routing.preview.enabled=true", "--modelhub.routing.generation.enabled=true");
             var client = HttpClient.newHttpClient()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String explicitPath = "/api/generate/local";
            String routedPath = "/api/routing/generate-local";
            String explicit = """
                    {"model":"qwen3:4b","prompt":"test"}
                    """;
            String routed = """
                    {"profile":"basic","prompt":"test"}
                    """;
            assertEquals("127.0.0.1", context.getEnvironment().getProperty("server.address"));
            assertError(mapper, post(client, port, explicitPath, "{}"), 400, "INVALID_INPUT");
            assertError(mapper, post(client, port, routedPath, routed.replace("basic", "other")), 400, "INVALID_INPUT");
            assertError(mapper, post(client, port, explicitPath, explicit.replace("qwen3:4b", "openrouter/free")),
                    400, "UNKNOWN_MODEL");
            assertEquals(0, calls.get());

            var success = post(client, port, explicitPath, explicit);
            assertEquals(200, success.statusCode(), success.body());
            var first = mapper.readTree(success.body());
            UUID firstId = assertSuccess(first, output);
            assertEquals(mapper.readTree("""
                    {"model":"qwen3:4b","prompt":"test","stream":false}
                    """), payload.get());

            var fallback = post(client, port, routedPath, routed);
            assertEquals(200, fallback.statusCode(), fallback.body());
            var routedResult = mapper.readTree(fallback.body());
            var decision = routedResult.path("decision");
            assertEquals("FALLBACK_LOCAL", decision.path("outcome").asString());
            assertEquals("CUTOFF_REQUIRED", decision.path("reason").asString());
            assertEquals("qwen3:4b", decision.path("selectedModel").asString());
            assertNotEquals(firstId, assertSuccess(routedResult.path("generation"), output));

            var proxy = post(client, port, explicitPath, explicit.replace("qwen3:4b", "proxied"));
            assertEquals(503, proxy.statusCode(), proxy.body());
            assertEquals("CONFIGURATION_ERROR", mapper.readTree(proxy.body()).path("error").path("code").asString());
            inventory.set("{\"models\":[]}");
            var missing = post(client, port, routedPath, routed);
            assertEquals(503, missing.statusCode(), missing.body());
            assertEquals("CONFIGURATION_ERROR", mapper.readTree(missing.body())
                    .path("generation").path("error").path("code").asString());
            assertEquals(2, calls.get());

            inventory.set("{\"models\":[{\"name\":\"qwen3:4b\"}]}");
            var timeout = post(client, port, explicitPath, explicit.replace("test", "slow"));
            assertEquals(504, timeout.statusCode(), timeout.body());
            var failed = mapper.readTree(timeout.body());
            assertEquals("TIMEOUT", failed.path("error").path("code").asString());
            assertEquals("FAILED", failed.path("status").asString());
            assertTrue(failed.path("response").isNull());
            assertNotEquals(firstId, UUID.fromString(failed.path("requestId").asString()));
            assertEquals(3, calls.get());
        } finally {
            releaseTimeout.countDown();
            stub.stop(0);
        }
    }

    private void assertError(ObjectMapper mapper, HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, mapper.readTree(response.body()).path("code").asString());
    }

    private UUID assertSuccess(JsonNode execution, String output) {
        assertEquals("SUCCESS", execution.path("status").asString());
        assertEquals("qwen3:4b", execution.path("model").asString());
        assertEquals(output, execution.path("response").asString());
        assertTrue(execution.path("durationMs").isIntegralNumber());
        assertTrue(execution.path("durationMs").asLong() >= 0);
        assertTrue(execution.path("error").isNull());
        return UUID.fromString(execution.path("requestId").asString());
    }

    @Test void validatesRequestsAndMapsProviderResultsOverHttp() throws Exception {
        var stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/api/generate", exchange -> {
            var request = new ObjectMapper().readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean fail = request.path("prompt").asString().equals("fail");
            byte[] bytes = (fail ? "sensitive upstream details" : "{\"response\":\"Hello\",\"done\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run("--server.port=0",
                "--modelhub.ollama.base-url=http://localhost:" + stub.getAddress().getPort(),
                "--modelhub.ollama.model=qwen3:4b");
             var client = HttpClient.newHttpClient()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            for (var sample : new String[][] {
                    {"{}", "400", "INVALID_INPUT"},
                    {"{", "400", "INVALID_INPUT"},
                    {"{\"model\":\"unknown\",\"prompt\":\"Hi\"}", "400", "UNKNOWN_MODEL"},
                    {"{\"model\":\"qwen3:4b\",\"prompt\":\"Hi\"}", "200", "SUCCESS"},
                    {"{\"model\":\"qwen3:4b\",\"prompt\":\"fail\"}", "502", "PROVIDER_ERROR"}}) {
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/generate"))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(sample[0])).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(Integer.parseInt(sample[1]), response.statusCode(), response.body());
                assertTrue(response.body().contains(sample[2]));
                assertFalse(response.body().contains("sensitive"));
                if (response.statusCode() == 200) assertTrue(response.body().contains("Hello"));
            }
        } finally { stub.stop(0); }
    }

    @Test void validatesOptInJsonAndKeepsRawOutputAndProviderErrorsSeparate() throws Exception {
        var mapper = new ObjectMapper();
        var payload = new AtomicReference<JsonNode>();
        var calls = new AtomicInteger();
        var checks = new AtomicInteger();
        String valid = " {\"label\":\"unverified value\"}\n";
        var outputs = Map.of("valid", valid, "malformed", "{", "fenced", "```json\n" + valid + "```",
                "missing", "{}", "duplicate", "{\"label\":\"a\",\"label\":\"b\"}",
                "wrong-type", "{\"label\":null}", "extra", "{\"label\":\"a\",\"extra\":\"b\"}",
                "trailing", "{\"label\":\"a\"} {}", "array", "[]");
        var stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/api/tags", exchange -> {
            checks.incrementAndGet();
            send(exchange, 200, """
                    {"models":[{"name":"qwen3:4b"},{"name":"proxied","remote_model":"upstream"}]}
                    """);
        });
        stub.createContext("/api/generate", exchange -> {
            calls.incrementAndGet();
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            payload.set(request);
            String prompt = request.path("prompt").asString();
            if (prompt.equals("provider-failure")) send(exchange, 500, "private upstream details");
            else if (prompt.equals("bad-envelope")) send(exchange, 200, "{\"done\":false}");
            else send(exchange, 200, mapper.writeValueAsString(Map.of("response", outputs.get(prompt), "done", true)));
        });
        stub.start();
        try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run("--server.port=0",
                "--modelhub.ollama.base-url=http://localhost:" + stub.getAddress().getPort(),
                "--modelhub.ollama.model=qwen3:4b", "--modelhub.ollama.secondary-model=proxied",
                "--modelhub.openrouter.enabled=true", "--modelhub.openrouter.model=openrouter/free");
             var client = HttpClient.newHttpClient()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String local = "{\"model\":\"qwen3:4b\",\"prompt\":\"valid\",\"requiredStringFields\":[\"label\"]}";
            for (String invalid : new String[] { "[]", "[\"label\",\"label\"]", "[true]", "[null]", "[\"bad name\"]" }) {
                var result = post(client, port, "/api/generate/json", local.replace("[\"label\"]", invalid));
                assertEquals(400, result.statusCode(), result.body());
            }
            var cloud = post(client, port, "/api/generate/json", local.replace("qwen3:4b", "openrouter/free"));
            assertEquals(400, cloud.statusCode(), cloud.body());
            assertEquals(0, checks.get());
            var proxied = post(client, port, "/api/generate/json", local.replace("qwen3:4b", "proxied"));
            assertEquals(503, proxied.statusCode(), proxied.body());
            assertEquals("CONFIGURATION_ERROR", mapper.readTree(proxied.body()).path("error").path("code").asString());
            assertEquals(0, calls.get());

            for (var sample : outputs.entrySet()) {
                var result = post(client, port, "/api/generate/json", local.replace("\"valid\"", "\"" + sample.getKey() + "\""));
                var execution = mapper.readTree(result.body());
                boolean passed = sample.getKey().equals("valid");
                assertEquals(passed ? 200 : 502, result.statusCode(), result.body());
                assertEquals(sample.getValue(), execution.path("response").asString());
                assertEquals(passed ? "SUCCESS" : "FAILED", execution.path("status").asString());
                if (!passed) assertEquals("OUTPUT_FORMAT_ERROR", execution.path("error").path("code").asString());
            }
            assertEquals(mapper.readTree("""
                    {"type":"object","properties":{"label":{"type":"string"}},"required":["label"],"additionalProperties":false}
                    """), payload.get().path("format"));
            for (var failure : Map.of("provider-failure", "PROVIDER_ERROR", "bad-envelope", "INVALID_PROVIDER_RESPONSE").entrySet()) {
                var result = post(client, port, "/api/generate/json", local.replace("\"valid\"", "\"" + failure.getKey() + "\""));
                var execution = mapper.readTree(result.body());
                assertEquals(502, result.statusCode(), result.body());
                assertEquals(failure.getValue(), execution.path("error").path("code").asString());
                assertTrue(execution.path("response").isNull());
                assertFalse(result.body().contains("private upstream details"));
            }
            int inventoryRequests = checks.get();
            var standard = post(client, port, "/api/generate", local.replace(",\"requiredStringFields\":[\"label\"]", ""));
            assertEquals(200, standard.statusCode(), standard.body());
            assertFalse(payload.get().has("format"));
            assertEquals(inventoryRequests, checks.get());
        } finally { stub.stop(0); }
    }

    private HttpResponse<String> post(HttpClient client, int port, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
