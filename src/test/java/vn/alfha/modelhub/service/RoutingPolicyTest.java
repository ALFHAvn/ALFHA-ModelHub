package vn.alfha.modelhub.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.ApiError;
import vn.alfha.modelhub.dto.BenchmarkReport;
import vn.alfha.modelhub.dto.BenchmarkReport.ModelConfig;
import vn.alfha.modelhub.dto.BenchmarkReport.Result;
import vn.alfha.modelhub.dto.BenchmarkSchedule;
import vn.alfha.modelhub.dto.GenerateResponse;
import vn.alfha.modelhub.service.RoutingPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-01T00:00:00Z");
    private static final Candidate LOCAL = new Candidate("qwen3:4b", "ollama", "120s");
    private static final Candidate CLOUD = new Candidate("vendor/model:free", "openrouter", "120s");
    private final BenchmarkCatalog catalog;

    RoutingPolicyTest() throws Exception { catalog = new BenchmarkCatalog(new ObjectMapper()); }

    @Test void separatesWrongAnswersFromRateLimitsAndKeepsCloudUnverified() {
        var reports = List.of(report(1), report(2), report(3));
        var decision = preview(List.of(LOCAL, CLOUD), reports);
        assertEquals("EVIDENCE_SELECTED", decision.outcome());
        assertEquals(LOCAL.model(), decision.selectedModel());
        var local = decision.candidates().getFirst().metrics();
        assertEquals(3, local.incorrect());
        assertEquals(0, local.providerFailures());
        assertEquals(0.9, local.correctness());
        assertEquals(14.5, local.medianLatencyMs());
        var cloud = decision.candidates().getLast();
        assertEquals(9, cloud.metrics().providerFailures());
        assertEquals(0, cloud.metrics().incorrect());
        assertEquals(1.0, cloud.metrics().correctness());
        assertEquals(0.7, cloud.metrics().reliability());
        assertTrue(cloud.reasons().containsAll(List.of("CLOUD_NOT_VERIFIED", "LOW_PROVIDER_RELIABILITY")));
        assertEquals(decision, preview(List.of(CLOUD, LOCAL), reports.reversed()));
    }

    @Test void rejectsNonComparableReportsAndKeepsFallbackExplicit() {
        var first = report(1);
        var duplicateExecution = copy(first, new UUID(0, 2), first.completedAt(),
                first.datasetSha256(), first.scoringVersion(), null, first.models(), first.results());
        var schedule = copy(report(3), new UUID(0, 3), first.completedAt(),
                first.datasetSha256(), first.scoringVersion(), new BenchmarkSchedule(3000, 1), first.models(), report(3).results());
        var stale = report(4, NOW.minusSeconds(15 * 86400));
        var future = report(5, NOW.plusSeconds(1));
        var mismatch = copy(report(6), new UUID(0, 6), first.completedAt(),
                "different-hash", "other-scorer", null, first.models(), report(6).results());
        var drift = copy(report(7), new UUID(0, 7), first.completedAt(),
                first.datasetSha256(), first.scoringVersion(), null,
                List.of(config(LOCAL), new ModelConfig(CLOUD.model(), CLOUD.provider(), "90s", "defaults")), report(7).results());
        var incomplete = copy(report(8), new UUID(0, 8), first.completedAt(),
                first.datasetSha256(), first.scoringVersion(), null, first.models(), first.results().subList(1, 20));
        var decision = preview(List.of(LOCAL, CLOUD), List.of(first, duplicateExecution, schedule, stale,
                future, mismatch, drift, incomplete, report(9), report(9)));
        assertEquals("FALLBACK_LOCAL", decision.outcome());
        assertEquals("NO_ELIGIBLE_EVIDENCE", decision.reason());
        assertTrue(decision.reportIds().isEmpty());
        assertTrue(decision.exclusions().stream().map(Exclusion::reason).toList().containsAll(List.of(
                "DUPLICATE_EXECUTION", "DUPLICATE_REPORT", "INCOMPATIBLE_SCHEDULE", "STALE_EVIDENCE",
                "INVALID_REPORT_TIME", "DATASET_OR_SCORER_MISMATCH", "CONFIGURATION_MISMATCH", "INCOMPLETE_REPORT")));
        assertEquals("FALLBACK_LOCAL", preview(List.of(LOCAL, CLOUD), List.of(report(1), report(2))).outcome());
        assertEquals("STALE_EVIDENCE", preview(List.of(LOCAL, CLOUD), List.of(stale)).reason());
    }

    @Test void missingEvidenceAndBlockedModelsCannotRouteCloud() {
        var blocked = List.of(CLOUD, new Candidate("openrouter/free", "openrouter", "120s"),
                new Candidate("vendor/paid", "openrouter", "120s"));
        var decision = preview(blocked, List.of());
        assertEquals("NO_ROUTE", decision.outcome());
        assertNull(decision.selectedModel());
        assertEquals("NO_EVIDENCE", decision.reason());
        assertEquals(2, decision.candidates().stream().filter(e -> e.reasons().contains("MODEL_NOT_ELIGIBLE")).count());
        var failedLookup = new RoutingPolicy().preview(NOW, CUTOFF, catalog.dataset("2"), catalog.hash("2"),
                List.of(LOCAL, CLOUD), List.of(report(1), report(2), report(3)),
                List.of(new Exclusion(null, "EVIDENCE_UNAVAILABLE")));
        assertEquals("FALLBACK_LOCAL", failedLookup.outcome());
        assertEquals("EVIDENCE_UNAVAILABLE", failedLookup.reason());
    }

    @Test void usesExactBandBoundaryAndOrderedTies() {
        var best = entry("ollama", "best", 20, 20, 20, 10);
        var boundary = entry("ollama", "boundary", 19, 20, 20, 5);
        var outside = entry("ollama", "outside", 18, 20, 20, 1);
        assertEquals(boundary, RoutingPolicy.rank(List.of(best, boundary, outside)));
        assertEquals(best, RoutingPolicy.rank(List.of(best, entry("ollama", "lower", 19, 20, 20, 10))));
        assertEquals(best, RoutingPolicy.rank(List.of(best, entry("ollama", "less-reliable", 20, 20, 24, 10))));
        assertEquals(best, RoutingPolicy.rank(List.of(best, entry("ollama", "smaller", 10, 10, 10, 10))));
        assertEquals(best, RoutingPolicy.rank(List.of(entry("openrouter", "a", 20, 20, 20, 10),
                entry("ollama", "z", 20, 20, 20, 10), best)));
    }

    @Test void requiresFreshVerificationForTheExactCloudModel() {
        var reports = List.of(report(1, NOW, 0), report(2, NOW, 0), report(3, NOW, 0));
        var fresh = new OpenRouterPricing.Check(CLOUD.model(), NOW.minusSeconds(60), null);
        var decision = preview(List.of(LOCAL, checked(fresh)), reports);
        assertEquals(CLOUD.model(), decision.selectedModel());
        assertEquals(fresh.observedAt(), decision.candidates().getLast().pricingObservedAt());
        var dispatch = new RoutingPolicy().evaluate(NOW, CUTOFF, catalog.dataset("2"), catalog.hash("2"),
                List.of(LOCAL, checked(fresh)), reports, List.of(), true);
        assertEquals(LOCAL.model(), dispatch.selectedModel());
        assertEquals("EVIDENCE_SELECTED", dispatch.outcome());
        assertEquals(List.of("CLOUD_DISPATCH_DISABLED"), dispatch.candidates().getLast().reasons());
        var noLocal = new RoutingPolicy().evaluate(NOW, CUTOFF, catalog.dataset("2"), catalog.hash("2"),
                List.of(checked(fresh)), reports, List.of(), true);
        assertEquals("NO_ROUTE", noLocal.outcome());
        assertNull(noLocal.selectedModel());
        for (var check : List.of(
                new OpenRouterPricing.Check(CLOUD.model(), NOW.minusSeconds(61), null),
                new OpenRouterPricing.Check(CLOUD.model(), NOW.plusSeconds(1), null),
                new OpenRouterPricing.Check("vendor/other:free", NOW, null),
                new OpenRouterPricing.Check(CLOUD.model(), NOW, "CLOUD_NONZERO_PRICE"))) {
            assertEquals(LOCAL.model(), preview(List.of(LOCAL, checked(check)), reports).selectedModel());
        }
    }

    private Candidate checked(OpenRouterPricing.Check check) {
        return new Candidate(CLOUD.model(), CLOUD.provider(), CLOUD.timeout(), check);
    }

    private Explanation entry(String provider, String model, int passed, int scored, int attempted, double latency) {
        return new Explanation(model, provider, new Metrics(3, 10, attempted, scored, passed, scored - passed,
                attempted - scored, (double) passed / scored, (double) scored / attempted, latency), List.of(), null);
    }

    private Decision preview(List<Candidate> candidates, List<BenchmarkReport> reports) {
        return new RoutingPolicy().preview(NOW, CUTOFF, catalog.dataset("2"), catalog.hash("2"),
                candidates, reports, List.of());
    }

    private BenchmarkReport report(int id) { return report(id, NOW.minusSeconds(60)); }

    private BenchmarkReport report(int id, Instant completed) {
        return report(id, completed, 3);
    }

    private BenchmarkReport report(int id, Instant completed, int failures) {
        var dataset = catalog.dataset("2");
        var results = new ArrayList<Result>();
        for (int i = 0; i < dataset.tasks().size(); i++) {
            String taskId = dataset.tasks().get(i).id();
            var local = new GenerateResponse(new UUID(id, i * 2L), LOCAL.model(), "answer", 10 + i, "SUCCESS", null);
            boolean failed = i < failures;
            var cloud = new GenerateResponse(new UUID(id, i * 2L + 1), CLOUD.model(), failed ? null : "answer",
                    failed ? 9000 : 2, failed ? "FAILED" : "SUCCESS",
                    failed ? new ApiError("RATE_LIMITED", "limited") : null);
            results.add(new Result(taskId, local, i == 0 ? "FAIL" : "PASS", List.of(local), null));
            results.add(new Result(taskId, cloud, failed ? "NOT_SCORED" : "PASS", List.of(cloud), null));
        }
        return new BenchmarkReport(new UUID(0, id), completed.minusSeconds(10), completed, catalog.hash("2"),
                "objective-v1", dataset, List.of(config(LOCAL), config(CLOUD)), results, List.of(), null, List.of());
    }

    private ModelConfig config(Candidate candidate) {
        return new ModelConfig(candidate.model(), candidate.provider(), candidate.timeout(), "defaults");
    }

    private BenchmarkReport copy(BenchmarkReport original, UUID id, Instant completed, String hash, String scorer,
            BenchmarkSchedule schedule, List<ModelConfig> models, List<Result> rows) {
        return new BenchmarkReport(id, completed.minusSeconds(10), completed, hash, scorer, original.dataset(),
                models, rows, List.of(), schedule, List.of());
    }
}
