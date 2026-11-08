package vn.alfha.modelhub.dto;

import java.util.List;

public record CodingEvaluation(Outcome outcome, String sourceSha256, String imageId,
        Integer timeoutSeconds, long durationMs, List<CaseResult> cases) {
    public enum Outcome {
        PASS, WRONG_ANSWER, COMPILATION_ERROR, EXECUTION_ERROR, TIMEOUT, OUTPUT_LIMIT,
        INVALID_SUBMISSION, FORMAT_ERROR, INFRASTRUCTURE_ERROR
    }
    public record CaseResult(String id, Outcome outcome, long durationMs) { }
}
