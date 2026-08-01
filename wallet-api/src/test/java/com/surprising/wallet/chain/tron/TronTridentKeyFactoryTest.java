package com.surprising.wallet.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.tron.trident.core.key.KeyPair;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TronTridentKeyFactoryTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TronTridentKeyFactoryTest {
    /**
     * 验证 {@code sameEcKey_shouldProduceSameTronAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void sameEcKey_shouldProduceSameTronAddress() {
        ECKey ecKey = ECKey.fromPrivate(BigInteger.valueOf(2), true);
        String first = TronTridentKeyFactory.toBase58Address(ecKey);
        String second = TronTridentKeyFactory.toBase58Address(ecKey);
        assertEquals(first, second);
        assertTrue(first.startsWith("T"));
    }

    /**
     * 验证 {@code privateKeyHex_shouldCreateTridentKeyPair} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void privateKeyHex_shouldCreateTridentKeyPair() {
        String normalized = TronTridentKeyFactory.normalizePrivateKeyHex("0x2");
        KeyPair keyPair = TronTridentKeyFactory.fromPrivateKeyHex(normalized);
        assertEquals(64, normalized.length());
        assertTrue(keyPair.toBase58CheckAddress().startsWith("T"));
    }

    /**
     * 验证 {@code invalidPrivateKey_shouldFail} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void invalidPrivateKey_shouldFail() {
        assertThrows(IllegalArgumentException.class, () -> TronTridentKeyFactory.fromPrivateKeyHex("0x0"));
        assertThrows(IllegalArgumentException.class, () -> TronTridentKeyFactory.fromPrivateKeyHex("not-hex"));
    }

    /**
     * 验证 {@code address_shouldMatchLegacyImplementation} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void address_shouldMatchLegacyImplementation() {
        ECKey ecKey = ECKey.fromPrivate(BigInteger.valueOf(123456789), true);
        assertEquals(TronTridentKeyFactory.legacyBase58Address(ecKey), TronTridentKeyFactory.toBase58Address(ecKey));
    }

    /**
     * 验证 {@code bitcoinEcKeyToTronKeyPair_shouldRemainCompatible} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void bitcoinEcKeyToTronKeyPair_shouldRemainCompatible() {
        ECKey ecKey = ECKey.fromPrivate(BigInteger.valueOf(987654321), true);
        KeyPair keyPair = TronTridentKeyFactory.fromBitcoinEcKey(ecKey);
        assertEquals(TronTridentKeyFactory.legacyBase58Address(ecKey), keyPair.toBase58CheckAddress());
    }
}
