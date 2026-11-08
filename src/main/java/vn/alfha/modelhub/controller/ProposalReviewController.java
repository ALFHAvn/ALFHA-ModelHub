package vn.alfha.modelhub.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.alfha.modelhub.dto.ProposalReviewRequest;
import vn.alfha.modelhub.dto.ProposalReviewResponse;
import vn.alfha.modelhub.service.ProposalReviewService;

@RestController
public class ProposalReviewController {
    private final ProposalReviewService service;

    public ProposalReviewController(ProposalReviewService service) { this.service = service; }

    @PostMapping("/api/workflows/proposal-review")
    public ProposalReviewResponse run(@RequestBody ProposalReviewRequest request) {
        return service.run(request);
    }

    @PostMapping("/api/workflows/proposal-review/defect-ids")
    public ProposalReviewResponse runDefectIds(@RequestBody ProposalReviewRequest request) {
        return service.runDefectIds(request);
    }
}
