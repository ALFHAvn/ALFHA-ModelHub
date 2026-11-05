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
import tools.jackson.databind.ObjectMapper;
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
    private final OpenRouterPricing pricing;

    public RoutingPreview(BenchmarkCatalog catalog, ObjectProvider<BenchmarkRepository> repository,
            ModelRegistry registry, @Value("${modelhub.ollama.timeout}") String localTimeout,
            @Value("${modelhub.openrouter.timeout}") String cloudTimeout,
            @Value("${modelhub.routing.preview.report-ids:}") String ids,
            @Value("${modelhub.routing.preview.cutoff:}") String cutoff,
            @Value("${modelhub.routing.preview.verify-cloud:false}") boolean verifyCloud,
            @Value("${modelhub.openrouter.endpoint}") String cloudEndpoint, ObjectMapper mapper) {
        this.catalog = catalog;
        pricing = verifyCloud ? new OpenRouterPricing(mapper, cloudEndpoint) : null;
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
        return decide(profile, false);
    }

    public RoutingPolicy.Decision localDispatch(String profile) {
        return decide(profile, true);
    }

    private RoutingPolicy.Decision decide(String profile, boolean localOnly) {
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
        var policy = new RoutingPolicy();
        var decision = policy.evaluate(Instant.now(), cutoff, catalog.dataset("2"), catalog.hash("2"),
                candidates, reports, excluded, localOnly);
        if (localOnly || pricing == null || "EVIDENCE_UNAVAILABLE".equals(decision.reason())) return decision;
        var cloud = decision.candidates().stream()
                .filter(entry -> entry.reasons().equals(List.of("CLOUD_NOT_VERIFIED")))
                .findFirst().orElse(null);
        if (cloud == null) return decision;
        var check = pricing.check(cloud.model());
        var checked = candidates.stream().map(candidate -> cloud.provider().equals(candidate.provider())
                && cloud.model().equals(candidate.model())
                ? new Candidate(candidate.model(), candidate.provider(), candidate.timeout(), check) : candidate).toList();
        return policy.preview(Instant.now(), cutoff, catalog.dataset("2"), catalog.hash("2"), checked, reports, excluded);
    }
}
