package com.surprising.wallet.custody.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * API 错误响应统一工具。
 */
public final class CustodyHttpErrors {
    /**
     * 工具类禁止实例化。
     */
    private CustodyHttpErrors() {
    }

    /**
     * 按统一格式写入错误响应体，保证前端与平台方错误码语义一致。
     */
    public static void write(ObjectMapper objectMapper, HttpServletResponse response, int status,
                            String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", Map.of(
                        "code", code,
                        "message", message)));
    }
}
