package vn.alfha.modelhub.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.alfha.modelhub.dto.GenerateRequest;
import vn.alfha.modelhub.dto.GenerateResponse;
import vn.alfha.modelhub.service.GenerationService;
import vn.alfha.modelhub.service.RoutingPolicy.Decision;
import vn.alfha.modelhub.service.RoutingPreview;

@RestController
@ConditionalOnProperty(name = {"modelhub.routing.preview.enabled", "modelhub.routing.generation.enabled"},
        havingValue = "true")
public class RoutedGenerationController {
    private final RoutingPreview routing;
    private final GenerationService generation;

    public RoutedGenerationController(RoutingPreview routing, GenerationService generation) {
        this.routing = routing;
        this.generation = generation;
    }

    public record Request(String profile, String prompt) { }
    public record Result(Decision decision, GenerateResponse generation) { }

    @PostMapping("/api/routing/generate")
    public ResponseEntity<Result> generate(@RequestBody Request request) {
        if (!GenerationService.validPrompt(request.prompt()))
            throw new IllegalArgumentException("Provide a prompt of 1 to 32000 characters");
        var decision = routing.localDispatch(request.profile());
        if (decision.selectedModel() == null) return ResponseEntity.status(503).body(new Result(decision, null));
        var result = generation.generate(new GenerateRequest(decision.selectedModel(), request.prompt()));
        return ResponseEntity.status(GenerationController.status(result)).body(new Result(decision, result));
    }
}
