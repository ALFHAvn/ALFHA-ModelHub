package vn.alfha.modelhub.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.alfha.modelhub.service.RoutingPreview;
import vn.alfha.modelhub.service.RoutingPolicy.Decision;

@RestController
@ConditionalOnProperty(name = "modelhub.routing.preview.enabled", havingValue = "true")
public class RoutingPreviewController {
    private final RoutingPreview preview;

    public RoutingPreviewController(RoutingPreview preview) { this.preview = preview; }

    @GetMapping("/api/routing/preview")
    public Decision preview(@RequestParam(defaultValue = "") String profile) { return preview.preview(profile); }
}
