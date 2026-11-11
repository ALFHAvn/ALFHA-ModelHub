package vn.alfha.modelhub.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.service.GenerationService;

@RestController
public class GenerationController {
    private final GenerationService service;
    public GenerationController(GenerationService service) { this.service = service; }
    @PostMapping("/api/generate")
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest request) {
        var result = service.generate(request);
        return ResponseEntity.status(status(result)).body(result);
    }
    @PostMapping("/api/generate/local")
    public ResponseEntity<GenerateResponse> generateLocal(@RequestBody GenerateRequest request) {
        var result = service.generateInstalledRaw(request);
        return ResponseEntity.status(status(result)).body(result);
    }
    @PostMapping("/api/generate/json")
    public ResponseEntity<GenerateResponse> generateJson(@RequestBody JsonGenerateRequest request) {
        var result = service.generateJson(request);
        return ResponseEntity.status(status(result)).body(result);
    }
    static int status(GenerateResponse result) {
        return result.error() == null ? 200 : switch (result.error().code()) {
            case "TIMEOUT" -> 504;
            case "CONNECTION_FAILURE", "INTERRUPTED", "CONFIGURATION_ERROR", "HISTORY_UNAVAILABLE" -> 503;
            case "RATE_LIMITED" -> 429;
            default -> 502;
        };
    }
}
