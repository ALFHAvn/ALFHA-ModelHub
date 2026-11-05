package vn.alfha.modelhub.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import vn.alfha.modelhub.dto.BenchmarkSchedule;
import vn.alfha.modelhub.dto.GenerateResponse;

final class BenchmarkCloudScheduler {
    private static final long MAX_COOLDOWN_MS = 30_000;
    private final BenchmarkSchedule policy;
    private Long nextStart;
    private boolean stopped;

    BenchmarkCloudScheduler(BenchmarkSchedule policy) {
        this.policy = policy;
    }

    List<GenerateResponse> execute(Supplier<GenerateResponse> generate) {
        var attempts = new ArrayList<GenerateResponse>();
        for (int retry = 0; retry <= policy.maxRetries() && !stopped; retry++) {
            try {
                if (nextStart != null) {
                    long remaining = nextStart - System.nanoTime();
                    while (remaining > 0) {
                        TimeUnit.NANOSECONDS.sleep(remaining);
                        remaining = nextStart - System.nanoTime();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
                break;
            }
            nextStart = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(policy.minIntervalMs());
            var execution = generate.get();
            attempts.add(execution);
            var error = execution.error();
            if (error == null || !"RATE_LIMITED".equals(error.code())) break;
            long delay = error.retryAfterMs() == null ? 1000L << retry : error.retryAfterMs();
            if (delay > MAX_COOLDOWN_MS) {
                stopped = true;
                break;
            }
            long cooldown = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
            if (cooldown - nextStart > 0) nextStart = cooldown;
        }
        return attempts;
    }
}
