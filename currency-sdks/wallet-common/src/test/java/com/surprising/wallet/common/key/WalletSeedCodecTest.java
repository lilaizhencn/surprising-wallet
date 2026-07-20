package com.surprising.wallet.common.key;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletSeedCodecTest {
    @Test
    void acceptsFourDifferentBase64Encoded32ByteSeeds() {
        WalletKeyConfig config = new WalletKeyConfig(
                seed(1), seed(2), seed(3), seed(4), null, null, "test");

        assertDoesNotThrow(() -> WalletSeedCodec.validate(config));
    }

    @Test
    void rejectsWrongLengthAndDuplicateSeeds() {
        assertThrows(IllegalArgumentException.class,
                () -> WalletSeedCodec.decode("sig1Seed", Base64.getEncoder().encodeToString(new byte[31])));
        WalletKeyConfig duplicate = new WalletKeyConfig(
                seed(1), seed(1), seed(3), seed(4), null, null, "test");
        assertThrows(IllegalArgumentException.class, () -> WalletSeedCodec.validate(duplicate));
    }

    private static String seed(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return Base64.getEncoder().encodeToString(bytes);
    }
}
