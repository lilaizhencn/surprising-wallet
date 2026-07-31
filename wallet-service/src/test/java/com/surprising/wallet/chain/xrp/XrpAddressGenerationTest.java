package com.surprising.wallet.chain.xrp;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.xrpl.xrpl4j.model.transactions.Address;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code XrpAddressGenerationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class XrpAddressGenerationTest {
    /**
     * 保存 {@code FIRST_PRIVATE_KEY}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final BigInteger FIRST_PRIVATE_KEY =
            new BigInteger("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16);
    /**
     * 保存 {@code SECOND_PRIVATE_KEY}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final BigInteger SECOND_PRIVATE_KEY =
            new BigInteger("2234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16);

    /**
     * 验证 {@code derivesStableClassicAddressFromSecp256k1Key} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void derivesStableClassicAddressFromSecp256k1Key() {
        ECKey firstKey = ECKey.fromPrivate(FIRST_PRIVATE_KEY);
        ECKey secondKey = ECKey.fromPrivate(SECOND_PRIVATE_KEY);

        String first = XrpKeyService.address(firstKey);
        String restarted = XrpKeyService.address(ECKey.fromPrivate(FIRST_PRIVATE_KEY));
        String second = XrpKeyService.address(secondKey);

        assertEquals(first, restarted);
        assertNotEquals(first, second);
        assertTrue(first.startsWith("r"));
        Address.of(first).validateAddress();
        Address.of(second).validateAddress();
    }
}
