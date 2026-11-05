package vn.alfha.modelhub.service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.dto.BenchmarkReport.*;
import vn.alfha.modelhub.repository.BenchmarkRepository;

@Service
public class BenchmarkService {
    private final BenchmarkCatalog catalog;
    private final GenerationService generation;
    private final ComparisonService comparison;
    private final ObjectiveScorer scorer;
    private final ModelRegistry registry;
    private final BenchmarkRepository repository;
    private final String localTimeout;
    private final String cloudTimeout;
    private final Semaphore slot = new Semaphore(1);

    public BenchmarkService(BenchmarkCatalog catalog, GenerationService generation, ComparisonService comparison,
            ObjectiveScorer scorer, ModelRegistry registry, ObjectProvider<BenchmarkRepository> repository,
            @Value("${modelhub.ollama.timeout}") String localTimeout,
            @Value("${modelhub.openrouter.timeout}") String cloudTimeout) {
        this.catalog = catalog; this.generation = generation; this.comparison = comparison;
        this.scorer = scorer; this.registry = registry; this.repository = repository.getIfAvailable();
        this.localTimeout = localTimeout; this.cloudTimeout = cloudTimeout;
    }
    public BenchmarkReport run(BenchmarkRequest request) {
        if (request == null || !catalog.dataset().id().equals(request.datasetId()) || request.models() == null
                || request.models().isEmpty() || request.models().size() > 2 || request.models().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.models()).size() != request.models().size()
                || request.models().contains("openrouter/free"))
            throw new IllegalArgumentException("Choose basics and one or two distinct fixed model identifiers");
        var selected = List.copyOf(request.models());
        var dataset = catalog.dataset(request.datasetVersion());
        for (String model : selected) generation.validate(new GenerateRequest(model, "validation"));
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
                if (cloudScheduler == null && selected.size() == 2) {
                    executions = comparison.compare(new CompareRequest(selected, task.prompt())).results()
                            .stream().map(List::of).toList();
                } else {
                    var jobs = configs.stream().<java.util.function.Supplier<List<GenerateResponse>>>map(config -> () -> {
                        var input = new GenerateRequest(config.model(), task.prompt());
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
                    String score = !execution.status().equals("SUCCESS") ? "NOT_SCORED"
                            : scorer.passes(task, execution.response()) ? "PASS" : "FAIL";
                    results.add(new Result(task.id(), execution, score, attempts));
                }
            }
            var report = new BenchmarkReport(UUID.randomUUID(), started, Instant.now(), catalog.hash(dataset.version()), "objective-v1",
                    dataset, configs, List.copyOf(results), selected.stream().map(model -> summarize(model, results)).toList(),
                    request.schedule(), skippedCloudTasks);
            repository.save(report);
            return report;
        } finally { slot.release(); }
    }
    private Summary summarize(String model, List<Result> results) {
        var runs = results.stream().filter(r -> r.execution().model().equals(model)).toList();
        int successful = (int) runs.stream().filter(r -> r.execution().status().equals("SUCCESS")).count();
        int passed = (int) runs.stream().filter(r -> r.score().equals("PASS")).count();
        double totalTime = runs.stream().filter(r -> r.execution().status().equals("SUCCESS")).mapToLong(r -> r.execution().durationMs()).sum();
        return new Summary(model, runs.size(), successful, runs.size() - successful, passed, successful - passed,
                successful == 0 ? null : (double) passed / successful, runs.isEmpty() ? 0 : (double) passed / runs.size(),
                successful == 0 ? null : totalTime / successful);
    }
    public BenchmarkReport find(UUID id) {
        if (repository == null) throw new IllegalStateException("Benchmark persistence is required");
        return repository.find(id).orElseThrow(MissingException::new);
    }
    public static class BusyException extends RuntimeException { }
    public static class MissingException extends RuntimeException { }
}
