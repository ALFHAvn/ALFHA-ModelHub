package vn.alfha.modelhub.dto;

public record ApiError(String code, String message,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long retryAfterMs) {
    public ApiError(String code, String message) { this(code, message, null); }
}
