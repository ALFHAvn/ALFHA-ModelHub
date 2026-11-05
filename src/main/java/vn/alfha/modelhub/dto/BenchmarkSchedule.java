package vn.alfha.modelhub.dto;

public record BenchmarkSchedule(int minIntervalMs, int maxRetries) {
    public BenchmarkSchedule {
        if (minIntervalMs < 0 || minIntervalMs > 30_000 || maxRetries < 0 || maxRetries > 2)
            throw new IllegalArgumentException("Invalid benchmark schedule");
    }
}
