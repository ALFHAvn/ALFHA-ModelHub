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
            var result = evaluator.evaluate(task, "1", source, RawJavaFormat.BOUNDARY);
            assertEquals(Outcome.FORMAT_ERROR, result.outcome());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))), result.sourceSha256());
            assertNull(result.imageId());
            assertNull(result.timeoutSeconds());
            assertTrue(result.cases().isEmpty());
            assertEquals(Outcome.INFRASTRUCTURE_ERROR, evaluator.evaluate(task, "1", source).outcome());
        }
        String textBlock = "class Main { String text = \"\"\"\n```text\ninside Java\n```\n\"\"\"; }";
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, evaluator.evaluate(task, "1", textBlock, RawJavaFormat.BOUNDARY).outcome());
        assertEquals(Outcome.INVALID_SUBMISSION, evaluator.evaluate(task, "1", " ", RawJavaFormat.BOUNDARY).outcome());
    }

    @Test void rejectsProseEnvelopesBeforeTheRunnerOnlyInTheNewMode() throws Exception {
        var mapper = new ObjectMapper();
        var task = new BenchmarkCatalog(mapper).dataset("java-coding", "2").tasks().getFirst();
        var evaluator = new DockerCodingEvaluator(mapper, "target/nonexistent-coding-runner.ps1");
        for (String source : new String[] {
                "Certainly! Here is the Java source.\n```java\nclass Main {}\n```\nExplanation.",
                "\r\n**Below is the code.**\r\n~~~~~ java\r\nclass Main {}\r\n~~~~~\r\nExplanation.\r\n",
                "Here’s the solution.\r```\rpublic final class Main\r{}\r```\rExplanation." }) {
            var result = evaluator.evaluate(task, "2", source, RawJavaFormat.ENVELOPE);
            assertEquals(Outcome.FORMAT_ERROR, result.outcome());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8))), result.sourceSha256());
            assertTrue(result.cases().isEmpty());
            assertNull(result.imageId());
            assertEquals(Outcome.INFRASTRUCTURE_ERROR, evaluator.evaluate(task, "2", source, RawJavaFormat.BOUNDARY).outcome());
        }
        for (String source : new String[] {
                "class Main { String text = \"\"\"\nBelow is an example.\n````java\ninside Java\n````\n\"\"\"; }",
                "/*\nCertainly! Example follows.\n```java\nclass Main {}\n```\nExplanation.\n*/\nclass Main {}",
                "Here is the source.\n```\nprint('class Main {}')\n```\nExplanation.",
                "Here is the source.\n```\nclass Main\nis an entry point.\n```\nExplanation.",
                "Below is the source.\n```" + " ".repeat(16_000) + "unknown\nclass Main {}\n```\nExplanation.",
                "Below is the source.\n````java\nclass Main {}\n```\nExplanation.",
                "Below is the source.\n```java\nclass Main {}\n```\n```java\nclass Main {}\n```\nExplanation." }) {
            assertEquals(Outcome.INFRASTRUCTURE_ERROR, evaluator.evaluate(task, "2", source, RawJavaFormat.ENVELOPE).outcome());
        }
    }
}
