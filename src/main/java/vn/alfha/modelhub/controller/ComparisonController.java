package vn.alfha.modelhub.controller;

import org.springframework.web.bind.annotation.*;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.service.ComparisonService;

@RestController
public class ComparisonController {
    private final ComparisonService service;
    public ComparisonController(ComparisonService service) { this.service = service; }
    @PostMapping("/api/compare")
    public CompareResponse compare(@RequestBody CompareRequest request) { return service.compare(request); }
}
