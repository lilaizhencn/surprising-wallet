package com.surprising.wallet.custody.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Custody 模块全局异常处理器。
 *
 * <p>将业务异常映射为标准 HTTP 响应：
 * <ul>
 *   <li>{@link CustodyUnauthorizedException} → 401</li>
 *   <li>{@link CustodyForbiddenException} → 403</li>
 *   <li>参数/请求体格式错误 → 400</li>
 *   <li>数据库约束冲突 → 409</li>
 *   <li>未预期的运行时异常 → 500（仅暴露错误码，不泄露内部细节）</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.surprising.wallet.custody")
public class CustodyExceptionHandler {
    /**
     * 未授权异常统一转为 401，返回标准错误码与消息。
     */
    @ExceptionHandler(CustodyUnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(CustodyUnauthorizedException e) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
    }

    /**
     * 禁止访问异常统一转为 403，避免泄露鉴权细节。
     */
    @ExceptionHandler(CustodyForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(CustodyForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    /**
     * 参数非法转为 400，保留入参错误提示。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    /**
     * 唯一性/外键等数据库约束冲突转为 409。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "resource already exists or violates a constraint");
    }

    /**
     * 业务状态异常转为 409，便于上游根据 code 做重试或人工介入。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> state(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    /**
     * WebFlux/控制器层定义的响应状态统一透传。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException e) {
        return error(e.getStatusCode(), codeFor(e.getStatusCode()), e.getReason());
    }

    /**
     * 缺失 Header 直接返回 400，避免后续 NPE。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "missing required header: " + e.getHeaderName());
    }

    /**
     * 反序列化失败返回 400，提示请求体格式不合法。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "request body is malformed or contains invalid field types");
    }

    /**
     * 参数类型转换失败返回 400，保留参数名方便排查。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> invalidParameter(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "invalid request parameter: " + e.getName());
    }

    /**
     * 根据 HTTP 状态映射统一错误码。
     */
    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "INVALID_STATE";
            default -> status.is4xxClientError() ? "INVALID_REQUEST" : "INTERNAL_ERROR";
        };
    }

    /**
     * 统一构造错误响应体，保持前后端一致的错误结构。
     */
    private static ResponseEntity<Map<String, Object>> error(
            HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", Map.of(
                    "code", code,
                        "message", message == null || message.isBlank()
                                ? "request could not be completed"
                                : message)));
    }
}
