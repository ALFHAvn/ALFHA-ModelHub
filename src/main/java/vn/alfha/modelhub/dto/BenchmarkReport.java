package vn.alfha.modelhub.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BenchmarkReport(UUID id, Instant startedAt, Instant completedAt, String datasetSha256,
        String scoringVersion, BenchmarkDataset dataset, List<ModelConfig> models,
        List<Result> results, List<Summary> summaries) {
    public record ModelConfig(String model, String provider, String timeout, String inferenceSettings) { }
    public record Result(String taskId, GenerateResponse execution, String score) { }
    public record Summary(String model, int attempted, int successful, int failed, int passed,
            int scoringFailures, Double passRateAmongSuccessful, double passRateAllAttempts,
            Double averageSuccessfulDurationMs) { }
}
