package vn.alfha.modelhub.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.CodingEvaluation.Outcome;
import static org.junit.jupiter.api.Assertions.*;

class DockerCodingEvaluatorTest {
    @Test void boundsSourceInUtf8AndSeparatesRunnerUnavailability() throws Exception {
        var mapper = new ObjectMapper();
        var task = new BenchmarkCatalog(mapper).dataset("java-coding", "1").tasks().getFirst();
        var evaluator = new DockerCodingEvaluator(mapper, "target/nonexistent-coding-runner.ps1");
        assertTrue(evaluator.enabled());
        assertEquals(Outcome.INVALID_SUBMISSION, evaluator.evaluate(task, "1", "é".repeat(8193)).outcome());
        assertEquals(Outcome.INVALID_SUBMISSION, evaluator.evaluate(task, "1", " ").outcome());
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, evaluator.evaluate(task, "1", "class Main {}").outcome());
    }
}
