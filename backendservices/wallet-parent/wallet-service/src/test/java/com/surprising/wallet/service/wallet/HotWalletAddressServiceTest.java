package com.surprising.wallet.service.wallet;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.service.chain.aptos.AptosKeyService;
import com.surprising.wallet.service.chain.solana.SolanaKeyService;
import com.surprising.wallet.service.chain.sui.SuiKeyService;
import com.surprising.wallet.service.chain.ton.TonKeyService;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotWalletAddressServiceTest {
    private static final String XPUB_2 =
            "tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT";
    private static final String ED25519_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void hyperCoreUsesSecp256k1AccountDerivation() {
        Bip32Node node = Bip32Node.decode(XPUB_2);
        PubKeyConfig pubKeyConfig = new PubKeyConfig(node, node, node);
        HotWalletAddressService service = new HotWalletAddressService(
                null, pubKeyConfig, null, null, null, null, null, null, null, null, null);
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("HYPERCORE")
                .network("testnet")
                .family("hypercore")
                .bip44CoinType(60)
                .nativeSymbol("USDC")
                .build();

        ChainAddressRecord address = service.deriveAddress(profile, 1, 0, 0, "DEPOSIT");

        assertEquals("HYPERCORE", address.getChain());
        assertEquals("USDC", address.getAssetSymbol());
        assertEquals("m/44/60/0/1/0", address.getDerivationPath());
        assertEquals(address.getAddress(), address.getOwnerAddress());
        assertTrue(address.getAddress().matches("^0x[0-9a-f]{40}$"));
    }

    @ParameterizedTest
    @MethodSource("tenantEd25519Profiles")
    void tenantEd25519AddressesIncludeNamespaceAndSubject(
            AccountChainProfile profile, int coinType) {
        HotWalletAddressService service = tenantEd25519Service();

        ChainAddressRecord first = service.deriveAddress(profile, 100_000, 1_000, 0, "DEPOSIT");
        ChainAddressRecord nextSubject = service.deriveAddress(profile, 100_001, 1_000, 0, "DEPOSIT");
        ChainAddressRecord nextTenant = service.deriveAddress(profile, 100_000, 1_001, 0, "DEPOSIT");

        assertEquals("m/44'/" + coinType + "'/1000'/100000'/0'", first.getDerivationPath());
        assertNotEquals(first.getAddress(), nextSubject.getAddress());
        assertNotEquals(first.getAddress(), nextTenant.getAddress());
        assertNotEquals(nextSubject.getAddress(), nextTenant.getAddress());
    }

    private static Stream<Arguments> tenantEd25519Profiles() {
        return Stream.of(
                Arguments.of(profile("SOLANA", "solana", "SOL", 501), 501),
                Arguments.of(profile("SUI", "sui", "SUI", 784), 784),
                Arguments.of(profile("APTOS", "aptos", "APT", 637), 637),
                Arguments.of(profile("TON", "ton", "TON", 607), 607));
    }

    private static AccountChainProfile profile(String chain, String family, String symbol, int coinType) {
        return AccountChainProfile.builder()
                .chain(chain)
                .network("testnet")
                .family(family)
                .bip44CoinType(coinType)
                .nativeSymbol(symbol)
                .build();
    }

    private static HotWalletAddressService tenantEd25519Service() {
        return new HotWalletAddressService(
                null,
                null,
                new SolanaKeyService(ED25519_SEED),
                new SuiKeyService(ED25519_SEED),
                new AptosKeyService(ED25519_SEED),
                new TonKeyService(ED25519_SEED),
                null,
                null,
                null,
                null,
                null);
    }
}
