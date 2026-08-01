package com.surprising.wallet.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TronAddressGenerationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TronAddressGenerationTest {
    /**
     * 验证 {@code generatedAddress_shouldBeValidBase58AndStable} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void generatedAddress_shouldBeValidBase58AndStable() {
        ECKey ecKey = ECKey.fromPrivate(BigInteger.valueOf(20260621), true);
        String first = TronTridentKeyFactory.toBase58Address(ecKey);
        String second = TronTridentKeyFactory.toBase58Address(ecKey);

        assertEquals(first, second);
        assertTrue(first.startsWith("T"));
        assertTrue(TronAddressCodec.isValidBase58(first));
        assertEquals(first, TronAddressCodec.hexToBase58(TronAddressCodec.base58ToHex(first)));
    }
}
