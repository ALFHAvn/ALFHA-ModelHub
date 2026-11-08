package vn.alfha.modelhub.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.service.*;

@RestController
@RequestMapping("/api/benchmarks")
public class BenchmarkController {
    private final BenchmarkService service;
    private final BenchmarkCatalog catalog;
    public BenchmarkController(BenchmarkService service, BenchmarkCatalog catalog) { this.service = service; this.catalog = catalog; }
    @GetMapping("/dataset")
    public BenchmarkDataset dataset(@RequestParam(defaultValue = "basics") String datasetId,
            @RequestParam(defaultValue = "1") String version) {
        return catalog.dataset(datasetId, version);
    }
    @PostMapping
    public ResponseEntity<BenchmarkReport> run(@RequestBody BenchmarkRequest request) {
        return created(service.run(request));
    }
    @PostMapping("/java-coding/strict-raw")
    public ResponseEntity<BenchmarkReport> runStrictRaw(@RequestBody BenchmarkRequest request) {
        return created(service.runStrictRaw(request));
    }
    @PostMapping("/java-coding/strict-raw-v2")
    public ResponseEntity<BenchmarkReport> runStrictRawV2(@RequestBody BenchmarkRequest request) {
        return created(service.runStrictRawV2(request));
    }
    @PostMapping("/coding-cohort")
    public CodingBenchmarkCohort codingCohort(@RequestBody CodingBenchmarkCohort.Request request) {
        return service.codingCohort(request.reportIds());
    }
    @GetMapping("/coding-reports")
    public List<CodingBenchmarkCohort.SavedReport> recentCodingReports() {
        return service.recentCodingReports();
    }
    @GetMapping("/{id}/coding-peers")
    public CodingBenchmarkCohort.Discovery codingPeers(@PathVariable UUID id, @RequestParam(defaultValue = "19") int limit) {
        return service.codingPeers(id, limit);
    }
    private ResponseEntity<BenchmarkReport> created(BenchmarkReport report) {
        return ResponseEntity.created(URI.create("/api/benchmarks/" + report.id())).body(report);
    }
    @GetMapping("/{id}")
    public BenchmarkReport find(@PathVariable UUID id) { return service.find(id); }
}
