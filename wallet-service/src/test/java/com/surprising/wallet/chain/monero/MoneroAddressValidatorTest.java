package com.surprising.wallet.chain.monero;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code MoneroAddressValidatorTest} 覆盖的业务流程、边界条件和异常行为。
 */
class MoneroAddressValidatorTest {
    /**
     * 保存 {@code GETMONERO_DONATION_ADDRESS}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private static final String GETMONERO_DONATION_ADDRESS =
            "888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H";

    /**
     * 验证 {@code validatesMoneroBase58AddressChecksum} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void validatesMoneroBase58AddressChecksum() {
        assertTrue(MoneroAddressValidator.isValid(GETMONERO_DONATION_ADDRESS));
        assertFalse(MoneroAddressValidator.isValid(
                GETMONERO_DONATION_ADDRESS.substring(0, GETMONERO_DONATION_ADDRESS.length() - 1) + "J"));
        assertFalse(MoneroAddressValidator.isValid("8".repeat(95)));
        assertFalse(MoneroAddressValidator.isValid("not-an-address"));
    }
}
