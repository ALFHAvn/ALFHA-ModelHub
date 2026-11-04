package vn.alfha.modelhub.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import vn.alfha.modelhub.dto.ApiError;
import vn.alfha.modelhub.service.GenerationService.UnknownModelException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "Provide valid JSON with a model and a prompt of 1 to 32000 characters"));
    }
    @ExceptionHandler(UnknownModelException.class)
    public ResponseEntity<ApiError> unknown() {
        return ResponseEntity.badRequest().body(new ApiError("UNKNOWN_MODEL", "Model is not configured"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected() {
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
