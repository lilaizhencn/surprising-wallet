package com.surprising.wallet.service.chain.cardano;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.surprising.wallet.common.key.Ed25519Chain;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.Ed25519KeyProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoAddressGenerationTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void derivesStableShelleyEnterpriseAddresses() {
        CardanoKeyService first = new CardanoKeyService(MASTER_SEED);
        CardanoKeyService restarted = new CardanoKeyService(MASTER_SEED);

        String testnet = first.address(7, 0, 3, false);
        String testnetAgain = restarted.address(7, 0, 3, false);
        String mainnet = first.address(7, 0, 3, true);

        assertEquals(testnet, testnetAgain);
        assertTrue(testnet.startsWith("addr_test1"));
        assertTrue(mainnet.startsWith("addr1"));
        assertTrue(CardanoKeyService.isValidAddress(testnet));
        assertTrue(CardanoKeyService.isValidAddress(mainnet));
    }

    @Test
    void rejectsCorruptedAddresses() {
        CardanoKeyService service = new CardanoKeyService(MASTER_SEED);
        String address = service.address(1, 0, 0, false);
        String corrupted = address.substring(0, address.length() - 1)
                + (address.endsWith("q") ? "p" : "q");

        assertFalse(CardanoKeyService.isValidAddress(corrupted));
        assertFalse(CardanoKeyService.isValidAddress("addr_test1not-valid"));
        assertFalse(CardanoKeyService.isValidAddress(null));
    }

    @Test
    void keepsCardanoKeySpaceSeparatedFromOtherEd25519Chains() {
        Ed25519KeyProvider provider = new Ed25519KeyProvider(
                Ed25519KeyProvider.decodeMasterSeed(MASTER_SEED));

        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.CARDANO, 9).publicKey(),
                provider.derive(Ed25519Chain.SOLANA, 9).publicKey()));
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.CARDANO, 9).publicKey(),
                provider.derive(Ed25519Chain.POLKADOT, 9).publicKey()));
        assertFalse(Arrays.equals(provider.derive(Ed25519Chain.CARDANO, 9).publicKey(),
                provider.derive(Ed25519Chain.NEAR, 9).publicKey()));
    }

    @Test
    void signingKeyMatchesAddressPublicKey() throws Exception {
        CardanoKeyService service = new CardanoKeyService(MASTER_SEED);
        Ed25519DerivedKey derived = service.derive(1, 0, 2);
        byte[] signerPublicKey = KeyGenUtil.getPublicKeyFromPrivateKey(
                SecretKey.create(derived.privateSeed())).getBytes();

        assertTrue(Arrays.equals(derived.publicKey(), signerPublicKey));
        assertTrue(CardanoKeyService.isValidAddress(
                CardanoKeyService.enterpriseAddress(signerPublicKey, false)));
    }
}
