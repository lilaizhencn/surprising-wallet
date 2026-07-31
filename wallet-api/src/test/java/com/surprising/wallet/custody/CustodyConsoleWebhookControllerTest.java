package com.surprising.wallet.custody;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.surprising.wallet.custody.service.CustodyWebhookService.CreateWebhookCommand;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.custody.service.CustodyWebhookService;

/**
 * 验证 {@code CustodyConsoleWebhookControllerTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyConsoleWebhookControllerTest {
    /**
     * 保存 {@code objectMapper}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private final ObjectMapper objectMapper =
            new CustodyJacksonConfiguration().custodyObjectMapper();

    /**
     * 验证 {@code createWebhookAcceptsOnlyNameAndUrl} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createWebhookAcceptsOnlyNameAndUrl() throws Exception {
        CustodyWebhookService.CreateWebhookCommand request = objectMapper.readValue("""
                {
                  "name": "Production events",
                  "url": "https://example.com/webhooks/custody"
                }
                """, CustodyWebhookService.CreateWebhookCommand.class);

        assertEquals("Production events", request.name());
        assertEquals("https://example.com/webhooks/custody", request.url());
    }

    /**
     * 验证 {@code createWebhookRejectsEventSelection} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void createWebhookRejectsEventSelection() {
        assertThrows(UnrecognizedPropertyException.class, () -> objectMapper.readValue("""
                {
                  "name": "Production events",
                  "url": "https://example.com/webhooks/custody",
                  "events": ["DEPOSIT.CONFIRMED"]
                }
                """, CustodyWebhookService.CreateWebhookCommand.class));
    }
}
