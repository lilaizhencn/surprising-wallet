package com.surprising.wallet.service.chain.near;

import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearAddressGenerationTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void derivesStableImplicitAccountsFromUnifiedEd25519Tree() {
        NearKeyService first = new NearKeyService(MASTER_SEED);
        NearKeyService restarted = new NearKeyService(MASTER_SEED);

        String user0 = first.address(0, 0, 0);
        String user1 = first.address(1, 0, 0);

        assertEquals(user0, restarted.address(0, 0, 0));
        assertNotEquals(user0, user1);
        assertTrue(user0.matches("[0-9a-f]{64}"));
        assertEquals("m/44'/397'/0'/0'/0'", first.derive(0).derivationPath());
        assertEquals("m/44'/397'/0'/1'/0'", first.derive(1, 0, 0).derivationPath());
        assertTrue(NearKeyService.isValidAccountId(user0));
        assertTrue(NearKeyService.isValidAccountId("alice.testnet"));
        assertTrue(NearKeyService.isValidAccountId("a.testnet"));
        assertFalse(NearKeyService.isValidAccountId("Alice.testnet"));
        assertFalse(NearKeyService.isValidAccountId("-alice.testnet"));
        assertFalse(NearKeyService.isValidAccountId("alice..testnet"));
    }

    @Test
    void nearPathIsSeparatedFromOtherEd25519Chains() {
        Ed25519KeyProvider provider = new Ed25519KeyProvider(Ed25519KeyProvider.decodeMasterSeed(MASTER_SEED));

        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.NEAR, 9).publicKey(),
                provider.derive(Ed25519Chain.SOLANA, 9).publicKey()));
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.NEAR, 9).publicKey(),
                provider.derive(Ed25519Chain.SUI, 9).publicKey()));
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.NEAR, 9).publicKey(),
                provider.derive(Ed25519Chain.POLKADOT, 9).publicKey()));
    }
}
