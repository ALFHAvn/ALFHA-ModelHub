package vn.alfha.modelhub;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.provider.OpenRouterProvider;
import vn.alfha.modelhub.exception.ProviderException;
import static org.junit.jupiter.api.Assertions.*;

class OpenRouterProviderTest {
    @Test void sendsAuthenticatedChatAndParsesResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/chat", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            assertEquals("Hello", provider(server, "test-key", Duration.ofSeconds(5)).generate("vendor/model:free", "Hi"));
            assertEquals("Bearer test-key", authorization.get());
            var body = new ObjectMapper().readTree(captured.get());
            assertEquals("vendor/model:free", body.path("model").asString());
            assertEquals("Hi", body.path("messages").path(0).path("content").asString());
            assertEquals("user", body.path("messages").path(0).path("role").asString());
            assertFalse(body.path("stream").asBoolean());
            assertFalse(body.has("models"));
        } finally { server.stop(0); }
    }

    @Test void sanitizesErrorsAndRejectsInvalidResponses() throws Exception {
        for (var sample : new String[][] {
                {"401", "test-key secret", "PROVIDER_ERROR"}, {"429", "secret", "RATE_LIMITED"},
                {"500", "secret", "PROVIDER_ERROR"}, {"302", "secret", "PROVIDER_ERROR"},
                {"200", "not json", "INVALID_PROVIDER_RESPONSE"}, {"200", "{}", "INVALID_PROVIDER_RESPONSE"},
                {"200", "{\"error\":{\"message\":\"secret\"}}", "INVALID_PROVIDER_RESPONSE"},
                {"200", "{\"choices\":[{\"message\":{\"content\":null}}]}", "INVALID_PROVIDER_RESPONSE"}}) {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/chat", exchange -> {
                byte[] bytes = sample[1].getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(Integer.parseInt(sample[0]), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var error = assertThrows(ProviderException.class,
                        () -> provider(server, "test-key", Duration.ofSeconds(5)).generate("vendor/model:free", "Hi"));
                assertEquals(sample[2], error.code());
                assertFalse(error.getMessage().contains("secret"));
                assertFalse(error.getMessage().contains("test-key"));
            } finally { server.stop(0); }
        }
    }
    @Test void rejectsPaidModelsAndMissingCredentialsWithoutNetworkCalls() {
        var provider = new OpenRouterProvider(new ObjectMapper(), URI.create("http://localhost:1/chat"), Duration.ofSeconds(1), "");
        assertEquals("CONFIGURATION_ERROR", assertThrows(ProviderException.class,
                () -> provider.generate("vendor/model:free", "Hi")).code());
        assertEquals("CONFIGURATION_ERROR", assertThrows(ProviderException.class,
                () -> provider.generate("vendor/paid", "Hi")).code());
        assertTrue(OpenRouterProvider.isFreeModel("openrouter/free"));
        assertFalse(OpenRouterProvider.isFreeModel("vendor/model:free:online"));
    }
    @Test void handlesTimeoutAndConnectionFailure() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat", exchange -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            assertEquals("TIMEOUT", assertThrows(ProviderException.class,
                    () -> provider(server, "test-key", Duration.ofMillis(50)).generate("vendor/model:free", "Hi")).code());
        } finally { server.stop(0); }
        assertEquals("CONNECTION_FAILURE", assertThrows(ProviderException.class,
                () -> provider(server, "test-key", Duration.ofSeconds(2)).generate("vendor/model:free", "Hi")).code());
    }
    @Test void rejectsInsecureEndpointAndInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterProvider(new ObjectMapper(),
                URI.create("http://example.com/chat"), Duration.ofSeconds(1), "test-key"));
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterProvider(new ObjectMapper(),
                URI.create("https://example.com/chat"), Duration.ZERO, "test-key"));
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterProvider(new ObjectMapper(),
                URI.create("https://example.com/chat"), Duration.ofSeconds(1), "key\r\nheader"));
    }
    private OpenRouterProvider provider(HttpServer server, String key, Duration timeout) {
        return new OpenRouterProvider(new ObjectMapper(), URI.create("http://localhost:" + server.getAddress().getPort() + "/chat"), timeout, key);
    }
}
