package com.surprising.wallet.approval.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApprovalErrors {
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String,String>> unauthorized(SecurityException failure) {
        return ResponseEntity.status(401).body(Map.of("code","APPROVAL_AUTH_REQUIRED","message",failure.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    public ResponseEntity<Map<String,String>> invalid(RuntimeException failure) {
        return ResponseEntity.badRequest().body(Map.of("code","APPROVAL_REQUEST_INVALID","message",failure.getMessage()));
    }
}
