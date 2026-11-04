package vn.alfha.modelhub.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.provider.ModelProvider;
import vn.alfha.modelhub.exception.ProviderException;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonServiceTest {
    @Test void startsBothProvidersConcurrentlyAndPreservesSelectionOrder() {
        var started = new CountDownLatch(2);
        ModelProvider provider = (model, prompt) -> {
            started.countDown();
            try { assertTrue(started.await(3, TimeUnit.SECONDS), "Both providers must start before either finishes"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            assertEquals("same prompt", prompt);
            return model + " answer";
        };
        var result = service(provider, provider).compare(request());
        assertEquals(List.of("first", "second"), result.results().stream().map(GenerateResponse::model).toList());
        for (var run : result.results()) {
            assertEquals("SUCCESS", run.status());
            assertEquals(run.model() + " answer", run.response());
            assertTrue(run.durationMs() >= 0);
        }
    }
    @Test void preservesSuccessWhenEitherProviderFailsOrTimesOut() {
        for (String code : List.of("TIMEOUT", "CONNECTION_FAILURE", "RATE_LIMITED")) {
            ModelProvider success = (model, prompt) -> "answer";
            ModelProvider failure = (model, prompt) -> { throw new ProviderException(code, "Safe failure"); };
            for (boolean failFirst : List.of(true, false)) {
                var result = service(failFirst ? failure : success, failFirst ? success : failure).compare(request());
                var failed = result.results().get(failFirst ? 0 : 1);
                var succeeded = result.results().get(failFirst ? 1 : 0);
                assertEquals("FAILED", failed.status());
                assertNull(failed.response());
                assertEquals(code, failed.error().code());
                assertTrue(failed.durationMs() >= 0);
                assertEquals("SUCCESS", succeeded.status());
                assertEquals("answer", succeeded.response());
            }
        }
    }
    @Test void isolatesUnexpectedErrorsAndRetainsBothFailures() {
        ModelProvider bug = (model, prompt) -> { throw new IllegalStateException("secret"); };
        var result = service(bug, bug).compare(request());
        for (var run : result.results()) {
            assertEquals("FAILED", run.status());
            assertEquals("INTERNAL_ERROR", run.error().code());
            assertFalse(run.error().message().contains("secret"));
        }
    }
    @Test void validatesEntireRequestBeforeCallingProviders() {
        var calls = new AtomicInteger();
        ModelProvider provider = (model, prompt) -> { calls.incrementAndGet(); return "answer"; };
        var service = service(provider, provider);
        for (var invalid : List.of(new CompareRequest(null, "Hi"), new CompareRequest(List.of("first"), "Hi"),
                new CompareRequest(List.of("first", "first"), "Hi"), new CompareRequest(List.of("first", "second", "third"), "Hi"),
                new CompareRequest(java.util.Arrays.asList("first", null), "Hi"), new CompareRequest(List.of("first", "second"), " "),
                new CompareRequest(List.of("first", "second"), "x".repeat(32001)))) {
            assertThrows(IllegalArgumentException.class, () -> service.compare(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> service.compare(null));
        assertThrows(GenerationService.UnknownModelException.class,
                () -> service.compare(new CompareRequest(List.of("first", "unknown"), "Hi")));
        assertEquals(0, calls.get());
    }
    private ComparisonService service(ModelProvider first, ModelProvider second) {
        return new ComparisonService(new GenerationService(Map.of("first", first, "second", second)));
    }
    private CompareRequest request() { return new CompareRequest(List.of("first", "second"), "same prompt"); }
}
