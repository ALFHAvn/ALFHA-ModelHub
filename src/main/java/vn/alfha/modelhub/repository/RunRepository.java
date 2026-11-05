package vn.alfha.modelhub.repository;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import vn.alfha.modelhub.entity.ExecutionRun;
import vn.alfha.modelhub.dto.*;

@Repository
@ConditionalOnProperty(name = "modelhub.history.enabled", havingValue = "true", matchIfMissing = true)
public class RunRepository {
    private final JdbcTemplate jdbc;
    public RunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(ExecutionRun run) {
        jdbc.update("""
                INSERT INTO execution_runs
                (request_id, model, prompt, response, duration_ms, status, error_code, error_message, created_at, retry_after_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.requestId(), run.model(), run.prompt(), run.response(), run.durationMs(), run.status(),
                run.error() == null ? null : run.error().code(), run.error() == null ? null : run.error().message(),
                Timestamp.from(run.timestamp()), run.error() == null ? null : run.error().retryAfterMs());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RunPage findPage(int page, int size) {
        long count = jdbc.queryForObject("SELECT count(*) FROM execution_runs", Long.class);
        var runs = jdbc.query("""
                SELECT * FROM execution_runs ORDER BY created_at DESC, request_id DESC LIMIT ? OFFSET ?
                """, (rs, index) -> new ExecutionRun(rs.getObject("request_id", UUID.class), rs.getString("model"),
                rs.getString("prompt"), rs.getString("response"), rs.getLong("duration_ms"), rs.getString("status"),
                rs.getString("error_code") == null ? null : new ApiError(rs.getString("error_code"),
                        rs.getString("error_message"), rs.getObject("retry_after_ms", Long.class)),
                rs.getTimestamp("created_at").toInstant()), size, (long) page * size);
        return new RunPage(runs, page, size, count, (count + size - 1) / size);
    }
}
