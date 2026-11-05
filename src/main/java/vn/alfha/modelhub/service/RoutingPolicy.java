package vn.alfha.modelhub.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.alfha.modelhub.dto.BenchmarkDataset;
import vn.alfha.modelhub.dto.BenchmarkReport;
import vn.alfha.modelhub.dto.BenchmarkReport.Result;

public final class RoutingPolicy {
    private static final Thresholds GATES = new Thresholds(3, 30, 24, 80, 5, 14);
    private static final Set<String> PROVIDER_FAILURES = Set.of(
            "RATE_LIMITED", "TIMEOUT", "CONNECTION_FAILURE", "PROVIDER_ERROR", "INVALID_PROVIDER_RESPONSE");
    private static final Comparator<Candidate> ID_ORDER = Comparator.comparing(Candidate::provider)
            .thenComparing(Candidate::model);

    record Candidate(String model, String provider, String timeout) {
        boolean local() { return "ollama".equals(provider); }
        boolean evidenceEligible() {
            return local() || ("openrouter".equals(provider) && model.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:free"));
        }
    }
    record Thresholds(int minReports, int minAttempts, int minScored, int minReliabilityPercent,
            int correctnessBandPoints, int maxAgeDays) { }
    record Exclusion(UUID reportId, String reason) { }
    record Metrics(int reports, int distinctTasks, int attempted, int scored, int passed, int incorrect,
            int providerFailures, Double correctness, Double reliability, Double medianLatencyMs) { }
    record Explanation(String model, String provider, Metrics metrics, List<String> reasons) { }
    public record Decision(String profile, String policyVersion, Instant asOf, String datasetId,
            String datasetVersion, String datasetSha256, Instant evidenceCutoff,
            String outcome, String selectedModel, String reason, Thresholds thresholds, List<UUID> reportIds,
            List<Exclusion> exclusions, List<Explanation> candidates) { }

