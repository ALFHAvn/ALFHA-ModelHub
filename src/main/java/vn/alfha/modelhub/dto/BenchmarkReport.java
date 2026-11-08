package vn.alfha.modelhub.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public record BenchmarkReport(UUID id, Instant startedAt, Instant completedAt, String datasetSha256,
        String scoringVersion, BenchmarkDataset dataset, List<ModelConfig> models,
        List<Result> results, List<Summary> summaries, BenchmarkSchedule schedule, List<String> skippedCloudTasks,
        @JsonInclude(JsonInclude.Include.NON_NULL) String deploymentId) {
    public BenchmarkReport {
        skippedCloudTasks = skippedCloudTasks == null ? List.of() : List.copyOf(skippedCloudTasks);
    }
    public record ModelConfig(String model, String provider, String timeout, String inferenceSettings) { }
    public record Result(String taskId, GenerateResponse execution, String score, List<GenerateResponse> attempts,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            CodingEvaluation codingEvaluation) {
        public Result {
            attempts = attempts == null ? List.of(execution) : List.copyOf(attempts);
        }
    }
    public record Summary(String model, int attempted, int successful, int failed, int passed,
            int scoringFailures, Double passRateAmongSuccessful, double passRateAllAttempts,
            Double averageSuccessfulDurationMs, @JsonInclude(JsonInclude.Include.NON_NULL) CodingSummary coding) {
        public Summary(String model, int attempted, int successful, int failed, int passed,
                int scoringFailures, Double passRateAmongSuccessful, double passRateAllAttempts,
                Double averageSuccessfulDurationMs) {
            this(model, attempted, successful, failed, passed, scoringFailures, passRateAmongSuccessful,
                    passRateAllAttempts, averageSuccessfulDurationMs, null);
        }
    }
    public record CodingSummary(int sandboxVerdicts, Map<CodingEvaluation.Outcome, Integer> sandboxOutcomes,
            int invalidSubmissions, int graderInfrastructureFailures, Map<String, Integer> executionErrors,
            @JsonInclude(JsonInclude.Include.NON_NULL) RawFormatSummary rawFormat) { }
    public record RawFormatSummary(int checked, int rejected) { }
}
