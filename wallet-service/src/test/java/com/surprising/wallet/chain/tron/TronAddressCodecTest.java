package com.surprising.wallet.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TronAddressCodecTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TronAddressCodecTest {
    /**
     * 验证 {@code base58AndHex_shouldRoundTrip} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void base58AndHex_shouldRoundTrip() {
        String address = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(42), true));
        String hex = TronAddressCodec.base58ToHex(address);
        assertEquals(42, hex.length());
        assertTrue(hex.startsWith("41"));
        assertEquals(address, TronAddressCodec.hexToBase58(hex));
    }

    /**
     * 验证 {@code topicAddress_shouldDecodeToBase58} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void topicAddress_shouldDecodeToBase58() {
        String address = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(43), true));
        String hex = TronAddressCodec.base58ToHex(address);
        String topic = "000000000000000000000000" + hex.substring(2);
        assertEquals(address, TronAddressCodec.topicAddressToBase58(topic));
    }

    /**
     * 验证 {@code invalidAddress_shouldFail} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void invalidAddress_shouldFail() {
        assertFalse(TronAddressCodec.isValidBase58("bad-address"));
        assertThrows(IllegalArgumentException.class, () -> TronAddressCodec.hexToBase58("00"));
    }
}
