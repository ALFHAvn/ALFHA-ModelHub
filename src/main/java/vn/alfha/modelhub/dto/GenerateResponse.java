package vn.alfha.modelhub.dto;

public record GenerateResponse(String model, String response, long durationMs, String status, ApiError error) { }
