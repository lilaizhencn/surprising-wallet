package com.surprising.wallet.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.tron.trident.core.key.KeyPair;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TronTridentKeyFactoryTest {
    @Test
    void sameEcKey_shouldProduceSameTronAddress() {
        ECKey ecKey = ECKey.fromPrivate(BigInteger.valueOf(2), true);
        String first = TronTridentKeyFactory.toBase58Address(ecKey);
        String second = TronTridentKeyFactory.toBase58Address(ecKey);
        assertEquals(first, second);
        assertTrue(first.startsWith("T"));
    }

    @Test
    void privateKeyHex_shouldCreateTridentKeyPair() {
        String normalized = TronTridentKeyFactory.normalizePrivateKeyHex("0x2");
        KeyPair keyPair = TronTridentKeyFactory.fromPrivateKeyHex(normalized);
        assertEquals(64, normalized.length());
        assertTrue(keyPair.toBase58CheckAddress().startsWith("T"));
    }

    @Test
    void invalidPrivateKey_shouldFail() {
        assertThrows(IllegalArgumentException.class, () -> TronTridentKeyFactory.fromPrivateKeyHex("0x0"));
        assertThrows(IllegalArgumentException.class, () -> TronTridentKeyFactory.fromPrivateKeyHex("not-hex"));
    }

    @Test
    void address_shouldMatchLegacyImplementation() {
        ECKey ecKey = ECKey.fromPrivate(BigInteger.valueOf(123456789), true);
        assertEquals(TronTridentKeyFactory.legacyBase58Address(ecKey), TronTridentKeyFactory.toBase58Address(ecKey));
    }

    @Test
    void bitcoinEcKeyToTronKeyPair_shouldRemainCompatible() {
        ECKey ecKey = ECKey.fromPrivate(BigInteger.valueOf(987654321), true);
        KeyPair keyPair = TronTridentKeyFactory.fromBitcoinEcKey(ecKey);
        assertEquals(TronTridentKeyFactory.legacyBase58Address(ecKey), keyPair.toBase58CheckAddress());
    }
}
