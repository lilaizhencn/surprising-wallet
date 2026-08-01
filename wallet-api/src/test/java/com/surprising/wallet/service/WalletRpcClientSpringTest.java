package com.surprising.wallet.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证 {@code WalletRpcClientSpringTest} 覆盖的业务流程、边界条件和异常行为。
 */
class WalletRpcClientSpringTest {

    /**
     * 验证 {@code springInjectsTheObjectMapperConstructor} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void springInjectsTheObjectMapperConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
            context.register(WalletRpcClient.class);
            context.refresh();

            assertNotNull(context.getBean(WalletRpcClient.class));
        }
    }
}
