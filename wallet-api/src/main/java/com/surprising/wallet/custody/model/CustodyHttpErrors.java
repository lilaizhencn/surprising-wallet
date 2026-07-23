package com.surprising.wallet.custody.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

public final class CustodyHttpErrors {
    private CustodyHttpErrors() {
    }

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
