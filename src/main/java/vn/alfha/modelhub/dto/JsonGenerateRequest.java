package vn.alfha.modelhub.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record JsonGenerateRequest(String model, String prompt, List<JsonNode> requiredStringFields) { }
