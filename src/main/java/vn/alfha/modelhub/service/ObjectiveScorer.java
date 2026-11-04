package vn.alfha.modelhub.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import vn.alfha.modelhub.dto.BenchmarkDataset.Task;

@Component
public class ObjectiveScorer {
    private final ObjectMapper mapper;
    public ObjectiveScorer(ObjectMapper mapper) { this.mapper = mapper; }
    public boolean passes(Task task, String response) {
        if (response == null) return false;
        if (task.rule() == vn.alfha.modelhub.dto.BenchmarkDataset.Rule.EXACT)
            return response.strip().equals(task.expected());
        try {
            var body = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response);
            if (body == null || !body.isObject()) return false;
            if (task.rule() == vn.alfha.modelhub.dto.BenchmarkDataset.Rule.JSON_OBJECT) return true;
            return task.fields().entrySet().stream().allMatch(field ->
                    body.has(field.getKey()) && body.get(field.getKey()).equals(field.getValue()));
        } catch (tools.jackson.core.JacksonException e) { return false; }
    }
}
