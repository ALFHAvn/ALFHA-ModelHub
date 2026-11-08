package vn.alfha.modelhub.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

    @Test void rejectsBoundaryFencesOnlyInStrictModeWithoutInvokingRunner() throws Exception {
        var mapper = new ObjectMapper();
        var task = new BenchmarkCatalog(mapper).dataset("java-coding", "1").tasks().getFirst();
        var evaluator = new DockerCodingEvaluator(mapper, "target/nonexistent-coding-runner.ps1");
        for (String source : new String[] { " \t```java\r\nclass Main {}\r\n```\r\n", "Here is the source:\n~~~java\nclass Main {}\n~~~" }) {
            var result = evaluator.evaluate(task, "1", source, true);
            assertEquals(Outcome.FORMAT_ERROR, result.outcome());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))), result.sourceSha256());
            assertNull(result.imageId());
            assertNull(result.timeoutSeconds());
            assertTrue(result.cases().isEmpty());
            assertEquals(Outcome.INFRASTRUCTURE_ERROR, evaluator.evaluate(task, "1", source).outcome());
        }
        String textBlock = "class Main { String text = \"\"\"\n```text\ninside Java\n```\n\"\"\"; }";
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, evaluator.evaluate(task, "1", textBlock, true).outcome());
        assertEquals(Outcome.INVALID_SUBMISSION, evaluator.evaluate(task, "1", " ", true).outcome());
    }
}
