package vn.alfha.modelhub.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.BenchmarkReport;
import vn.alfha.modelhub.dto.CodingBenchmarkCohort.SavedReport;

@Repository
@ConditionalOnProperty(name = "modelhub.history.enabled", havingValue = "true", matchIfMissing = true)
public class BenchmarkRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public BenchmarkRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public void save(BenchmarkReport report) {
        jdbc.update("INSERT INTO benchmark_reports(id, report) VALUES (?, ?::jsonb)", report.id(), mapper.writeValueAsString(report));
    }
    public Optional<BenchmarkReport> find(UUID id) {
        return jdbc.query("SELECT report::text FROM benchmark_reports WHERE id = ?",
                (rs, row) -> mapper.readValue(rs.getString(1), BenchmarkReport.class), id).stream().findFirst();
    }
    public List<SavedReport> findRecentCodingReports(int limit) {
        return jdbc.query("""
                SELECT id, (report->>'completedAt')::timestamptz AS completed_at,
                       report #>> '{dataset,version}' AS dataset_version, report->>'scoringVersion' AS scoring_version
                FROM benchmark_reports
                WHERE report->>'id' = id::text AND report #>> '{dataset,id}' = 'java-coding'
                  AND report->>'completedAt' IS NOT NULL
                ORDER BY completed_at DESC, id DESC
                LIMIT ?
                """, (rs, row) -> new SavedReport(rs.getObject("id", UUID.class),
                rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                rs.getString("dataset_version"), rs.getString("scoring_version")), limit);
    }
    public List<BenchmarkReport> findCodingPeerCandidates(BenchmarkReport seed, int limit) {
        return jdbc.query("""
                SELECT report::text FROM benchmark_reports
                WHERE id <> ? AND report->>'id' = id::text
                  AND report #>> '{dataset,id}' = ? AND report #>> '{dataset,version}' = ?
                  AND report->>'datasetSha256' = ? AND report->>'scoringVersion' = ?
                  AND report->>'deploymentId' IS NOT DISTINCT FROM ?
                  AND report->'models' = ?::jsonb
                  AND COALESCE(report->'schedule', 'null'::jsonb) = ?::jsonb
                ORDER BY (report->>'completedAt')::timestamptz DESC, id DESC
                LIMIT ?
                """, (rs, row) -> mapper.readValue(rs.getString(1), BenchmarkReport.class), seed.id(),
                seed.dataset().id(), seed.dataset().version(), seed.datasetSha256(), seed.scoringVersion(), seed.deploymentId(),
                mapper.writeValueAsString(seed.models()), mapper.writeValueAsString(seed.schedule()), limit);
    }
}
