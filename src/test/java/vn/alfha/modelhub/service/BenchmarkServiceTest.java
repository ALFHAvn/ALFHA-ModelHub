package vn.alfha.modelhub.service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.dto.BenchmarkReport.Result;
import vn.alfha.modelhub.dto.CodingBenchmarkCohort.Failure;
import vn.alfha.modelhub.dto.CodingBenchmarkCohort.FormatValidation;
import vn.alfha.modelhub.dto.CodingBenchmarkCohort.Stage;
import vn.alfha.modelhub.dto.CodingBenchmarkCohort.TimelineEntry;
import vn.alfha.modelhub.dto.CodingEvaluation.Outcome;
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
                new ObjectiveScorer(mapper), new DockerCodingEvaluator(mapper, ""), registry,
                beans.getBeanProvider(BenchmarkRepository.class), "120s", "120s", new BenchmarkDeployment(""));
    }
    @Test void separatesScoringFromProviderFailuresAndRetainsEveryAttempt() throws Exception {
        var catalog = new BenchmarkCatalog(mapper);
        Map<String, String> answers = new HashMap<>();
        for (var task : catalog.dataset().tasks()) answers.put(task.prompt(), switch (task.rule()) {
            case EXACT -> task.expected();
            case JSON_OBJECT -> "{}";
            case JSON_FIELDS -> mapper.writeValueAsString(task.fields());
            case JAVA_CODING -> throw new AssertionError("Expected a basics task");
        });
        var cloudCalls = new AtomicInteger();
        var service = service((model, prompt) -> answers.get(prompt), (model, prompt) -> {
            if (cloudCalls.getAndIncrement() == 0) throw new ProviderException("TIMEOUT", "Request timed out");
            return "incorrect";
        });
        var report = service.run(new BenchmarkRequest("basics", List.of("qwen3:4b", "vendor/model:free")));
        assertEquals(20, report.results().size());
        assertEquals(10, report.summaries().get(0).passed());
        assertNull(report.summaries().get(0).coding());
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

    @Test void separatesRawFormatFromSandboxVerdictsAndTerminalExecutionFailures() {
        var rows = new ArrayList<Result>();
        for (Outcome outcome : List.of(Outcome.PASS, Outcome.WRONG_ANSWER, Outcome.COMPILATION_ERROR,
                Outcome.TIMEOUT, Outcome.FORMAT_ERROR, Outcome.INVALID_SUBMISSION, Outcome.INFRASTRUCTURE_ERROR)) {
            var execution = new GenerateResponse("model", "original response", 3, "SUCCESS", null);
            var grade = new CodingEvaluation(outcome, null, null, null, 0, List.of());
            var earlierFailure = new GenerateResponse("model", null, 3, "FAILED", new ApiError("TIMEOUT", "timed out"));
            rows.add(new Result("task", execution, outcome == Outcome.PASS ? "PASS" : "FAIL",
                    List.of(earlierFailure, execution), grade));
        }
        for (String code : List.of("RATE_LIMITED", "RATE_LIMITED", "HISTORY_UNAVAILABLE")) {
            var failure = new GenerateResponse("model", null, 3, "FAILED", new ApiError(code, "failed"));
            rows.add(new Result("task", failure, "NOT_SCORED", List.of(failure), null));
        }

        var summary = BenchmarkService.summarizeCoding(rows, true);
        assertEquals(4, summary.sandboxVerdicts());
        assertEquals(Map.of(Outcome.PASS, 1, Outcome.WRONG_ANSWER, 1, Outcome.COMPILATION_ERROR, 1, Outcome.TIMEOUT, 1),
                summary.sandboxOutcomes());
        assertEquals(1, summary.invalidSubmissions());
        assertEquals(1, summary.graderInfrastructureFailures());
        assertEquals(Map.of("RATE_LIMITED", 2, "HISTORY_UNAVAILABLE", 1), summary.executionErrors());
        assertEquals(new BenchmarkReport.RawFormatSummary(6, 1), summary.rawFormat());
        assertNull(BenchmarkService.summarizeCoding(rows, false).rawFormat());
    }

    @Test void rejectsMixedModelAndGraderSettingsAndReusedRetryEvidence() throws Exception {
        var service = service((model, prompt) -> fail("Cohort must be read-only"), (model, prompt) -> fail("Cohort must be read-only"));
        var retry = new GenerateResponse("qwen3:4b", null, 1, "FAILED", new ApiError("TIMEOUT", "timed out"));
        UUID baseline = saveCodingFixture("120s", "image-a", retry);
        UUID differentModelConfig = saveCodingFixture("60s", "image-a", null);
        UUID differentImage = saveCodingFixture("120s", "image-b", null);
        UUID repeatedRetry = saveCodingFixture("120s", "image-a", retry);
        assertEquals("Reports must share the dataset snapshot, scoring version, model configuration, schedule, and deployment",
                assertThrows(BenchmarkService.InvalidCohortException.class,
                        () -> service.codingCohort(List.of(baseline, differentModelConfig))).getMessage());
        assertEquals("Reports use different grader images or timeouts",
                assertThrows(BenchmarkService.InvalidCohortException.class,
                        () -> service.codingCohort(List.of(baseline, differentImage))).getMessage());
        assertEquals("Reports contain missing or reused execution IDs",
                assertThrows(BenchmarkService.InvalidCohortException.class,
                        () -> service.codingCohort(List.of(baseline, repeatedRetry))).getMessage());
        var original = saved.get(baseline);
        UUID duplicateTerminal = saveCodingReport(UUID.randomUUID(), Instant.EPOCH, original.scoringVersion(),
                original.models().getFirst().timeout(), original.results());
        assertThrows(BenchmarkService.InvalidCohortException.class,
                () -> service.codingCohort(List.of(baseline, duplicateTerminal)));
    }

    @Test void ordersTimelineByReportCompletionThenStableIdentifiers() throws Exception {
        var service = service((model, prompt) -> fail("Cohort must be read-only"), (model, prompt) -> fail("Cohort must be read-only"));
        var firstId = new UUID(0, 3);
        var secondId = new UUID(0, 1);
        var thirdId = new UUID(0, 2);
        var retry = new GenerateResponse("qwen3:4b", null, 10, "FAILED", new ApiError("TIMEOUT", "timed out"));
        var terminal = new GenerateResponse(new UUID(0, 30), "qwen3:4b", "source", 9, "SUCCESS", null);
        var grade = new CodingEvaluation(Outcome.PASS, "source-hash", "image-a", 8, 12, List.of());
        saveCodingReport(firstId, Instant.EPOCH, "java-coding-v1", "120s", List.of(codingRow(90, "task-z", Outcome.PASS)));
        saveCodingReport(secondId, Instant.EPOCH.plusSeconds(1), "java-coding-v1", "120s", List.of(
                codingRow(10, "task-b", Outcome.PASS), new Result("task-a", terminal, "PASS", List.of(retry, terminal), grade),
                codingRow(20, "task-a", Outcome.PASS)));
        saveCodingReport(thirdId, Instant.EPOCH.plusSeconds(1), "java-coding-v1", "120s", List.of(codingRow(5, "task-a", Outcome.PASS)));

        var cohort = service.codingCohort(List.of(thirdId, secondId, firstId));
        assertEquals(cohort, service.codingCohort(List.of(secondId, firstId, thirdId)));
        assertEquals(List.of(firstId, secondId, secondId, secondId, thirdId),
                cohort.timeline().stream().map(TimelineEntry::reportId).toList());
        assertEquals(List.of(90L, 20L, 30L, 10L, 5L),
                cohort.timeline().stream().map(row -> row.executionId().getLeastSignificantBits()).toList());
        var row = cohort.timeline().get(2);
        assertEquals(Instant.EPOCH.plusSeconds(1), row.completedAt());
        assertEquals("qwen3:4b", row.model());
        assertEquals("task-a", row.taskId());
        assertEquals("SUCCESS", row.executionStatus());
        assertEquals(9L, row.executionDurationMs());
        assertEquals(12L, row.gradingDurationMs());
        assertEquals(8, row.sandboxTimeoutSeconds());
        assertEquals(FormatValidation.NOT_CHECKED, row.formatValidation());
        assertNull(row.error());
        assertEquals(5, cohort.models().getFirst().attempted());
    }

    @Test void classifiesSavedOutcomesAndKeepsMissingEvidenceVisible() throws Exception {
        var service = service((model, prompt) -> fail("Cohort must be read-only"), (model, prompt) -> fail("Cohort must be read-only"));
        var rows = new ArrayList<Result>();
        for (Outcome outcome : Outcome.values()) rows.add(codingRow(outcome.ordinal(), "task", outcome));
        var provider = new GenerateResponse("qwen3:4b", null, 4, "FAILED", new ApiError("TIMEOUT", "timed out"));
        var history = new GenerateResponse("qwen3:4b", null, 5, "FAILED", new ApiError("HISTORY_UNAVAILABLE", "failed"));
        var missingError = new GenerateResponse("qwen3:4b", null, 0, "FAILED", null);
        var missingGrade = new GenerateResponse("qwen3:4b", "source", 6, "SUCCESS", null);
        for (var execution : List.of(provider, history, missingError, missingGrade))
            rows.add(new Result("task", execution, "NOT_SCORED", null, null));
        var reportId = saveCodingReport(UUID.randomUUID(), Instant.EPOCH, "java-coding-raw-v2", "120s", rows);
        var timeline = service.codingCohort(List.of(reportId)).timeline();
        var byId = new HashMap<UUID, TimelineEntry>();
        timeline.forEach(row -> byId.put(row.executionId(), row));
        assertEquals(rows.size(), byId.size());
        for (Outcome verdict : List.of(Outcome.PASS, Outcome.WRONG_ANSWER, Outcome.COMPILATION_ERROR,
                Outcome.EXECUTION_ERROR, Outcome.TIMEOUT, Outcome.OUTPUT_LIMIT)) {
            var row = byId.get(new UUID(0, verdict.ordinal()));
            assertEquals(verdict, row.sandboxVerdict());
            assertEquals(FormatValidation.PASSED, row.formatValidation());
            assertEquals(verdict == Outcome.PASS ? null : new Failure(Stage.SANDBOX, verdict.name()), row.error());
        }
        var rejected = byId.get(new UUID(0, Outcome.FORMAT_ERROR.ordinal()));
        assertEquals(FormatValidation.REJECTED, rejected.formatValidation());
        assertEquals(new Failure(Stage.FORMAT, "FORMAT_ERROR"), rejected.error());
        assertNull(rejected.sandboxVerdict());
        assertNull(rejected.sandboxTimeoutSeconds());
        assertEquals(7L, rejected.gradingDurationMs());
        var invalid = byId.get(new UUID(0, Outcome.INVALID_SUBMISSION.ordinal()));
        assertEquals(FormatValidation.NOT_CHECKED, invalid.formatValidation());
        assertEquals(new Failure(Stage.SUBMISSION, "INVALID_SUBMISSION"), invalid.error());
        assertNull(invalid.sandboxVerdict());
        var infra = byId.get(new UUID(0, Outcome.INFRASTRUCTURE_ERROR.ordinal()));
        assertEquals(FormatValidation.PASSED, infra.formatValidation());
        assertEquals(new Failure(Stage.GRADER, "INFRASTRUCTURE_ERROR"), infra.error());
        assertNull(infra.sandboxVerdict());
        assertEquals(new Failure(Stage.PROVIDER, "TIMEOUT"), byId.get(provider.requestId()).error());
        assertEquals(new Failure(Stage.HISTORY, "HISTORY_UNAVAILABLE"), byId.get(history.requestId()).error());
        assertEquals(new Failure(Stage.GENERATION, null), byId.get(missingError.requestId()).error());
        assertEquals(new Failure(Stage.GRADER, "MISSING_EVALUATION"), byId.get(missingGrade.requestId()).error());
        for (var execution : List.of(provider, history, missingError, missingGrade)) {
            var row = byId.get(execution.requestId());
            assertEquals(FormatValidation.NOT_CHECKED, row.formatValidation());
            assertNull(row.sandboxVerdict());
            assertNull(row.gradingDurationMs());
            assertNull(row.sandboxTimeoutSeconds());
        }
    }

    private static Result codingRow(long id, String task, Outcome outcome) {
        var execution = new GenerateResponse(new UUID(0, id), "qwen3:4b", "source", 4, "SUCCESS", null);
        return new Result(task, execution, outcome == Outcome.PASS ? "PASS" : "FAIL", null,
                new CodingEvaluation(outcome, null, null, null, 7, List.of()));
    }

    private UUID saveCodingFixture(String timeout, String image, GenerateResponse retry) throws Exception {
        var result = new GenerateResponse("qwen3:4b", "source", 1, "SUCCESS", null);
        var grade = new CodingEvaluation(Outcome.PASS, "source-hash", image, 8, 1,
                List.of(new CodingEvaluation.CaseResult("ascending", Outcome.PASS, 1)));
        var row = new Result("median-of-three", result, "PASS", retry == null ? List.of(result) : List.of(retry, result), grade);
        return saveCodingReport(UUID.randomUUID(), Instant.EPOCH, "java-coding-raw-v2", timeout, List.of(row));
    }

    private UUID saveCodingReport(UUID id, Instant completedAt, String scoringVersion, String timeout, List<Result> rows) throws Exception {
        var catalog = new BenchmarkCatalog(mapper);
        saved.put(id, new BenchmarkReport(id, Instant.EPOCH, completedAt, catalog.hash("java-coding", "1"),
                scoringVersion, catalog.dataset("java-coding", "1"),
                List.of(new BenchmarkReport.ModelConfig("qwen3:4b", "ollama", timeout, "defaults")),
                rows, List.of(), null, List.of(), "test-deployment"));
        return id;
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
    @Test void executesTheExplicitVersionAndRejectsUnknownVersionsBeforeProviderCalls() throws Exception {
        var prompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var service = service((model, prompt) -> { prompts.add(prompt); return "{}"; }, (model, prompt) -> "{}");
        assertThrows(IllegalArgumentException.class,
                () -> service.run(new BenchmarkRequest("basics", "unknown", List.of("qwen3:4b"))));
        assertTrue(prompts.isEmpty());
        var report = service.run(new BenchmarkRequest("basics", "2", List.of("qwen3:4b")));
        assertEquals("2", report.dataset().version());
        assertEquals(new BenchmarkCatalog(mapper).hash("2"), report.datasetSha256());
        assertTrue(prompts.getLast().contains("not an empty string"));
        assertEquals("objective-v1", report.scoringVersion());
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
