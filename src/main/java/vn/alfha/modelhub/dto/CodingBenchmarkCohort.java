package vn.alfha.modelhub.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CodingBenchmarkCohort(String datasetId, String datasetVersion, String datasetSha256,
        String scoringVersion, String deploymentId, BenchmarkSchedule schedule, Grader grader, List<Run> reports, List<Model> models) {
    public record Request(List<UUID> reportIds) { }
    public record Run(UUID id, Instant completedAt) { }
    public record Grader(String imageId, Integer timeoutSeconds) { }
    public record Model(BenchmarkReport.ModelConfig config, int attempted, BenchmarkReport.CodingSummary coding) { }
}
