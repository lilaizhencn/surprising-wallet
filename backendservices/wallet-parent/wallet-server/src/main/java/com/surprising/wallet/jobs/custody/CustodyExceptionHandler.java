package com.surprising.wallet.jobs.custody;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.surprising.wallet.jobs.custody")
public class CustodyExceptionHandler {
    @ExceptionHandler(CustodyUnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(CustodyUnauthorizedException e) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
    }

    @ExceptionHandler(CustodyForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(CustodyForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "resource already exists or violates a constraint");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> state(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", Map.of(
                        "code", code,
                        "message", message == null ? status.getReasonPhrase() : message)));
    }
}
