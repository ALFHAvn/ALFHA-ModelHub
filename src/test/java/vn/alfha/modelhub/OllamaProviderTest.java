package vn.alfha.modelhub;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.provider.OllamaProvider;
import vn.alfha.modelhub.exception.ProviderException;
import static org.junit.jupiter.api.Assertions.*;

class OllamaProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sendsNonStreamingRequestAndReadsResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/generate", exchange -> {
            var body = mapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("qwen3:4b", body.path("model").asString());
            assertEquals("Hello", body.path("prompt").asString());
            assertFalse(body.path("stream").asBoolean());
            byte[] bytes = "{\"response\":\"Hi\",\"done\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            assertEquals("Hi", provider(server, Duration.ofSeconds(5)).generate("qwen3:4b", "Hello"));
        } finally { server.stop(0); }
    }

    @Test void sanitizesUpstreamErrorsAndRejectsInvalidResponses() throws Exception {
        for (var sample : new String[][] {
                {"429", "secret", "RATE_LIMITED"}, {"500", "secret", "PROVIDER_ERROR"},
                {"200", "not json secret", "INVALID_PROVIDER_RESPONSE"},
                {"200", "{}", "INVALID_PROVIDER_RESPONSE"},
                {"200", "{\"response\":\"partial\",\"done\":false}", "INVALID_PROVIDER_RESPONSE"}}) {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/generate", exchange -> {
                byte[] bytes = sample[1].getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(Integer.parseInt(sample[0]), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var error = assertThrows(ProviderException.class,
                        () -> provider(server, Duration.ofSeconds(5)).generate("qwen3:4b", "Hello"));
                assertEquals(sample[2], error.code());
                assertFalse(error.getMessage().contains("secret"));
            } finally { server.stop(0); }
        }
    }

    @Test void handlesTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/generate", exchange -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var error = assertThrows(ProviderException.class,
                    () -> provider(server, Duration.ofMillis(50)).generate("qwen3:4b", "Hello"));
            assertEquals("TIMEOUT", error.code());
        } finally { server.stop(0); }
    }

    @Test void handlesConnectionFailure() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var provider = new OllamaProvider(mapper, URI.create("http://localhost:" + port), Duration.ofSeconds(2));
        var error = assertThrows(ProviderException.class, () -> provider.generate("qwen3:4b", "Hello"));
        assertEquals("CONNECTION_FAILURE", error.code());
    }
    private OllamaProvider provider(HttpServer server, Duration timeout) {
        return new OllamaProvider(mapper, URI.create("http://localhost:" + server.getAddress().getPort()), timeout);
    }
}
