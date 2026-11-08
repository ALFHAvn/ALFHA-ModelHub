package vn.alfha.modelhub.service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.dto.BenchmarkReport.*;
import vn.alfha.modelhub.dto.CodingEvaluation.Outcome;
import vn.alfha.modelhub.repository.BenchmarkRepository;

@Service
public class BenchmarkService {
    private final BenchmarkCatalog catalog;
    private final GenerationService generation;
    private final ComparisonService comparison;
    private final ObjectiveScorer scorer;
    private final DockerCodingEvaluator codingEvaluator;
    private final ModelRegistry registry;
    private final BenchmarkRepository repository;
    private final String localTimeout;
    private final String cloudTimeout;
    private final BenchmarkDeployment deployment;
    private final Semaphore slot = new Semaphore(1);

    public BenchmarkService(BenchmarkCatalog catalog, GenerationService generation, ComparisonService comparison,
            ObjectiveScorer scorer, DockerCodingEvaluator codingEvaluator, ModelRegistry registry,
            ObjectProvider<BenchmarkRepository> repository,
            @Value("${modelhub.ollama.timeout}") String localTimeout,
            @Value("${modelhub.openrouter.timeout}") String cloudTimeout, BenchmarkDeployment deployment) {
        this.catalog = catalog; this.generation = generation; this.comparison = comparison;
        this.scorer = scorer; this.codingEvaluator = codingEvaluator;
        this.registry = registry; this.repository = repository.getIfAvailable();
        this.localTimeout = localTimeout; this.cloudTimeout = cloudTimeout;
        this.deployment = deployment;
    }
    public BenchmarkReport run(BenchmarkRequest request) {
        return run(request, RawJavaFormat.STANDARD);
    }
    public BenchmarkReport runStrictRaw(BenchmarkRequest request) {
        return run(request, RawJavaFormat.BOUNDARY);
    }
    public BenchmarkReport runStrictRawV2(BenchmarkRequest request) {
        return run(request, RawJavaFormat.ENVELOPE);
    }
    private BenchmarkReport run(BenchmarkRequest request, RawJavaFormat format) {
        boolean strictRaw = format != RawJavaFormat.STANDARD;
        if (request == null || request.models() == null
                || request.models().isEmpty() || request.models().size() > 2 || request.models().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.models()).size() != request.models().size()
                || request.models().contains("openrouter/free"))
            throw new IllegalArgumentException("Choose a dataset and one or two distinct fixed model identifiers");
        var selected = List.copyOf(request.models());
        var dataset = catalog.dataset(request.datasetId(), request.datasetVersion());
        boolean coding = dataset.tasks().stream().anyMatch(task -> task.rule() == BenchmarkDataset.Rule.JAVA_CODING);
        if (strictRaw && (!"java-coding".equals(dataset.id()) || request.schedule() != null))
            throw new IllegalArgumentException("Strict raw evaluation requires the Java coding dataset and local models");
        if (coding && "2".equals(dataset.version()) && !strictRaw)
            throw new IllegalArgumentException("The raw-prompt variant requires strict raw evaluation");
        if (coding && !codingEvaluator.enabled()) throw new CodingDisabledException();
        for (String model : selected) {
            generation.validate(new GenerateRequest(model, "validation"));
            if (strictRaw) generation.requireOllama(model);
        }
        if (repository == null) throw new IllegalStateException("Benchmark persistence is required");
        if (!slot.tryAcquire()) throw new BusyException();
        try {
            // Fail before model calls if the report table/database is already unavailable.
            repository.find(UUID.randomUUID());
            Instant started = Instant.now();
            var configs = selected.stream().map(model -> {
                String provider = registry.models().stream().filter(m -> m.model().equals(model)).findFirst().orElseThrow().provider();
                return new ModelConfig(model, provider, provider.equals("ollama") ? localTimeout : cloudTimeout,
                        "Provider defaults; non-streaming; sampling settings and model weights are not pinned");
            }).toList();
            var cloudScheduler = request.schedule() == null ? null : new BenchmarkCloudScheduler(request.schedule());
            var results = new ArrayList<Result>();
            var skippedCloudTasks = new ArrayList<String>();
            for (var task : dataset.tasks()) {
                List<List<GenerateResponse>> executions;
                if (!strictRaw && cloudScheduler == null && selected.size() == 2) {
                    executions = comparison.compare(new CompareRequest(selected, task.prompt())).results()
                            .stream().map(List::of).toList();
                } else {
                    var jobs = configs.stream().<java.util.function.Supplier<List<GenerateResponse>>>map(config -> () -> {
                        var input = new GenerateRequest(config.model(), task.prompt());
                        if (strictRaw) return List.of(generation.generateInstalledRaw(input));
                        if (cloudScheduler != null && "openrouter".equals(config.provider()))
                            return cloudScheduler.execute(() -> generation.generate(input));
                        return List.of(generation.generate(input));
                    }).toList();
                    executions = jobs.size() == 1 ? List.of(jobs.getFirst().get())
                            : ComparisonService.runPair(jobs.get(0), jobs.get(1));
                }
                for (var attempts : executions) {
                    if (attempts.isEmpty()) {
                        skippedCloudTasks.add(task.id());
                        continue;
                    }
                    var execution = attempts.getLast();
                    CodingEvaluation evaluation = null;
                    String score = "NOT_SCORED";
                    if ("SUCCESS".equals(execution.status())) {
                        if (task.rule() == BenchmarkDataset.Rule.JAVA_CODING) {
                            evaluation = codingEvaluator.evaluate(task, dataset.version(), execution.response(), format);
                            score = switch (evaluation.outcome()) {
                                case PASS -> "PASS";
                                case INFRASTRUCTURE_ERROR -> "NOT_SCORED";
                                default -> "FAIL";
                            };
                        } else {
                            score = scorer.passes(task, execution.response()) ? "PASS" : "FAIL";
                        }
                    }
                    results.add(new Result(task.id(), execution, score, attempts, evaluation));
                }
            }
            String scoringVersion = coding ? format.scoringVersion : "objective-v1";
            var report = new BenchmarkReport(UUID.randomUUID(), started, Instant.now(),
                    catalog.hash(dataset.id(), dataset.version()), scoringVersion,
                    dataset, configs, List.copyOf(results), selected.stream().map(model -> summarize(model, results, coding, strictRaw)).toList(),
                    request.schedule(), skippedCloudTasks, deployment.id());
            repository.save(report);
            return report;
        } finally { slot.release(); }
    }
    private Summary summarize(String model, List<Result> results, boolean coding, boolean strictRaw) {
        var runs = results.stream().filter(r -> r.execution().model().equals(model)).toList();
        int successful = (int) runs.stream().filter(r -> !"NOT_SCORED".equals(r.score())).count();
        int passed = (int) runs.stream().filter(r -> r.score().equals("PASS")).count();
        double totalTime = runs.stream().filter(r -> !"NOT_SCORED".equals(r.score()))
                .mapToLong(r -> r.execution().durationMs()).sum();
        return new Summary(model, runs.size(), successful, runs.size() - successful, passed, successful - passed,
                successful == 0 ? null : (double) passed / successful, runs.isEmpty() ? 0 : (double) passed / runs.size(),
                successful == 0 ? null : totalTime / successful, coding ? summarizeCoding(runs, strictRaw) : null);
    }

    static CodingSummary summarizeCoding(List<Result> runs, boolean strictRaw) {
        var outcomes = new EnumMap<Outcome, Integer>(Outcome.class);
        var executionErrors = new TreeMap<String, Integer>();
        for (var run : runs) {
            if (run.codingEvaluation() != null) {
                outcomes.merge(run.codingEvaluation().outcome(), 1, Integer::sum);
            } else if (run.execution().error() != null) {
                executionErrors.merge(run.execution().error().code(), 1, Integer::sum);
            }
        }
        int invalid = outcomes.getOrDefault(Outcome.INVALID_SUBMISSION, 0);
        int infrastructure = outcomes.getOrDefault(Outcome.INFRASTRUCTURE_ERROR, 0);
        int formatRejected = outcomes.getOrDefault(Outcome.FORMAT_ERROR, 0);
        outcomes.keySet().removeAll(EnumSet.of(Outcome.INVALID_SUBMISSION, Outcome.INFRASTRUCTURE_ERROR, Outcome.FORMAT_ERROR));
        int sandboxVerdicts = outcomes.values().stream().mapToInt(Integer::intValue).sum();
        var rawFormat = strictRaw ? new RawFormatSummary(sandboxVerdicts + infrastructure + formatRejected, formatRejected) : null;
        return new CodingSummary(sandboxVerdicts, Map.copyOf(outcomes), invalid, infrastructure, Map.copyOf(executionErrors), rawFormat);
    }
    public BenchmarkReport find(UUID id) {
        if (repository == null) throw new IllegalStateException("Benchmark persistence is required");
        return repository.find(id).orElseThrow(MissingException::new);
    }
    public static class BusyException extends RuntimeException { }
    public static class MissingException extends RuntimeException { }
    public static class CodingDisabledException extends RuntimeException { }
}
