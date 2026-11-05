package vn.alfha.modelhub.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import vn.alfha.modelhub.dto.*;

@Service
public class ComparisonService {
    private final GenerationService generation;
    public ComparisonService(GenerationService generation) { this.generation = generation; }

    public CompareResponse compare(CompareRequest request) {
        if (request == null || request.models() == null || request.models().size() != 2
                || request.models().get(0) == null || request.models().get(1) == null
                || request.models().get(0).equals(request.models().get(1)))
            throw new IllegalArgumentException("Select exactly two distinct configured models");
        // Validate both selections before starting any provider calls.
        var first = new GenerateRequest(request.models().get(0), request.prompt());
        var second = new GenerateRequest(request.models().get(1), request.prompt());
        generation.validate(first);
        generation.validate(second);
        // Each provider has its own request timeout; neither task cancels its peer on failure.
        return new CompareResponse(runPair(() -> generation.generate(first), () -> generation.generate(second)));
    }

    static <T> List<T> runPair(Supplier<T> first, Supplier<T> second) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = CompletableFuture.supplyAsync(first, executor);
            var secondResult = CompletableFuture.supplyAsync(second, executor);
            return List.of(firstResult.join(), secondResult.join());
        }
    }
}
