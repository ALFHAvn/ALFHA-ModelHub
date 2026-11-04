package vn.alfha.modelhub.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.alfha.modelhub.dto.HealthResponse;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "ALFHA ModelHub");
    }
}
