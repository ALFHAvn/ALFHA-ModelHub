package vn.alfha.modelhub.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;
import vn.alfha.modelhub.dto.ApiError;
import vn.alfha.modelhub.service.GenerationService.UnknownModelException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "Provide valid model selection and a prompt of 1 to 32000 characters; comparison requires two distinct models"));
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
}
