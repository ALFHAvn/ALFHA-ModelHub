package vn.alfha.modelhub.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
record BenchmarkDeployment(String id) {
    BenchmarkDeployment(@Value("${modelhub.benchmark.deployment-id:}") String id) {
        if (id != null && !id.isEmpty() && !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Benchmark deployment ID must be 1 to 64 letters, digits, dots, underscores or hyphens and start with a letter or digit");
        this.id = id == null || id.isEmpty() ? null : id;
    }

    String exclusionReason(String recordedId) {
        if (id == null || id.equals(recordedId)) return null;
        return recordedId == null ? "DEPLOYMENT_ID_MISSING" : "DEPLOYMENT_ID_MISMATCH";
    }
}
