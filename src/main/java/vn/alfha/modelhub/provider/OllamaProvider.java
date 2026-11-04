package vn.alfha.modelhub.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.exception.ProviderException;

@Component
public class OllamaProvider implements ModelProvider {
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    private final ObjectMapper mapper;

    public OllamaProvider(ObjectMapper mapper,
            @Value("${modelhub.ollama.base-url}") URI baseUrl,
            @Value("${modelhub.ollama.timeout}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive");
        if (!java.util.Set.of("http", "https").contains(baseUrl.getScheme()) || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("Invalid Ollama base URL");
        }
        this.mapper = mapper;
        this.endpoint = baseUrl.resolve("/api/generate");
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String generate(String model, String prompt) {
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("model", model, "prompt", prompt, "stream", false)))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) throw failure("RATE_LIMITED", "Provider rate limit reached");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw failure("PROVIDER_ERROR", "Ollama rejected the request");
            try {
                var body = mapper.readTree(response.body());
                if (body == null || !body.path("done").isBoolean() || !body.path("done").asBoolean()
                        || !body.path("response").isString() || body.has("error"))
                    throw failure("INVALID_PROVIDER_RESPONSE", "Ollama returned an invalid response");
                return body.path("response").asString();
            } catch (tools.jackson.core.JacksonException e) {
                throw failure("INVALID_PROVIDER_RESPONSE", "Ollama returned an invalid response");
            }
        } catch (HttpTimeoutException e) {
            throw failure("TIMEOUT", "Ollama request timed out");
        } catch (IOException e) {
            throw failure("CONNECTION_FAILURE", "Unable to communicate with Ollama");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("INTERRUPTED", "Provider request interrupted");
        }
    }
    private ProviderException failure(String code, String message) { return new ProviderException(code, message); }
}
