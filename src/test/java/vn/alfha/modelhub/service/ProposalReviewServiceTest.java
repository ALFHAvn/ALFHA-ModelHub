package vn.alfha.modelhub.service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.ProposalReviewRequest;
import vn.alfha.modelhub.provider.OllamaProvider;
import vn.alfha.modelhub.provider.OpenRouterProvider;
import static org.junit.jupiter.api.Assertions.*;

class ProposalReviewServiceTest {
    @Test void cloudRequiresExplicitOptInAndUsesTheCappedPath() {
        var mapper = new ObjectMapper();
        var calls = new ArrayList<String>();
        var timeout = Duration.ofSeconds(1);
        var ollama = new OllamaProvider(mapper, URI.create("http://localhost:1"), timeout) {
            @Override public String generate(String model, String prompt) {
                calls.add(model);
                return "Proposal";
            }
        };
        var openRouter = new OpenRouterProvider(mapper, URI.create(OpenRouterProvider.OFFICIAL_ENDPOINT), timeout, "") {
            @Override public String generate(String model, String prompt) { throw new AssertionError("Uncapped cloud call"); }
            @Override public String generateWithZeroPriceCeiling(String model, String prompt) {
                calls.add(model);
                assertTrue(prompt.contains("Proposal"));
                return "Review";
            }
        };
        var registry = new ModelRegistry(ollama, openRouter, "local", true, "vendor/model:free");
        var generation = new GenerationService(registry, null);
        var service = new ProposalReviewService(generation, registry, mapper, OpenRouterProvider.OFFICIAL_ENDPOINT);
        var optIn = new ProposalReviewRequest("local", "vendor/model:free", "Task", true);

        assertThrows(IllegalArgumentException.class,
                () -> service.run(new ProposalReviewRequest("local", "vendor/model:free", "Task", false)));
        assertThrows(IllegalArgumentException.class,
                () -> service.run(new ProposalReviewRequest("local", "vendor/model:free", "Task", null)));
        var custom = new ProposalReviewService(generation, registry, mapper, "http://localhost:1/chat");
        assertThrows(IllegalArgumentException.class, () -> custom.run(optIn));
        var aliasRegistry = new ModelRegistry(ollama, openRouter, "local", true, "openrouter/free");
        var aliasService = new ProposalReviewService(new GenerationService(aliasRegistry, null), aliasRegistry,
                mapper, OpenRouterProvider.OFFICIAL_ENDPOINT);
        assertThrows(IllegalArgumentException.class,
                () -> aliasService.run(new ProposalReviewRequest("local", "openrouter/free", "Task", true)));
        assertTrue(calls.isEmpty());

        var result = service.run(optIn);
        assertEquals("Review", result.review().response());
        assertEquals(List.of("local", "vendor/model:free"), calls);
    }
}
