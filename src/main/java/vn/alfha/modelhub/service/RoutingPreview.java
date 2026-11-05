package vn.alfha.modelhub.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import vn.alfha.modelhub.dto.BenchmarkReport;
import vn.alfha.modelhub.repository.BenchmarkRepository;
import vn.alfha.modelhub.service.RoutingPolicy.Candidate;
import vn.alfha.modelhub.service.RoutingPolicy.Exclusion;

@Service
@ConditionalOnProperty(name = "modelhub.routing.preview.enabled", havingValue = "true")
public class RoutingPreview {
    private final BenchmarkCatalog catalog;
    private final BenchmarkRepository repository;
    private final List<Candidate> candidates;
    private final List<UUID> reportIds;
    private final Instant cutoff;

    public RoutingPreview(BenchmarkCatalog catalog, ObjectProvider<BenchmarkRepository> repository,
            ModelRegistry registry, @Value("${modelhub.ollama.timeout}") String localTimeout,
            @Value("${modelhub.openrouter.timeout}") String cloudTimeout,
            @Value("${modelhub.routing.preview.report-ids:}") String ids,
            @Value("${modelhub.routing.preview.cutoff:}") String cutoff) {
        this.catalog = catalog;
        this.repository = repository.getIfAvailable();
        this.cutoff = cutoff.isBlank() ? null : Instant.parse(cutoff.strip());
        var unique = new TreeSet<UUID>();
        if (!ids.isBlank()) {
            String[] tokens = ids.split(",", -1);
            if (tokens.length > 20) throw new IllegalArgumentException("Routing preview accepts up to 20 report IDs");
            for (String id : tokens) unique.add(UUID.fromString(id.strip()));
        }
        reportIds = List.copyOf(unique);
        candidates = registry.models().stream().map(model -> new Candidate(model.model(), model.provider(),
                "ollama".equals(model.provider()) ? localTimeout : cloudTimeout)).toList();
    }

    public RoutingPolicy.Decision preview(String profile) {
        if (!"basic".equals(profile)) throw new IllegalArgumentException("Unknown routing profile");
        var reports = new ArrayList<BenchmarkReport>();
        var excluded = new ArrayList<Exclusion>();
        if (cutoff != null && !reportIds.isEmpty()) {
            if (repository == null) {
                excluded.add(new Exclusion(null, "EVIDENCE_UNAVAILABLE"));
            } else {
                for (UUID id : reportIds) {
                    try {
                        repository.find(id).ifPresentOrElse(report -> {
                            if (id.equals(report.id())) reports.add(report);
                            else excluded.add(new Exclusion(id, "INVALID_REPORT_ID"));
                        }, () -> excluded.add(new Exclusion(id, "REPORT_NOT_FOUND")));
                    } catch (DataAccessException | JacksonException e) {
                        reports.clear();
                        excluded.add(new Exclusion(id, "EVIDENCE_UNAVAILABLE"));
                        break;
                    }
                }
            }
        }
        return new RoutingPolicy().preview(Instant.now(), cutoff, catalog.dataset("2"), catalog.hash("2"),
                candidates, reports, excluded);
    }
}
