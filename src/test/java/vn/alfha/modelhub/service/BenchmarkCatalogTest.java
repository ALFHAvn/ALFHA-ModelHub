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
        assertThrows(IllegalArgumentException.class, () -> catalog.dataset("software-engineering", "3"));
    }

    @Test void expandedSuitePreservesPilotTasksAndScoresNewCases() throws Exception {
        var mapper = new ObjectMapper();
        var catalog = new BenchmarkCatalog(mapper);
        var scorer = new ObjectiveScorer(mapper);
        var pilot = catalog.dataset("software-engineering", "1");
        var suite = catalog.dataset("software-engineering", "2");
        assertEquals(pilot.tasks(), suite.tasks().subList(0, pilot.tasks().size()));
        assertNotEquals(catalog.hash(pilot.id(), pilot.version()), catalog.hash(suite.id(), suite.version()));
        assertEquals(24, suite.tasks().size());
        assertEquals(24, suite.tasks().stream().map(BenchmarkDataset.Task::id).distinct().count());
        for (String topic : java.util.List.of("java", "sql", "debug", "reasoning", "api", "structured")) {
            assertEquals(4, suite.tasks().stream().filter(task -> task.id().startsWith(topic + "-")).count(), topic);
        }
        var examples = java.util.Map.of(
                "java-finally", java.util.List.of("1", "2"),
                "sql-update-snapshot", java.util.List.of("{\"a\":5,\"b\":7}", "{\"a\":5,\"b\":10}"),
                "debug-binary-search-progress", java.util.List.of("mid + 1", "mid + 2"),
                "reasoning-short-circuit", java.util.List.of("{\"x\":1,\"ok\":true}", "{\"x\":2,\"ok\":true}"),
                "api-cursor-tiebreaker", java.util.List.of(
                        "{\"first_ids\":[1,2],\"cursor\":[10,2],\"next_ids\":[3,4]}",
                        "{\"first_ids\":[1,2],\"cursor\":[10],\"next_ids\":[4,5]}"),
                "structured-config-merge", java.util.List.of(
                        "{\"config\":{\"timeout_ms\":0,\"retries\":2,\"enabled\":false}}",
                        "{\"config\":{\"timeout_ms\":500,\"retries\":2,\"enabled\":true}}"));
        examples.forEach((id, answers) -> {
            var task = suite.tasks().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
            assertTrue(scorer.passes(task, answers.getFirst()), id);
            assertFalse(scorer.passes(task, answers.getLast()), id);
        });
    }
}
