package com.surprising.wallet.common.key;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class WalletKeyMaterialProviderTest {
    @Test
    void apiAvailabilityReflectsActualMaterialsAndDoesNotRelaxRoleRestrictions() {
        for (var mode : WalletKeyMaterialProvider.Mode.values()) {
            var provider = new WalletKeyMaterialProvider(new WalletKeyConfig(seed(1), seed(2), seed(3), seed(4)), mode);
            assertEquals(mode == WalletKeyMaterialProvider.Mode.WALLET_SERVER, provider.hasApiKeyMaterial());
            if (mode == WalletKeyMaterialProvider.Mode.WALLET_SERVER) {
                assertNotNull(provider.ed25519());
                assertNotNull(provider.sig2Root());
            } else {
                assertThrows(IllegalStateException.class, provider::ed25519);
            }
            if (mode == WalletKeyMaterialProvider.Mode.SIG1) {
                assertNotNull(provider.sig1Root());
                assertThrows(IllegalStateException.class, provider::sig2Root);
            } else {
                assertThrows(IllegalStateException.class, provider::sig1Root);
            }
        }
    }

    private static String seed(int marker) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) marker);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
