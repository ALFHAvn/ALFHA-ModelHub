package vn.alfha.modelhub;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.provider.*;
import vn.alfha.modelhub.service.ModelRegistry;
import static org.junit.jupiter.api.Assertions.*;

class ModelSelectionTest {
    @Test void routesBothModelsAndListsOnlyPublicConfiguration() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/generate", exchange -> {
            byte[] bytes = "{\"response\":\"local\",\"done\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/chat", exchange -> {
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"cloud\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String origin = "http://localhost:" + server.getAddress().getPort();
        try (var context = new SpringApplicationBuilder(ModelHubApplication.class).run("--server.port=0",
                "--modelhub.ollama.base-url=" + origin, "--modelhub.ollama.model=qwen3:4b",
                "--modelhub.openrouter.enabled=true", "--modelhub.openrouter.model=vendor/model:free",
                "--modelhub.openrouter.api-key=fake-secret", "--modelhub.openrouter.endpoint=" + origin + "/chat");
             var client = HttpClient.newHttpClient()) {
            String base = "http://localhost:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var listing = client.send(HttpRequest.newBuilder(URI.create(base + "/api/models")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listing.statusCode());
            var models = new ObjectMapper().readTree(listing.body());
            assertEquals(2, models.size());
            assertEquals(2, models.path(0).size());
            assertEquals(2, models.path(1).size());
            assertFalse(listing.body().contains("fake-secret"));
            for (var pair : new String[][] {{"qwen3:4b", "local"}, {"vendor/model:free", "cloud"}}) {
                var request = HttpRequest.newBuilder(URI.create(base + "/api/generate"))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + pair[0] + "\",\"prompt\":\"Hi\"}")).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(pair[1], new ObjectMapper().readTree(response.body()).path("response").asString());
            }
        } finally { server.stop(0); }
    }
    @Test void disabledCloudIsNotRegisteredAndPaidConfigurationIsRejected() {
        var mapper = new ObjectMapper();
        var ollama = new OllamaProvider(mapper, URI.create("http://localhost:1"), Duration.ofSeconds(1));
        var cloud = new OpenRouterProvider(mapper, URI.create("http://localhost:1/chat"), Duration.ofSeconds(1), "");
        var registry = new ModelRegistry(ollama, cloud, "qwen3:4b", false, "");
        assertEquals(1, registry.models().size());
        assertEquals("ollama", registry.models().getFirst().provider());
        assertThrows(IllegalArgumentException.class, () -> new ModelRegistry(ollama, cloud, "qwen3:4b", true, "vendor/paid"));
        assertThrows(IllegalArgumentException.class, () -> new ModelRegistry(ollama, cloud, "qwen3:4b", true, ""));
    }
}
