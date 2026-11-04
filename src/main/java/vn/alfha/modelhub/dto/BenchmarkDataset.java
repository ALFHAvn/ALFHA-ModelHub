package vn.alfha.modelhub.dto;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record BenchmarkDataset(String id, String version, List<Task> tasks) {
    public record Task(String id, String prompt, Rule rule, String expected, Map<String, JsonNode> fields) { }
    public enum Rule { EXACT, JSON_OBJECT, JSON_FIELDS }
}
