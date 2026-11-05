package vn.alfha.modelhub.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.BenchmarkDataset;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCatalogTest {
    @Test void preservesV1IdentityAndChangesOnlyTheAmbiguousPromptInV2() throws Exception {
        var catalog = new BenchmarkCatalog(new ObjectMapper());
        assertEquals("6de7890d47a885fc17f703d0b1a07970395f66640906a783ffd2498de467917e", catalog.hash());
        assertEquals(catalog.dataset(), catalog.dataset(null));
        assertNotEquals(catalog.hash("1"), catalog.hash("2"));
        assertEquals("25e3bdbecce5b8e41b3e8acf4f896de3226940ac7bb981def7801a622bafe2c1", catalog.hash("2"));
        var first = catalog.dataset("1").tasks();
        var second = catalog.dataset("2").tasks();
        assertEquals(10, second.size());
        assertEquals(first.subList(0, 9), second.subList(0, 9));
        assertEquals(first.getLast().fields(), second.getLast().fields());
        assertEquals(first.getLast().rule(), second.getLast().rule());
        assertTrue(second.getLast().prompt().contains("literal JSON string \"empty\" (not an empty string)"));
        assertThrows(IllegalArgumentException.class, () -> catalog.dataset("unknown"));
    }

    @Test void scoresPilotAnswersUsingTheExistingRules() throws Exception {
        var mapper = new ObjectMapper();
        var catalog = new BenchmarkCatalog(mapper);
        var scorer = new ObjectiveScorer(mapper);
        var pilot = catalog.dataset("software-engineering", "1");
        var answers = java.util.Map.of(
                "java-virtual-dispatch", "42",
                "sql-null-aggregation", "{\"row_count\":3,\"non_null_count\":2,\"distinct_count\":1}",
                "debug-loop-boundary", "<",
                "reasoning-list-alias", "{\"a\":[9,2],\"b\":[3]}",
                "api-idempotency-contract", "{\"statuses\":[201,201,409],\"order_count\":1}",
                "structured-incident-output", "{\"request_id\":\"r-17\",\"retries\":2,\"resolved\":false,\"error\":null}");
        assertEquals(answers.keySet(), pilot.tasks().stream().map(BenchmarkDataset.Task::id)
                .collect(java.util.stream.Collectors.toSet()));
        for (var task : pilot.tasks()) {
            assertTrue(scorer.passes(task, answers.get(task.id())), task.id());
            assertFalse(scorer.passes(task, "```\n" + answers.get(task.id()) + "\n```"), task.id());
        }
        assertNotEquals(catalog.hash("1"), catalog.hash("software-engineering", "1"));
        assertThrows(IllegalArgumentException.class, () -> catalog.dataset("software-engineering", "2"));
    }
}
