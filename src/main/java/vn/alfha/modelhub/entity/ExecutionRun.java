package vn.alfha.modelhub.entity;

import java.time.Instant;
import java.util.UUID;
import vn.alfha.modelhub.dto.ApiError;

public record ExecutionRun(UUID requestId, String model, String prompt, String response,
        long durationMs, String status, ApiError error, Instant timestamp) { }
