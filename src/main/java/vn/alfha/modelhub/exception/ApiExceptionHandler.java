package vn.alfha.modelhub.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.*;
import vn.alfha.modelhub.dto.ApiError;
import vn.alfha.modelhub.service.GenerationService.UnknownModelException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(vn.alfha.modelhub.service.BenchmarkService.CodingDisabledException.class)
    public ResponseEntity<ApiError> codingDisabled() {
        return ResponseEntity.status(503).body(new ApiError("CODING_DISABLED", "Coding evaluation is disabled"));
    }
    @ExceptionHandler(vn.alfha.modelhub.service.BenchmarkService.BusyException.class)
    public ResponseEntity<ApiError> benchmarkBusy() {
        return ResponseEntity.status(429).body(new ApiError("BENCHMARK_BUSY", "A benchmark is already running; try later"));
    }
    @ExceptionHandler(vn.alfha.modelhub.service.BenchmarkService.MissingException.class)
    public ResponseEntity<ApiError> benchmarkMissing() {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Benchmark report does not exist"));
    }
    @ExceptionHandler(vn.alfha.modelhub.service.BenchmarkService.InvalidCohortException.class)
    public ResponseEntity<ApiError> invalidCohort(vn.alfha.modelhub.service.BenchmarkService.InvalidCohortException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_COHORT", exception.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "Check model selection, prompt (1 to 32000 characters), and pagination (page >= 0, size 1 to 100); comparison requires two distinct models"));
    }
    @ExceptionHandler(UnknownModelException.class)
    public ResponseEntity<ApiError> unknown() {
        return ResponseEntity.badRequest().body(new ApiError("UNKNOWN_MODEL", "Model is not configured"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> database() {
        return ResponseEntity.status(503).body(new ApiError("HISTORY_UNAVAILABLE", "Execution history is temporarily unavailable"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected() {
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred"));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method() {
        return ResponseEntity.status(405).body(new ApiError("METHOD_NOT_ALLOWED", "HTTP method is not supported"));
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> media() {
        return ResponseEntity.status(415).body(new ApiError("UNSUPPORTED_MEDIA_TYPE", "Use application/json"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> missing() {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Endpoint does not exist"));
    }
}
