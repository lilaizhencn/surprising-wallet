package com.surprising.wallet.custody;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.surprising.wallet.custody.controller.console.CustodyConsoleSecurityController.CreateApiKeyRequest;
import com.surprising.wallet.custody.controller.console.CustodyConsoleSecurityController;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;

/**
 * 验证 {@code CustodyConsoleSecurityControllerTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyConsoleSecurityControllerTest {
    /**
     * 保存 {@code objectMapper}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private final ObjectMapper objectMapper =
            new CustodyJacksonConfiguration().custodyObjectMapper();

    /**
     * 验证 {@code createApiKeyAcceptsOnlyName} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createApiKeyAcceptsOnlyName() throws Exception {
        CustodyConsoleSecurityController.CreateApiKeyRequest request = objectMapper.readValue("""
                {
                  "name": "Production backend"
                }
                """, CustodyConsoleSecurityController.CreateApiKeyRequest.class);

        assertEquals("Production backend", request.name());
    }

    /**
     * 验证 {@code createApiKeyRejectsScopeSelection} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createApiKeyRejectsScopeSelection() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue("""
                {
                  "name": "Production backend",
                  "scopes": ["addresses:read"]
                }
                """, CustodyConsoleSecurityController.CreateApiKeyRequest.class));
    }
}
