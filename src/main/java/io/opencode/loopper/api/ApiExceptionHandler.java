package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.ServiceUnavailableException;
import io.opencode.loopper.lifecycle.PersistedStateInvalidException;
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
    @ExceptionHandler(io.opencode.loopper.ppt.PptFailure.class)
    ResponseEntity<ProblemDetail> ppt(io.opencode.loopper.ppt.PptFailure ex) { return problem(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage()); }
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException ex) { return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage()); }
    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ProblemDetail> badRequest(BadRequestException ex) { return problem(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage()); }
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException ex) { return problem(HttpStatus.CONFLICT, ex.code(), ex.getMessage()); }
    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ProblemDetail> serviceUnavailable(ServiceUnavailableException ex) { return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.code(), ex.getMessage()); }
    @ExceptionHandler(PersistedStateInvalidException.class)
    ResponseEntity<ProblemDetail> invalidPersistedState(PersistedStateInvalidException ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "PERSISTED_STATE_INVALID", ex.getMessage());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> invalidJson(HttpMessageNotReadableException ex) { return problem(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request JSON is invalid"); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> uploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_UPLOAD_TOO_LARGE", "上传超过大小限制，请检查单文件 20 MiB、整批 50 MiB 的上限后重试");
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> missingUploadPart(org.springframework.web.multipart.support.MissingServletRequestPartException ex) {
        return problem(HttpStatus.BAD_REQUEST, "UPLOAD_PART_REQUIRED", "上传内容不完整，请重新选择文档并提交");
    }
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
