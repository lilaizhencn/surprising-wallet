package com.surprising.wallet.custody;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;

/**
 * 验证 {@code CustodyJacksonConfigurationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CustodyJacksonConfigurationTest {
    /**
     * 验证 {@code serializesJavaTimeAsIso8601ForApiAndWebhookPayloads} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void serializesJavaTimeAsIso8601ForApiAndWebhookPayloads() throws Exception {
        ObjectMapper mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        Instant instant = Instant.parse("2026-07-20T00:00:00Z");

        String json = mapper.writeValueAsString(Map.of("createdAt", instant));

        assertEquals("{\"createdAt\":\"2026-07-20T00:00:00Z\"}", json);
        assertEquals(instant, mapper.treeToValue(mapper.readTree(json).path("createdAt"), Instant.class));
    }
}
