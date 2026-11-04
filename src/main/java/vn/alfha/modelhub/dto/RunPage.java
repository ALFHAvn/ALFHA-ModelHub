package vn.alfha.modelhub.dto;

import java.util.List;
import vn.alfha.modelhub.entity.ExecutionRun;

public record RunPage(List<ExecutionRun> content, int page, int size, long totalElements, long totalPages) { }
