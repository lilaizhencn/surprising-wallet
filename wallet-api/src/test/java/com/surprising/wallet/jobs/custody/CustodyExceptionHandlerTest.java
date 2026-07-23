package com.surprising.wallet.jobs.custody;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustodyExceptionHandlerTest {
    private final CustodyExceptionHandler handler = new CustodyExceptionHandler();

    @Test
    void preservesWalletBusinessErrorForCustodyClients() {
        var response = handler.responseStatus(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient available balance"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertError(response.getBody(), "INVALID_REQUEST", "insufficient available balance");
    }

    @Test
    void namesMissingRequiredHeader() throws NoSuchMethodException {
        Method method = CustodyExceptionHandlerTest.class.getDeclaredMethod("headerTarget", String.class);
        var response = handler.missingHeader(
                new MissingRequestHeaderException("Idempotency-Key", new MethodParameter(method, 0)));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertError(response.getBody(), "INVALID_REQUEST", "missing required header: Idempotency-Key");
    }

    @SuppressWarnings("unused")
    private void headerTarget(@RequestHeader("Idempotency-Key") String idempotencyKey) {
    }

    @SuppressWarnings("unchecked")
    private static void assertError(Map<String, Object> body, String code, String message) {
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals(code, error.get("code"));
        assertEquals(message, error.get("message"));
    }
}
