package vn.alfha.modelhub.dto;

public record GenerateResponse(java.util.UUID requestId, String model, String response, long durationMs, String status, ApiError error) {
    public GenerateResponse(String model, String response, long durationMs, String status, ApiError error) {
        this(java.util.UUID.randomUUID(), model, response, durationMs, status, error);
    }
}
