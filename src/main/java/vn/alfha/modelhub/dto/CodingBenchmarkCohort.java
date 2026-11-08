package vn.alfha.modelhub.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CodingBenchmarkCohort(String datasetId, String datasetVersion, String datasetSha256,
        String scoringVersion, String deploymentId, BenchmarkSchedule schedule, Grader grader,
        List<Run> reports, List<Model> models, List<TimelineEntry> timeline) {
    public record Request(List<UUID> reportIds) { }
    public record Discovery(UUID seedReportId, List<UUID> reportIds, int candidatesChecked, boolean windowLimited) { }
    public record Run(UUID id, Instant completedAt) { }
    public record SavedReport(UUID id, Instant completedAt, String datasetVersion, String scoringVersion) { }
    public record Grader(String imageId, Integer timeoutSeconds) { }
    public record Model(BenchmarkReport.ModelConfig config, int attempted, BenchmarkReport.CodingSummary coding) { }
    public record TimelineEntry(UUID reportId, Instant completedAt, String taskId, String model, UUID executionId,
            String executionStatus, FormatValidation formatValidation, CodingEvaluation.Outcome sandboxVerdict,
            Failure error, long executionDurationMs, Long gradingDurationMs, Integer sandboxTimeoutSeconds) { }
    public enum FormatValidation { NOT_CHECKED, PASSED, REJECTED }
    public record Failure(Stage stage, String code) { }
    public enum Stage { PROVIDER, GENERATION, HISTORY, SUBMISSION, FORMAT, GRADER, SANDBOX }
}
