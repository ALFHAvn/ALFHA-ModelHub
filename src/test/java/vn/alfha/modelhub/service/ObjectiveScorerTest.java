package vn.alfha.modelhub.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.BenchmarkDataset.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectiveScorerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectiveScorer scorer = new ObjectiveScorer(mapper);
    @Test void exactAnswersIgnoreOnlySurroundingWhitespace() {
        var task = new Task("id", "prompt", Rule.EXACT, "Paris", null);
        assertTrue(scorer.passes(task, " Paris\n"));
        for (String response : new String[] {"paris", "Paris.", "The answer is Paris", ""}) assertFalse(scorer.passes(task, response));
        assertFalse(scorer.passes(task, null));
    }
    @Test void requiresAnEntireJsonObjectWithoutMarkdownOrTrailingTokens() {
        var task = new Task("id", "prompt", Rule.JSON_OBJECT, null, null);
        assertTrue(scorer.passes(task, " {\"book\":\"test\"} \n"));
        for (String response : new String[] {"[]", "null", "42", "{", "```json\n{}\n```", "{} {}", "{} trailing"})
            assertFalse(scorer.passes(task, response), response);
    }
    @Test void checksRequiredFieldsIncludingTypesArraysAndExplicitNull() {
        var fields = Map.of("count", mapper.readTree("3"), "value", mapper.readTree("null"), "items", mapper.readTree("[1,2]"));
        var task = new Task("id", "prompt", Rule.JSON_FIELDS, null, fields);
        assertTrue(scorer.passes(task, "{\"count\":3,\"value\":null,\"items\":[1,2],\"extra\":true}"));
        assertFalse(scorer.passes(task, "{\"count\":\"3\",\"value\":null,\"items\":[1,2]}"));
        assertFalse(scorer.passes(task, "{\"count\":3,\"items\":[1,2]}"));
        assertFalse(scorer.passes(task, "{\"count\":3,\"value\":null,\"items\":[2,1]}"));
    }
}
