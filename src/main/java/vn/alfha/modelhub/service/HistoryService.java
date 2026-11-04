package vn.alfha.modelhub.service;

import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import vn.alfha.modelhub.repository.RunRepository;
import vn.alfha.modelhub.entity.ExecutionRun;
import vn.alfha.modelhub.dto.*;

@Service
public class HistoryService {
    private final RunRepository repository;
    public HistoryService(ObjectProvider<RunRepository> repository) { this.repository = repository.getIfAvailable(); }
    public GenerateResponse record(String prompt, GenerateResponse result) {
        if (repository == null) return result; // Explicit infrastructure-free test configuration.
        try {
            repository.save(new ExecutionRun(result.requestId(), result.model(), prompt, result.response(),
                    result.durationMs(), result.status(), result.error(), Instant.now()));
            return result;
        } catch (DataAccessException e) {
            return new GenerateResponse(result.requestId(), result.model(), result.response(), result.durationMs(), "FAILED",
                    new ApiError("HISTORY_UNAVAILABLE", "Execution completed but history could not be saved"));
        }
    }
    public RunPage findPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        if (repository == null) throw new IllegalStateException("History is disabled");
        return repository.findPage(page, size);
    }
}
