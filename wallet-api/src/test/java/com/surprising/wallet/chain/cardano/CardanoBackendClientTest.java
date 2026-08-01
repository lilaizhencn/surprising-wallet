package com.surprising.wallet.chain.cardano;

import com.bloxbean.cardano.client.api.model.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code CardanoBackendClientTest} 覆盖的业务流程、边界条件和异常行为。
 */
class CardanoBackendClientTest {

    /**
     * 验证 {@code shouldTreatOnlyUnsuccessful404AsNotFound} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void shouldTreatOnlyUnsuccessful404AsNotFound() {
        assertTrue(CardanoBackendClient.isNotFound(Result.error("not indexed yet").code(404)));
        assertFalse(CardanoBackendClient.isNotFound(Result.error("backend unavailable").code(503)));
        assertFalse(CardanoBackendClient.isNotFound(Result.success("ok").code(404)));
        assertFalse(CardanoBackendClient.isNotFound(null));
    }
}
