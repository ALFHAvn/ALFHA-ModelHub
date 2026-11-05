package vn.alfha.modelhub.dto;

import java.util.List;

public record BenchmarkRequest(String datasetId, String datasetVersion, List<String> models, BenchmarkSchedule schedule) {
    public BenchmarkRequest(String datasetId, String datasetVersion, List<String> models) {
        this(datasetId, datasetVersion, models, null);
    }
    public BenchmarkRequest(String datasetId, List<String> models) { this(datasetId, "1", models); }
}
