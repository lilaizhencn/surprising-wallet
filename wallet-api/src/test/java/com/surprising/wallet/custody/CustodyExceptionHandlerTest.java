package com.surprising.wallet.custody;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.surprising.wallet.custody.exception.CustodyExceptionHandler;

/**
 * 验证 {@code CustodyExceptionHandlerTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyExceptionHandlerTest {
    /**
     * 保存 {@code handler}，用于承载当前测试夹具的配置或运行数据。
     */
    private final CustodyExceptionHandler handler = new CustodyExceptionHandler();

    /**
     * 验证 {@code preservesWalletBusinessErrorForCustodyClients} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void preservesWalletBusinessErrorForCustodyClients() {
        var response = handler.invalid(
                new IllegalArgumentException("insufficient available balance"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertError(response.getBody(), "INVALID_REQUEST", "insufficient available balance");
    }

    /**
     * 验证 {@code namesMissingRequiredHeader} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void namesMissingRequiredHeader() throws NoSuchMethodException {
        Method method = CustodyExceptionHandlerTest.class.getDeclaredMethod("headerTarget", String.class);
        var response = handler.missingHeader(
                new MissingRequestHeaderException("Idempotency-Key", new MethodParameter(method, 0)));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertError(response.getBody(), "INVALID_REQUEST", "missing required header: Idempotency-Key");
    }

    /**
     * 验证 {@code headerTarget} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @SuppressWarnings("unused")
    private void headerTarget(@RequestHeader("Idempotency-Key") String idempotencyKey) {
    }

    /**
     * 验证 {@code assertError} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @SuppressWarnings("unchecked")
    private static void assertError(Map<String, Object> body, String code, String message) {
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals(code, error.get("code"));
        assertEquals(message, error.get("message"));
    }
}
