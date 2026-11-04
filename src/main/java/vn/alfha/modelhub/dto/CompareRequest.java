package vn.alfha.modelhub.dto;

import java.util.List;

public record CompareRequest(List<String> models, String prompt) { }
