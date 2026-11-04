package vn.alfha.modelhub;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthEndpointTest {
    @Test
    void healthEndpointReturnsApplicationStatusOverHttp() throws Exception {
        try (var context = new SpringApplicationBuilder(ModelHubApplication.class)
                .run("--server.port=0");
             HttpClient client = HttpClient.newHttpClient()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/health"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            JsonNode body = objectMapper.readTree(response.body());
            assertEquals("UP", body.path("status").asString());
            assertEquals("ALFHA ModelHub", body.path("application").asString());
            assertEquals(2, body.size());
        }
    }
}
