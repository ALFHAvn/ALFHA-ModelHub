package vn.alfha.modelhub;

import org.junit.jupiter.api.Test;
import vn.alfha.modelhub.dto.GenerateRequest;
import vn.alfha.modelhub.service.GenerationService;
import vn.alfha.modelhub.exception.ProviderException;
import static org.junit.jupiter.api.Assertions.*;

class GenerationServiceTest {
    @Test void returnsSuccessfulResultFromFakeProvider() {
        var service = new GenerationService((model, prompt) -> "Hello", "qwen3:4b");
        var result = service.generate(new GenerateRequest("qwen3:4b", "Hi"));
        assertEquals("Hello", result.response());
        assertEquals("qwen3:4b", result.model());
        assertEquals("SUCCESS", result.status());
        assertNull(result.error());
        assertTrue(result.durationMs() >= 0);
    }
    @Test void returnsFailedResultFromFakeProvider() {
        var service = new GenerationService((model, prompt) -> {
            throw new ProviderException("TIMEOUT", "Ollama request timed out");
        }, "qwen3:4b");
        var result = service.generate(new GenerateRequest("qwen3:4b", "Hi"));
        assertEquals("FAILED", result.status());
        assertNull(result.response());
        assertEquals("TIMEOUT", result.error().code());
        assertTrue(result.durationMs() >= 0);
    }
    @Test void rejectsInvalidInputBeforeCallingProvider() {
        var service = new GenerationService((model, prompt) -> { fail("Provider must not be called"); return ""; }, "qwen3:4b");
        assertThrows(IllegalArgumentException.class, () -> service.generate(null));
        assertThrows(IllegalArgumentException.class, () -> service.generate(new GenerateRequest(null, "Hi")));
        assertThrows(IllegalArgumentException.class, () -> service.generate(new GenerateRequest("qwen3:4b", " ")));
        assertThrows(IllegalArgumentException.class, () -> service.generate(new GenerateRequest("qwen3:4b", "x".repeat(32001))));
        assertThrows(GenerationService.UnknownModelException.class, () -> service.generate(new GenerateRequest("unknown", "Hi")));
    }
}
