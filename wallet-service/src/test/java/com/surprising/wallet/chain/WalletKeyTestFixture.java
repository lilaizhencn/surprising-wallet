package com.surprising.wallet.chain;

import com.surprising.wallet.common.key.WalletKeyConfig;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;

import java.util.Arrays;
import java.util.Base64;

/**
 * Deterministic, public test-only key material for integration tests.
 */
public final class WalletKeyTestFixture {
    private WalletKeyTestFixture() {
    }

    public static WalletKeyMaterialProvider provider() {
        return new WalletKeyMaterialProvider(
                new WalletKeyConfig(seed(0x11), seed(0x22), seed(0x33), seed(0x44)),
                WalletKeyMaterialProvider.Mode.WALLET_SERVER);
    }

    private static String seed(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return Base64.getEncoder().encodeToString(value);
    }
}
