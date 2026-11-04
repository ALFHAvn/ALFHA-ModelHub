package vn.alfha.modelhub.controller;

import org.springframework.web.bind.annotation.*;
import vn.alfha.modelhub.service.HistoryService;
import vn.alfha.modelhub.dto.RunPage;

@RestController
public class RunController {
    private final HistoryService history;
    public RunController(HistoryService history) { this.history = history; }
    @GetMapping("/api/runs")
    public RunPage runs(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return history.findPage(page, size);
    }
}
