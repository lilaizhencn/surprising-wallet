package com.surprising.wallet.custody.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class WalletRpcClientSpringTest {

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
