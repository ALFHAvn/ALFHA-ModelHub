package vn.alfha.modelhub.provider;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.GenerateRequest;
import vn.alfha.modelhub.exception.ProviderException;
import vn.alfha.modelhub.service.GenerationService;
import static org.junit.jupiter.api.Assertions.*;

class OpenRouterPriceCeilingTest {
    @Test void capsRoutedPricingAndPreservesExplicitRequestBody() {
        String model = "vendor/model:free";
        var explicit = OpenRouterProvider.requestBody(model, "test", false);
        assertEquals(Map.of("model", model, "stream", false,
                "messages", List.of(Map.of("role", "user", "content", "test"))), explicit);
        var routed = OpenRouterProvider.requestBody(model, "test", true);
        assertEquals(Map.of("allow_fallbacks", false,
                "max_price", Map.of("prompt", 0, "completion", 0, "request", 0, "image", 0)),
                routed.remove("provider"));
        assertEquals(explicit, routed);
    }

    @Test void rejectsUntrustedEndpointAndVariableOrPaidModelBeforeDispatch() {
        var mapper = new ObjectMapper();
        var custom = new OpenRouterProvider(mapper, URI.create("http://localhost:12345/chat"), Duration.ofSeconds(1), "");
        var official = new OpenRouterProvider(mapper, URI.create(OpenRouterProvider.OFFICIAL_ENDPOINT),
                Duration.ofSeconds(1), "");
        for (var error : List.of(
                assertThrows(ProviderException.class, () -> custom.generateWithZeroPriceCeiling("vendor/model:free", "test")),
                assertThrows(ProviderException.class, () -> official.generateWithZeroPriceCeiling("openrouter/free", "test")),
                assertThrows(ProviderException.class, () -> official.generateWithZeroPriceCeiling("vendor/model", "test")))) {
            assertEquals("CONFIGURATION_ERROR", error.code());
            assertTrue(error.getMessage().contains("official endpoint"));
        }
    }

    @Test void routedGenerationUsesTheCappedProviderPath() {
        var provider = new OpenRouterProvider(new ObjectMapper(), URI.create(OpenRouterProvider.OFFICIAL_ENDPOINT),
                Duration.ofSeconds(1), "") {
            @Override public String generate(String model, String prompt) { return "explicit"; }
            @Override public String generateWithZeroPriceCeiling(String model, String prompt) {
                assertEquals("vendor/model:free", model);
                assertEquals("test", prompt);
                throw new ProviderException("RATE_LIMITED", "Provider rate limit reached");
            }
        };
        var service = new GenerationService(provider, "vendor/model:free");
        var request = new GenerateRequest("vendor/model:free", "test");
        assertEquals("explicit", service.generate(request).response());
        assertEquals("RATE_LIMITED", service.generateRouted(request).error().code());
    }
}
