package com.surprising.wallet.deposit.repository;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChainJdbcRepositoryTransactionContractTest {

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
