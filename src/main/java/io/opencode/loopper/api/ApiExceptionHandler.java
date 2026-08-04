package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException ex) { return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage()); }
    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ProblemDetail> badRequest(BadRequestException ex) { return problem(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage()); }
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException ex) { return problem(HttpStatus.CONFLICT, ex.code(), ex.getMessage()); }
    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ProblemDetail> serviceUnavailable(ServiceUnavailableException ex) { return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.code(), ex.getMessage()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> invalidJson(HttpMessageNotReadableException ex) { return problem(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request JSON is invalid"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidFields(MethodArgumentNotValidException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        detail.setTitle("Validation failed"); detail.setProperty("errorCode", "FIELD_VALIDATION"); detail.setProperty("errorLayer", "FIELD");
        detail.setProperty("fields", ex.getBindingResult().getFieldErrors().stream().collect(java.util.stream.Collectors.toMap(
                FieldError::getField, field -> field.getDefaultMessage() == null ? "invalid" : field.getDefaultMessage(), (a, b) -> a)));
        return ResponseEntity.badRequest().body(detail);
    }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase()); detail.setProperty("errorCode", code);
        return ResponseEntity.status(status).body(detail);
    }
}
