package com.hydrapay.ledger.exception;

import com.hydrapay.ledger.security.AuditLoggerService;
import com.hydrapay.ledger.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final AuditLoggerService auditLoggerService;

    private String getCorrelationId() {
        String cid = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
        return cid != null ? cid : "N/A";
    }

    private Map<String, Object> buildErrorResponseBody(HttpStatus status, String errorCode, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", errorCode);
        body.put("message", message);
        body.put("path", path);
        body.put("correlationId", getCorrelationId());
        return body;
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(InsufficientFundsException ex, HttpServletRequest request) {
        log.warn("Insufficient funds for request [{}]: {}", request.getRequestURI(), ex.getMessage());
        auditLoggerService.logInsufficientFunds(null, null, ex.getMessage());
        Map<String, Object> body = buildErrorResponseBody(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAccountNotFound(AccountNotFoundException ex, HttpServletRequest request) {
        log.warn("Account not found for request [{}]: {}", request.getRequestURI(), ex.getMessage());
        Map<String, Object> body = buildErrorResponseBody(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        log.warn("Idempotency conflict for request [{}]: {}", request.getRequestURI(), ex.getMessage());
        auditLoggerService.logIdempotencyConflict("N/A", ex.getMessage());
        Map<String, Object> body = buildErrorResponseBody(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detailMsg = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("Validation error for request [{}]: {}", request.getRequestURI(), detailMsg);
        Map<String, Object> body = buildErrorResponseBody(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detailMsg, request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied for request [{}]: {}", request.getRequestURI(), ex.getMessage());
        auditLoggerService.logAccessDenied("ANONYMOUS", request.getRequestURI(), "ROLE_REQUIRED");
        Map<String, Object> body = buildErrorResponseBody(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied. Insufficient role permissions.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication failed for request [{}]: {}", request.getRequestURI(), ex.getMessage());
        auditLoggerService.logAuthFailure("ANONYMOUS", request.getRequestURI(), ex.getMessage());
        Map<String, Object> body = buildErrorResponseBody(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication credentials required or invalid.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Internal error for request [{}] with correlation ID [{}]: {}", request.getRequestURI(), getCorrelationId(), ex.getMessage(), ex);
        Map<String, Object> body = buildErrorResponseBody(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                "An unexpected internal error occurred. Please contact support with correlation ID: " + getCorrelationId(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
