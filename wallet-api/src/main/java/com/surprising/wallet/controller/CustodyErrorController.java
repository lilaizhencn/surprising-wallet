package com.surprising.wallet.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Servlet 错误分派始终返回 JSON，避免 HTML 请求在缺少 error 模板时循环分派。 */
@RestController
public class CustodyErrorController implements ErrorController {
    @RequestMapping("${spring.web.error.path:/error}")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = value instanceof Integer code ? HttpStatus.resolve(code) : null;
        if (status == null || !status.isError()) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", Map.of(
                        "code", status.name(), "message", status.getReasonPhrase())));
    }
}
