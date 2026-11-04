package vn.alfha.modelhub.dto;

import java.util.List;

public record BenchmarkRequest(String datasetId, List<String> models) { }
