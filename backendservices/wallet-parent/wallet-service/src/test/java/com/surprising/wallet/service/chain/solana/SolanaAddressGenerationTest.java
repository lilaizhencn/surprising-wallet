package com.surprising.wallet.service.chain.solana;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolanaAddressGenerationTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void derivesStableBase58AddressesAndAssociatedTokenAccounts() {
        SolanaKeyService first = new SolanaKeyService(MASTER_SEED);
        SolanaKeyService restarted = new SolanaKeyService(MASTER_SEED);
        String user0 = first.account(0).getPublicKeyBase58();
        String user1 = first.account(1).getPublicKeyBase58();

        assertEquals(user0, restarted.account(0).getPublicKeyBase58());
        assertNotEquals(user0, user1);
        assertTrue(user0.length() >= 32 && user0.length() <= 44);

        SolanaAddressService addresses = new SolanaAddressService(first, null);
        String usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
        String ata = addresses.associatedTokenAddress(user0, usdcMint);
        assertEquals(ata, addresses.associatedTokenAddress(user0, usdcMint));
        assertNotEquals(user0, ata);
    }
}
