package com.surprising.wallet.deposit.repository;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证 {@code ChainJdbcRepositoryTransactionContractTest} 覆盖的业务流程、边界条件和异常行为。
 */
class ChainJdbcRepositoryTransactionContractTest {

    /**
     * 验证 {@code everyPublicDepositCreditEntryPointIsTransactional} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void everyPublicDepositCreditEntryPointIsTransactional() {
        Method[] entryPoints = Arrays.stream(ChainJdbcRepository.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("recordAndCreditDeposit"))
                .toArray(Method[]::new);

        assertEquals(3, entryPoints.length);
        for (Method entryPoint : entryPoints) {
            Transactional transactional = entryPoint.getAnnotation(Transactional.class);
            assertNotNull(transactional, entryPoint + " must define a transaction boundary");
            assertEquals(Throwable.class, transactional.rollbackFor()[0]);
        }
    }
}
