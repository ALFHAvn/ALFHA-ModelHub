package vn.alfha.modelhub.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.exception.ProviderException;

@Component
public class OpenRouterProvider implements ModelProvider {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    private final String key;

    public OpenRouterProvider(ObjectMapper mapper,
            @Value("${modelhub.openrouter.endpoint}") URI endpoint,
            @Value("${modelhub.openrouter.timeout}") Duration timeout,
            @Value("${modelhub.openrouter.api-key}") String key) {
        boolean local = "localhost".equals(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost());
        if (endpoint.getHost() == null || !("https".equals(endpoint.getScheme())
                || (local && "http".equals(endpoint.getScheme()))) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("OpenRouter endpoint requires HTTPS (HTTP allowed for loopback tests)");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Timeout must be positive");
        if (key.contains("\r") || key.contains("\n")) throw new IllegalArgumentException("Invalid OpenRouter key format");
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.key = key;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static boolean isFreeModel(String model) {
        return model != null && (model.equals("openrouter/free") || model.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:free"));
    }

    @Override
    public String generate(String model, String prompt) {
        if (!isFreeModel(model)) throw failure("CONFIGURATION_ERROR", "Only free OpenRouter model identifiers are allowed");
        if (key.isBlank()) throw failure("CONFIGURATION_ERROR", "OpenRouter credentials are not configured");
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "model", model, "stream", false,
                            "messages", List.of(Map.of("role", "user", "content", prompt)))))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                Long delay = response.headers().firstValue("Retry-After")
                        .map(OpenRouterProvider::parseRetryAfter).orElse(null);
                throw new ProviderException("RATE_LIMITED", "Provider rate limit reached", delay);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw failure("PROVIDER_ERROR", "OpenRouter rejected the request");
            try {
                var body = mapper.readTree(response.body());
                var choice = body == null ? null : body.path("choices").path(0);
                if (body == null || body.has("error") || choice == null
                        || !choice.path("message").path("content").isString()
                        || !choice.path("finish_reason").isString()
                        || !List.of("stop", "length").contains(choice.path("finish_reason").asString()))
                    throw failure("INVALID_PROVIDER_RESPONSE", "OpenRouter returned an invalid response");
                return choice.path("message").path("content").asString();
            } catch (tools.jackson.core.JacksonException e) {
                throw failure("INVALID_PROVIDER_RESPONSE", "OpenRouter returned an invalid response");
            }
        } catch (HttpTimeoutException e) {
            throw failure("TIMEOUT", "OpenRouter request timed out");
        } catch (IOException e) {
            throw failure("CONNECTION_FAILURE", "Unable to communicate with OpenRouter");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("INTERRUPTED", "Provider request interrupted");
        }
    }
    static Long parseRetryAfter(String value) {
        String header = value.strip();
        if (header.matches("[0-9]+")) {
            try {
                return Math.multiplyExact(Long.parseLong(header), 1000);
            } catch (NumberFormatException | ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }
        try {
            Instant date = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0, Duration.between(Instant.now(), date).toMillis());
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    private ProviderException failure(String code, String message) { return new ProviderException(code, message); }
}
