package vn.alfha.modelhub.service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.BenchmarkDataset;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCatalogTest {
    @Test void publishedFamilyInventoryMatchesCatalogSnapshots() throws Exception {
        var mapper = new ObjectMapper();
        var catalog = new BenchmarkCatalog(mapper);
        var inventory = mapper.readTree(Path.of("benchmarks/scenario-families.json").toFile());
        var snapshots = new HashSet<String>();
        var familiesByTask = new HashMap<String, String>();
        for (var entry : inventory) {
            String id = entry.path("id").asString();
            String version = entry.path("version").asString();
            assertTrue(snapshots.add(id + ":" + version));
            assertEquals(catalog.hash(id, version), entry.path("sha256").asString());
            var families = entry.path("families");
            var taskIds = catalog.dataset(id, version).tasks().stream().map(BenchmarkDataset.Task::id)
                    .collect(Collectors.toSet());
            assertEquals(taskIds, families.properties().stream().map(java.util.Map.Entry::getKey)
                    .collect(Collectors.toSet()));
            for (String task : taskIds) {
                String family = families.path(task).asString();
                assertTrue(family.matches("[a-z0-9][a-z0-9-]*"), task);
                assertEquals(family, familiesByTask.computeIfAbsent(id + ":" + task, ignored -> family));
            }
        }
        assertEquals(Set.of("basics:1", "basics:2", "software-engineering:1", "software-engineering:2",
                "java-coding:1", "java-coding:2"), snapshots);
    }

    @Test void rawJavaVariantChangesOnlyThePromptAndSnapshotIdentity() throws Exception {
        var catalog = new BenchmarkCatalog(new ObjectMapper());
        var baseline = catalog.dataset("java-coding", "1").tasks().getFirst();
        var treatment = catalog.dataset("java-coding", "2").tasks().getFirst();
        assertEquals(baseline.prompt() + " Return only the raw Java source. Do not include Markdown fences, a filename, or explanatory text.",
                treatment.prompt());
        assertEquals(baseline, new BenchmarkDataset.Task(treatment.id(), baseline.prompt(), treatment.rule(),
                treatment.expected(), treatment.fields(), treatment.cases()));
        assertNotEquals(catalog.hash("java-coding", "1"), catalog.hash("java-coding", "2"));
    }

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
