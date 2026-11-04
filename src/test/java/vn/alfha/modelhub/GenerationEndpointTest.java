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
import static org.junit.jupiter.api.Assertions.*;

class GenerationEndpointTest {
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
}
