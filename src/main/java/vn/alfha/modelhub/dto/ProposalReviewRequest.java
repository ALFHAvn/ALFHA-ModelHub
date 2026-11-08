package vn.alfha.modelhub.dto;

public record ProposalReviewRequest(String proposerModel, String reviewerModel, String prompt, Boolean allowCloud) { }
