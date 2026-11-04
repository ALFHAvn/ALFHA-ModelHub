package vn.alfha.modelhub.service;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.provider.*;
import vn.alfha.modelhub.exception.ProviderException;
import vn.alfha.modelhub.repository.BenchmarkRepository;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, BenchmarkReport> saved = new ConcurrentHashMap<>();

    private BenchmarkService service(ModelProvider local, ModelProvider cloud) throws Exception {
        var generation = new GenerationService(Map.of("qwen3:4b", local, "vendor/model:free", cloud));
        var registry = new ModelRegistry(new OllamaProvider(mapper, URI.create("http://localhost:1"), Duration.ofSeconds(1)),
                new OpenRouterProvider(mapper, URI.create("http://localhost:1/chat"), Duration.ofSeconds(1), ""),
                "qwen3:4b", true, "vendor/model:free");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("repository", new BenchmarkRepository(null, mapper) {
            @Override public void save(BenchmarkReport report) { saved.put(report.id(), report); }
            @Override public Optional<BenchmarkReport> find(UUID id) { return Optional.ofNullable(saved.get(id)); }
        });
        return new BenchmarkService(new BenchmarkCatalog(mapper), generation, new ComparisonService(generation),
                new ObjectiveScorer(mapper), registry, beans.getBeanProvider(BenchmarkRepository.class), "120s", "120s");
    }
    @Test void separatesScoringFromProviderFailuresAndRetainsEveryAttempt() throws Exception {
        var catalog = new BenchmarkCatalog(mapper);
        Map<String, String> answers = new HashMap<>();
        for (var task : catalog.dataset().tasks()) answers.put(task.prompt(), switch (task.rule()) {
            case EXACT -> task.expected();
            case JSON_OBJECT -> "{}";
            case JSON_FIELDS -> mapper.writeValueAsString(task.fields());
        });
        var cloudCalls = new AtomicInteger();
        var service = service((model, prompt) -> answers.get(prompt), (model, prompt) -> {
            if (cloudCalls.getAndIncrement() == 0) throw new ProviderException("TIMEOUT", "Request timed out");
            return "incorrect";
        });
        var report = service.run(new BenchmarkRequest("basics", List.of("qwen3:4b", "vendor/model:free")));
        assertEquals(20, report.results().size());
        assertEquals(10, report.summaries().get(0).passed());
        assertEquals(1.0, report.summaries().get(0).passRateAllAttempts());
        var cloud = report.summaries().get(1);
        assertEquals(1, cloud.failed());
        assertEquals(9, cloud.scoringFailures());
        assertEquals(0, cloud.passed());
        assertEquals("NOT_SCORED", report.results().get(1).score());
        assertEquals("TIMEOUT", report.results().get(1).execution().error().code());
        assertEquals(catalog.hash(), report.datasetSha256());
        assertEquals(report, service.find(report.id()));
        assertEquals(20, report.results().stream().map(r -> r.execution().requestId()).distinct().count());
    }
    @Test void allFailuresHaveNoSuccessLatencyOrConditionalPassRate() throws Exception {
        ModelProvider failure = (model, prompt) -> { throw new ProviderException("TIMEOUT", "Request timed out"); };
        var report = service(failure, failure).run(new BenchmarkRequest("basics", List.of("qwen3:4b")));
        var summary = report.summaries().getFirst();
        assertEquals(10, summary.failed());
        assertEquals(0, summary.scoringFailures());
        assertNull(summary.averageSuccessfulDurationMs());
        assertNull(summary.passRateAmongSuccessful());
        assertEquals(0.0, summary.passRateAllAttempts());
    }
    @Test void rejectsInvalidSelectionsBeforeAnyProviderCalls() throws Exception {
        var calls = new AtomicInteger();
        ModelProvider provider = (model, prompt) -> { calls.incrementAndGet(); return ""; };
        var service = service(provider, provider);
        for (var request : List.of(new BenchmarkRequest("wrong", List.of("qwen3:4b")),
                new BenchmarkRequest("basics", List.of()), new BenchmarkRequest("basics", List.of("qwen3:4b", "qwen3:4b")),
                new BenchmarkRequest("basics", List.of("openrouter/free")), new BenchmarkRequest("basics", null)))
            assertThrows(IllegalArgumentException.class, () -> service.run(request));
        assertThrows(GenerationService.UnknownModelException.class,
                () -> service.run(new BenchmarkRequest("basics", List.of("unknown"))));
        assertEquals(0, calls.get());
        assertTrue(saved.isEmpty());
    }
    @Test void rejectsOverlappingBatchesAndReleasesSlot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = service((model, prompt) -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return "answer";
        }, (model, prompt) -> "answer");
        var request = new BenchmarkRequest("basics", List.of("qwen3:4b"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.run(request));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertThrows(BenchmarkService.BusyException.class, () -> service.run(request));
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertNotNull(service.run(request));
    }
}
