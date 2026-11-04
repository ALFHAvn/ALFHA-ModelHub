package vn.alfha.modelhub;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonEndpointTest {
    @Test void returnsIndependentResultsAndRejectsInvalidRequestsOverHttp() throws Exception {
        var stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/", exchange -> {
            boolean cloud = exchange.getRequestURI().getPath().equals("/chat");
            String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean fail = cloud && input.contains("fail");
            byte[] bytes = (fail ? "private upstream details" : cloud
                    ? "{\"choices\":[{\"message\":{\"content\":\"cloud\"},\"finish_reason\":\"stop\"}]}"
                    : "{\"response\":\"local\",\"done\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        String origin = "http://localhost:" + stub.getAddress().getPort();
        try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run("--server.port=0",
                "--modelhub.ollama.model=qwen3:4b", "--modelhub.ollama.base-url=" + origin,
                "--modelhub.openrouter.enabled=true", "--modelhub.openrouter.model=vendor/model:free",
                "--modelhub.openrouter.api-key=fake-key", "--modelhub.openrouter.endpoint=" + origin + "/chat");
             var client = HttpClient.newHttpClient()) {
            String url = "http://localhost:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort() + "/api/compare";
            var mapper = new ObjectMapper();
            for (String prompt : new String[] {"Hi", "fail"}) {
                String payload = "{\"models\":[\"qwen3:4b\",\"vendor/model:free\"],\"prompt\":\"" + prompt + "\"}";
                var response = client.send(request(url, payload), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                var results = mapper.readTree(response.body()).path("results");
                assertEquals(2, results.size());
                assertEquals("qwen3:4b", results.path(0).path("model").asString());
                assertEquals("local", results.path(0).path("response").asString());
                assertEquals("SUCCESS", results.path(0).path("status").asString());
                assertEquals(prompt.equals("fail") ? "FAILED" : "SUCCESS", results.path(1).path("status").asString());
                if (prompt.equals("fail")) {
                    assertTrue(results.path(1).path("response").isNull());
                    assertEquals("PROVIDER_ERROR", results.path(1).path("error").path("code").asString());
                }
                assertFalse(response.body().contains("private"));
                assertFalse(response.body().contains("fake-key"));
            }
            for (String payload : new String[] {"{", "{}", "{\"models\":[\"qwen3:4b\",\"qwen3:4b\"],\"prompt\":\"Hi\"}",
                    "{\"models\":[\"qwen3:4b\",\"unknown\"],\"prompt\":\"Hi\"}"}) {
                var response = client.send(request(url, payload), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode(), response.body());
                assertTrue(mapper.readTree(response.body()).has("code"));
            }
        } finally { stub.stop(0); }
    }
    private HttpRequest request(String url, String payload) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    }
}
