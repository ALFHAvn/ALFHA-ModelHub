package vn.alfha.modelhub.dto;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record BenchmarkDataset(String id, String version, List<Task> tasks) {
    public record Task(String id, String prompt, Rule rule, String expected, Map<String, JsonNode> fields,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<CodingCase> cases) {
        public Task(String id, String prompt, Rule rule, String expected, Map<String, JsonNode> fields) {
            this(id, prompt, rule, expected, fields, null);
        }
    }
    public record CodingCase(String id, List<String> args, String expected) { }
    public enum Rule { EXACT, JSON_OBJECT, JSON_FIELDS, JAVA_CODING }
}
