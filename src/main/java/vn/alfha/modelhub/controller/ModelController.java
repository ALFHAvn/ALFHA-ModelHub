package vn.alfha.modelhub.controller;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import vn.alfha.modelhub.service.ModelRegistry;

@RestController
public class ModelController {
    private final ModelRegistry registry;
    public ModelController(ModelRegistry registry) { this.registry = registry; }
    @GetMapping("/api/models")
    public List<ModelRegistry.ConfiguredModel> models() { return registry.models(); }
}