    Decision preview(Instant now, Instant cutoff, BenchmarkDataset dataset, String hash,
            List<Candidate> configured, List<BenchmarkReport> reports, List<Exclusion> unavailable) {
        var candidates = configured.stream().sorted(ID_ORDER).toList();
        var paired = candidates.stream().filter(Candidate::evidenceEligible).toList();
        var tasks = new HashSet<String>();
        dataset.tasks().forEach(task -> tasks.add(task.id()));
        var exclusions = new ArrayList<>(unavailable);
        var valid = new ArrayList<BenchmarkReport>();
        var reportCounts = new HashMap<UUID, Integer>();
        var executionCounts = new HashMap<UUID, Integer>();
        reports.forEach(report -> reportCounts.merge(report.id(), 1, Integer::sum));
        for (var report : reports) {
            String rejection = reportCounts.get(report.id()) > 1 ? "DUPLICATE_REPORT"
                    : reject(report, now, cutoff, dataset, hash, paired, tasks);
            if (rejection != null) {
                exclusions.add(new Exclusion(report.id(), rejection));
            } else {
                valid.add(report);
                report.results().forEach(row -> executionCounts.merge(row.execution().requestId(), 1, Integer::sum));
            }
        }
        valid.removeIf(report -> {
            boolean duplicate = report.results().stream().anyMatch(row -> executionCounts.get(row.execution().requestId()) > 1);
            if (duplicate) exclusions.add(new Exclusion(report.id(), "DUPLICATE_EXECUTION"));
            return duplicate;
        });
        var explanations = candidates.stream().map(candidate -> explain(candidate, valid, tasks.size())).toList();
        var eligible = explanations.stream().filter(candidate -> candidate.reasons().isEmpty()).toList();
        var selected = exclusions.stream().anyMatch(e -> "EVIDENCE_UNAVAILABLE".equals(e.reason()))
                ? null : rank(eligible);
        String outcome = "EVIDENCE_SELECTED";
        String reason = eligible.size() == 1 ? "ONLY_ELIGIBLE_CANDIDATE" : "CORRECTNESS_BAND_THEN_LATENCY";
        String model;
        if (selected == null) {
            model = candidates.stream().filter(Candidate::local).map(Candidate::model).findFirst().orElse(null);
            outcome = model == null ? "NO_ROUTE" : "FALLBACK_LOCAL";
            reason = fallbackReason(cutoff, reports, valid, exclusions);
        } else {
            model = selected.model();
        }
        return new Decision("basic", "basic-preview-v1", now, dataset.id(), dataset.version(), hash, cutoff,
                outcome, model, reason, GATES,
                valid.stream().map(BenchmarkReport::id).sorted().toList(),
                exclusions.stream().distinct().sorted(Comparator.comparing(Exclusion::reportId,
                        Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(Exclusion::reason)).toList(),
                explanations);
    }

    private String reject(BenchmarkReport report, Instant now, Instant cutoff, BenchmarkDataset dataset,
            String hash, List<Candidate> paired, Set<String> tasks) {
        if (report.id() == null) return "INVALID_REPORT_ID";
        if (cutoff == null) return "CUTOFF_REQUIRED";
        var completed = report.completedAt();
        if (completed == null || report.startedAt() == null || report.startedAt().isAfter(completed)
                || completed.isAfter(now)) return "INVALID_REPORT_TIME";
        if (report.startedAt().isBefore(cutoff) || completed.isBefore(now.minus(Duration.ofDays(GATES.maxAgeDays()))))
            return "STALE_EVIDENCE";
        if (!dataset.equals(report.dataset()) || !hash.equals(report.datasetSha256())
                || !"objective-v1".equals(report.scoringVersion())) return "DATASET_OR_SCORER_MISMATCH";
        if (report.schedule() != null || !report.skippedCloudTasks().isEmpty()) return "INCOMPATIBLE_SCHEDULE";
        if (paired.size() != 2 || report.models() == null || report.models().size() != paired.size()
                || report.models().stream().anyMatch(java.util.Objects::isNull)) return "CONFIGURATION_MISMATCH";
        for (var candidate : paired) {
            if (report.models().stream().noneMatch(model -> candidate.model().equals(model.model())
                    && candidate.provider().equals(model.provider()) && candidate.timeout().equals(model.timeout())))
                return "CONFIGURATION_MISMATCH";
        }
        if (report.results() == null || report.results().size() != tasks.size() * paired.size())
            return "INCOMPLETE_REPORT";
        var modelsByTask = new HashMap<String, Set<String>>();
        for (var row : report.results()) {
            if (!validRow(row) || !tasks.contains(row.taskId())) return "INVALID_RESULT";
            var model = row.execution().model();
            if (paired.stream().noneMatch(candidate -> candidate.model().equals(model))
                    || !modelsByTask.computeIfAbsent(row.taskId(), ignored -> new HashSet<>()).add(model))
                return "INVALID_RESULT";
        }
        return null;
    }

    private boolean validRow(Result row) {
        if (row == null || row.execution() == null || row.execution().requestId() == null
                || row.execution().durationMs() < 0 || row.codingEvaluation() != null
                || row.attempts().size() != 1 || !row.execution().equals(row.attempts().getFirst())) return false;
        var execution = row.execution();
        if ("SUCCESS".equals(execution.status()))
            return execution.error() == null && execution.response() != null
                    && ("PASS".equals(row.score()) || "FAIL".equals(row.score()));
        return "FAILED".equals(execution.status()) && "NOT_SCORED".equals(row.score())
                && execution.response() == null
                && execution.error() != null && execution.error().code() != null
                && PROVIDER_FAILURES.contains(execution.error().code());
    }

    private Explanation explain(Candidate candidate, List<BenchmarkReport> reports, int taskCount) {
        var rows = reports.stream().flatMap(report -> report.results().stream())
                .filter(row -> candidate.model().equals(row.execution().model())).toList();
        int passed = (int) rows.stream().filter(row -> "PASS".equals(row.score())).count();
        var latencies = rows.stream().filter(row -> !"NOT_SCORED".equals(row.score()))
                .map(row -> row.execution().durationMs()).sorted().toList();
        int scored = latencies.size();
        int attempts = rows.size();
        var metrics = new Metrics(attempts == 0 ? 0 : reports.size(), attempts == 0 ? 0 : taskCount,
                attempts, scored, passed, scored - passed, attempts - scored,
                scored == 0 ? null : (double) passed / scored,
                attempts == 0 ? null : (double) scored / attempts, median(latencies));
        var reasons = new ArrayList<String>();
        if (!candidate.evidenceEligible()) reasons.add("MODEL_NOT_ELIGIBLE");
        else if (!candidate.local()) reasons.add("CLOUD_NOT_VERIFIED");
        if (metrics.reports() < GATES.minReports() || attempts < GATES.minAttempts()) reasons.add("INSUFFICIENT_REPORTS");
        if (scored < GATES.minScored()) reasons.add("INSUFFICIENT_SCORED");
        if (attempts > 0 && 100L * scored < (long) GATES.minReliabilityPercent() * attempts)
            reasons.add("LOW_PROVIDER_RELIABILITY");
        return new Explanation(candidate.model(), candidate.provider(), metrics, List.copyOf(reasons));
    }

    private Double median(List<Long> sorted) {
        if (sorted.isEmpty()) return null;
        return sorted.get((sorted.size() - 1) / 2) / 2.0 + sorted.get(sorted.size() / 2) / 2.0;
    }

    static Explanation rank(List<Explanation> eligible) {
        var best = eligible.stream().max((a, b) -> correctness(a.metrics(), b.metrics())).orElse(null);
        if (best == null) return null;
        var top = best.metrics();
        return eligible.stream().filter(candidate -> {
            var stats = candidate.metrics();
            long deficit = (long) top.passed() * stats.scored() - (long) stats.passed() * top.scored();
            return 100 * deficit <= (long) GATES.correctnessBandPoints() * top.scored() * stats.scored();
        }).min(Comparator.comparing((Explanation entry) -> entry.metrics().medianLatencyMs())
                .thenComparing((a, b) -> correctness(b.metrics(), a.metrics()))
                .thenComparing((a, b) -> Long.compare((long) b.metrics().scored() * a.metrics().attempted(),
                        (long) a.metrics().scored() * b.metrics().attempted()))
                .thenComparing(Comparator.comparingInt((Explanation entry) -> entry.metrics().scored()).reversed())
                .thenComparing(Explanation::provider).thenComparing(Explanation::model)).orElseThrow();
    }

    private static int correctness(Metrics a, Metrics b) {
        return Long.compare((long) a.passed() * b.scored(), (long) b.passed() * a.scored());
    }

    private String fallbackReason(Instant cutoff, List<BenchmarkReport> reports, List<BenchmarkReport> valid,
            List<Exclusion> exclusions) {
        if (cutoff == null) return "CUTOFF_REQUIRED";
        if (exclusions.stream().anyMatch(e -> "EVIDENCE_UNAVAILABLE".equals(e.reason()))) return "EVIDENCE_UNAVAILABLE";
        if (reports.isEmpty() && exclusions.isEmpty()) return "NO_EVIDENCE";
        if (valid.isEmpty() && !reports.isEmpty() && exclusions.stream().allMatch(e -> "STALE_EVIDENCE".equals(e.reason())))
            return "STALE_EVIDENCE";
        return "NO_ELIGIBLE_EVIDENCE";
    }
}
