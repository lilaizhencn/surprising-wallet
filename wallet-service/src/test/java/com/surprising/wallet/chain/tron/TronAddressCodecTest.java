package com.surprising.wallet.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TronAddressCodecTest {
    @Test
    void base58AndHex_shouldRoundTrip() {
        String address = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(42), true));
        String hex = TronAddressCodec.base58ToHex(address);
        assertEquals(42, hex.length());
        assertTrue(hex.startsWith("41"));
        assertEquals(address, TronAddressCodec.hexToBase58(hex));
    }

    @Test
    void topicAddress_shouldDecodeToBase58() {
        String address = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(43), true));
        String hex = TronAddressCodec.base58ToHex(address);
        String topic = "000000000000000000000000" + hex.substring(2);
        assertEquals(address, TronAddressCodec.topicAddressToBase58(topic));
    }

    @Test
    void invalidAddress_shouldFail() {
        assertFalse(TronAddressCodec.isValidBase58("bad-address"));
        assertThrows(IllegalArgumentException.class, () -> TronAddressCodec.hexToBase58("00"));
    }
}
