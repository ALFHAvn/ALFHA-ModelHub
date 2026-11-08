package vn.alfha.modelhub.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import vn.alfha.modelhub.service.RoutingPolicy.Mode;

@Service
@ConditionalOnProperty(name = "modelhub.routing.preview.enabled", havingValue = "true")
public class RoutingPreview {
    private final BenchmarkCatalog catalog;
    private final BenchmarkRepository repository;
    private final List<Candidate> candidates;
    private final List<UUID> reportIds;
    private final Instant cutoff;
    private final OpenRouterPricing pricing;
    private final boolean verifyCloud;
    private final boolean allowCloudDispatch;
    private final RoutingPolicy policy;
    private final BenchmarkDeployment deployment;

    @Autowired
    public RoutingPreview(BenchmarkCatalog catalog, ObjectProvider<BenchmarkRepository> repository,
            ModelRegistry registry, @Value("${modelhub.ollama.timeout}") String localTimeout,
            @Value("${modelhub.openrouter.timeout}") String cloudTimeout,
            @Value("${modelhub.routing.preview.report-ids:}") String ids,
            @Value("${modelhub.routing.preview.cutoff:}") String cutoff,
            @Value("${modelhub.routing.preview.verify-cloud:false}") boolean verifyCloud,
            @Value("${modelhub.routing.generation.allow-cloud:false}") boolean allowCloudDispatch,
            @Value("${modelhub.openrouter.endpoint}") String cloudEndpoint, ObjectMapper mapper, ObjectiveScorer scorer,
            BenchmarkDeployment deployment) {
        this(catalog, repository.getIfAvailable(), registry, localTimeout, cloudTimeout, ids, cutoff,
                verifyCloud, allowCloudDispatch, new OpenRouterPricing(mapper, cloudEndpoint), scorer, deployment);
    }

    RoutingPreview(BenchmarkCatalog catalog, BenchmarkRepository repository, ModelRegistry registry,
            String localTimeout, String cloudTimeout, String ids, String cutoff, boolean verifyCloud,
            boolean allowCloudDispatch, OpenRouterPricing pricing, ObjectiveScorer scorer, BenchmarkDeployment deployment) {
        this.catalog = catalog;
        this.verifyCloud = verifyCloud;
        this.allowCloudDispatch = allowCloudDispatch;
        this.pricing = pricing;
        this.policy = new RoutingPolicy(scorer);
        this.deployment = deployment;
        this.repository = repository;
        this.cutoff = cutoff.isBlank() ? null : Instant.parse(cutoff.strip());
        var unique = new TreeSet<UUID>();
        if (!ids.isBlank()) {
            String[] tokens = ids.split(",", -1);
            if (tokens.length > 20) throw new IllegalArgumentException("Routing preview accepts up to 20 report IDs");
            for (String id : tokens) unique.add(UUID.fromString(id.strip()));
        }
        reportIds = List.copyOf(unique);
        candidates = registry.routingModels().stream().map(model -> new Candidate(model.model(), model.provider(),
                "ollama".equals(model.provider()) ? localTimeout : cloudTimeout)).toList();
    }

    public RoutingPolicy.Decision preview(String profile) {
        return decide(profile, Mode.PREVIEW, verifyCloud);
    }

    public RoutingPolicy.Decision dispatch(String profile) {
        return decide(profile, allowCloudDispatch ? Mode.CLOUD_ALLOWED_DISPATCH : Mode.LOCAL_DISPATCH,
                allowCloudDispatch);
    }

    private RoutingPolicy.Decision decide(String profile, Mode mode, boolean checkPricing) {
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
                            String reason = id.equals(report.id()) ? deployment.exclusionReason(report.deploymentId())
                                    : "INVALID_REPORT_ID";
                            if (reason == null) reports.add(report);
                            else excluded.add(new Exclusion(id, reason));
                        }, () -> excluded.add(new Exclusion(id, "REPORT_NOT_FOUND")));
                    } catch (DataAccessException | JacksonException e) {
                        reports.clear();
                        excluded.add(new Exclusion(id, "EVIDENCE_UNAVAILABLE"));
                        break;
                    }
                }
            }
        }
        var decision = policy.evaluate(Instant.now(), cutoff, catalog.dataset("2"), catalog.hash("2"),
                candidates, reports, excluded, mode);
        if (!checkPricing || "EVIDENCE_UNAVAILABLE".equals(decision.reason())) return decision;
        var cloud = decision.candidates().stream()
                .filter(entry -> entry.reasons().equals(List.of("CLOUD_NOT_VERIFIED")))
                .findFirst().orElse(null);
        if (cloud == null) return decision;
        var check = pricing.check(cloud.model());
        var checked = candidates.stream().map(candidate -> cloud.provider().equals(candidate.provider())
                && cloud.model().equals(candidate.model())
                ? new Candidate(candidate.model(), candidate.provider(), candidate.timeout(), check) : candidate).toList();
        return policy.evaluate(Instant.now(), cutoff, catalog.dataset("2"), catalog.hash("2"),
                checked, reports, excluded, mode);
    }
}
