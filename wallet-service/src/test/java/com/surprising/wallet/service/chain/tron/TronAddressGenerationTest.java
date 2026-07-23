package com.surprising.wallet.service.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TronAddressGenerationTest {
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
