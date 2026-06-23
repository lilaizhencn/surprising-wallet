package com.surprising.wallet.service.chain.sui;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiAddressGenerationTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void derivesStableSuiAddressesFromSuiPath() {
        SuiKeyService keys = new SuiKeyService(MASTER_SEED);
        String first = keys.address(7);
        String second = keys.address(7);
        String other = keys.address(8);

        assertEquals(first, second);
        assertTrue(first.matches("0x[0-9a-f]{64}"));
        assertFalse(first.equals(other));
    }

    @Test
    void suiPathIsSeparatedFromOtherEd25519Chains() {
        Ed25519KeyProvider provider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(MASTER_SEED));

        assertEquals("m/44'/784'/9'/0'/0'", provider.derive(Ed25519Chain.SUI, 9).derivationPath());
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.SUI, 9).publicKey(),
                provider.derive(Ed25519Chain.SOLANA, 9).publicKey()));
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.SUI, 9).publicKey(),
                provider.derive(Ed25519Chain.TON, 9).publicKey()));
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.SUI, 9).publicKey(),
                provider.derive(Ed25519Chain.APTOS, 9).publicKey()));
    }
}
