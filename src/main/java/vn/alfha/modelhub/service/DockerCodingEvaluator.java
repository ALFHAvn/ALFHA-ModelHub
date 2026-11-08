package vn.alfha.modelhub.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.BenchmarkDataset.Task;
import vn.alfha.modelhub.dto.CodingEvaluation;
import vn.alfha.modelhub.dto.CodingEvaluation.CaseResult;
import vn.alfha.modelhub.dto.CodingEvaluation.Outcome;

@Component
public class DockerCodingEvaluator {
    private static final int CASE_TIMEOUT_SECONDS = 8;
    private final ObjectMapper mapper;
    private final Path script;
    private final String fixtureHash;

    public DockerCodingEvaluator(ObjectMapper mapper, @Value("${modelhub.coding.runner:}") String runner)
            throws IOException, NoSuchAlgorithmException {
        this.mapper = mapper;
        script = runner.isBlank() ? null : Path.of(runner).toAbsolutePath().normalize();
        try (var input = new ClassPathResource("benchmarks/java-coding-v1.json").getInputStream()) {
            fixtureHash = hash(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
    }

    public boolean enabled() { return script != null; }

    public CodingEvaluation evaluate(Task task, String version, String source) {
        return evaluate(task, version, source, false);
    }

    CodingEvaluation evaluate(Task task, String version, String source, boolean strictRaw) {
        long started = System.nanoTime();
        if (source == null || source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > 16_384)
            return failure(Outcome.INVALID_SUBMISSION, started);
        String runId = UUID.randomUUID().toString().replace("-", "");
        Path directory = null;
        Path input = null;
        Path output = null;
        Process process = null;
        boolean verified = false;
        try {
            if (strictRaw && hasBoundaryFence(source)) {
                return new CodingEvaluation(Outcome.FORMAT_ERROR, hash(source), null, null, elapsed(started), List.of());
            }
            if (script == null || !Files.isRegularFile(script)) return failure(Outcome.INFRASTRUCTURE_ERROR, started);
            Path target = script.getParent().getParent().resolve("target");
            directory = Files.createTempDirectory(Files.createDirectories(target), "coding-");
            input = directory.resolve("Main.java");
            output = directory.resolve("report.json");
            Files.writeString(input, source, StandardCharsets.UTF_8);
            process = new ProcessBuilder("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
                    script.toString(), "-SourcePath", input.toString(), "-TimeoutSeconds",
                    Integer.toString(CASE_TIMEOUT_SECONDS), "-RunId", runId)
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            if (!process.waitFor(90, TimeUnit.SECONDS) || process.exitValue() != 0 || Files.size(output) > 16_384)
                return failure(Outcome.INFRASTRUCTURE_ERROR, started);

            RunnerReport report = mapper.readValue(Files.readString(output), RunnerReport.class);
            String sourceHash = hash(source);
            if (report == null || !task.id().equals(report.taskId()) || !version.equals(report.taskVersion())
                    || !fixtureHash.equalsIgnoreCase(report.taskSha256())
                    || !sourceHash.equalsIgnoreCase(report.sourceSha256())
                    || report.imageId() == null || !report.imageId().matches("sha256:[a-f0-9]{64}")
                    || !Objects.equals(report.timeoutSeconds(), CASE_TIMEOUT_SECONDS)
                    || report.results() == null
                    || !report.results().stream().map(CaseResult::id).toList()
                            .equals(task.cases().stream().map(c -> c.id()).toList())
                    || report.results().stream().anyMatch(c -> c.outcome() == null || c.durationMs() < 0
                            || c.outcome() == Outcome.INFRASTRUCTURE_ERROR || c.outcome() == Outcome.INVALID_SUBMISSION
                            || c.outcome() == Outcome.FORMAT_ERROR))
                return failure(Outcome.INFRASTRUCTURE_ERROR, started);
            verified = true;
            Outcome outcome = report.results().stream().map(CaseResult::outcome)
                    .filter(status -> status != Outcome.PASS).findFirst().orElse(Outcome.PASS);
            return new CodingEvaluation(outcome, sourceHash, report.imageId(), CASE_TIMEOUT_SECONDS,
                    elapsed(started), report.results());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(Outcome.INFRASTRUCTURE_ERROR, started);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return failure(Outcome.INFRASTRUCTURE_ERROR, started);
        } finally {
            if (process != null && !verified) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                removeContainers(task, runId);
            }
            delete(input);
            delete(output);
            delete(directory);
        }
    }

    private static boolean hasBoundaryFence(String source) {
        var lines = source.strip().lines().toList();
        return startsFence(lines.getFirst()) || startsFence(lines.getLast().stripLeading());
    }

    private static boolean startsFence(String line) {
        return line.startsWith("```") || line.startsWith("~~~");
    }

    private static void removeContainers(Task task, String runId) {
        // A killed script may not reach its finally block. Use only this run's generated names.
        boolean interrupted = Thread.interrupted();
        Process cleanup = null;
        try {
            var command = new ArrayList<>(List.of("docker", "rm", "--force"));
            for (var test : task.cases()) command.add("modelhub-coding-" + runId + "-" + test.id());
            cleanup = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            cleanup.getOutputStream().close();
            cleanup.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            // Docker may be unavailable, which is already reported as an infrastructure failure.
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (cleanup != null && cleanup.isAlive()) cleanup.destroyForcibly();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void delete(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (IOException ignored) { /* A stopped process may still be releasing its file handles. */ }
    }

    private static String hash(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    private static CodingEvaluation failure(Outcome outcome, long started) {
        return new CodingEvaluation(outcome, null, null, null, elapsed(started), List.of());
    }

    private record RunnerReport(String taskId, String taskVersion, String taskSha256, String sourceSha256,
            String imageId, Integer timeoutSeconds, List<CaseResult> results) { }
}
