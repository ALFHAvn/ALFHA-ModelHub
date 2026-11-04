package vn.alfha.modelhub.controller;

import java.net.URI;
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
    public BenchmarkDataset dataset() { return catalog.dataset(); }
    @PostMapping
    public ResponseEntity<BenchmarkReport> run(@RequestBody BenchmarkRequest request) {
        var report = service.run(request);
        return ResponseEntity.created(URI.create("/api/benchmarks/" + report.id())).body(report);
    }
    @GetMapping("/{id}")
    public BenchmarkReport find(@PathVariable UUID id) { return service.find(id); }
}
