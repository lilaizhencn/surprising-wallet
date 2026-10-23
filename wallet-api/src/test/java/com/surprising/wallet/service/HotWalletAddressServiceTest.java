package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.aptos.AptosKeyService;
import com.surprising.wallet.chain.solana.SolanaKeyService;
import com.surprising.wallet.chain.sui.SuiKeyService;
import com.surprising.wallet.chain.ton.TonKeyService;
import com.surprising.wallet.config.PubKeyConfig;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.bitcoinj.base.LegacyAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 {@code HotWalletAddressServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class HotWalletAddressServiceTest {
    /**
     * 保存 {@code XPUB_2}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String XPUB_2 =
            "tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT";
    /**
     * 保存 {@code ED25519_SEED}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final String ED25519_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * 验证 {@code hyperCoreUsesSecp256k1AccountDerivation} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void hyperCoreUsesSecp256k1AccountDerivation() {
        var material = org.mockito.Mockito.mock(com.surprising.wallet.common.key.WalletKeyMaterialProvider.class);
        org.mockito.Mockito.when(material.sig2PublicRoot()).thenReturn(Bip32Node.decode(XPUB_2));
        PubKeyConfig pubKeyConfig = new PubKeyConfig(material);
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
        assertEquals("0x2aced92da0e5dd90498ef43da87274aa7b01be1b", address.getAddress());
    }

    /**
     * 验证 {@code evmChainsShareAddressAtTheSameTenantCoordinates} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void evmChainsShareAddressAtTheSameTenantCoordinates() {
        var material = org.mockito.Mockito.mock(com.surprising.wallet.common.key.WalletKeyMaterialProvider.class);
        org.mockito.Mockito.when(material.sig2PublicRoot()).thenReturn(Bip32Node.decode(XPUB_2));
        PubKeyConfig pubKeyConfig = new PubKeyConfig(material);
        HotWalletAddressService service = new HotWalletAddressService(
                null, pubKeyConfig, null, null, null, null, null, null, null, null, null);
        AccountChainProfile ethereum = profile("ETH", "evm", "ETH", 60);
        AccountChainProfile bnb = profile("BNB", "evm", "BNB", 714);

        ChainAddressRecord ethAddress = service.deriveAddress(
                ethereum, 130, 1016, 1, "DEPOSIT");
        ChainAddressRecord bnbAddress = service.deriveAddress(
                bnb, 130, 1016, 1, "DEPOSIT");

        assertEquals("m/44/60/1016/130/1", ethAddress.getDerivationPath());
        assertEquals(ethAddress.getDerivationPath(), bnbAddress.getDerivationPath());
        assertEquals(ethAddress.getAddress(), bnbAddress.getAddress());
        assertEquals("0x2194d0e84405809794c6ef9e433acc3f781a4c7e", ethAddress.getAddress());
    }

    @ParameterizedTest
    @CsvSource({"BTC,MAINNET", "BTC,TESTNET", "BTC,REGTEST", "LTC,MAINNET", "LTC,TESTNET", "LTC,REGTEST",
            "BCH,MAINNET", "BCH,TESTNET", "BCH,REGTEST", "DOGE,MAINNET", "DOGE,TESTNET", "DOGE,REGTEST"})
    void apiAndSignerDeriveTheSameMultisigScriptOnEverySupportedNetwork(String chain, BitcoinLikeNetwork network) {
        String firstPublic = Bip32Node.getMasterKey(seedBytes(0x11)).pubSerialize(0, false);
        String secondPublic = Bip32Node.getMasterKey(seedBytes(0x22)).pubSerialize(0, false);
        String recoveryPublic = Bip32Node.getMasterKey(seedBytes(0x33)).pubSerialize(0, false);
        var apiKeys = WalletKeyMaterialProvider.forApi(seed(0x22), seed(0x44), firstPublic, recoveryPublic);
        var publicKeys = new PubKeyConfig(apiKeys);
        var signer = new com.surprising.wallet.sig.first.config.PubKeyConfig(
                WalletKeyMaterialProvider.forSig1(seed(0x11), secondPublic, recoveryPublic));
        int coinType = switch (chain) { case "BTC" -> 0; case "LTC" -> 2; case "BCH" -> 145; default -> 3; };
        var params = network.parameters(chain);
        boolean legacy = chain.equals("BCH") || chain.equals("DOGE");
        var metadata = legacy ? publicKeys.genLegacyThreeTwoAddressMetadata(params, coinType, 7, 3, 2)
                : publicKeys.genThreeTwoAddressMetadata(params, coinType, 7, 3, 2);
        var key = new UtxoSigningRequest.Key(7, 3, 2, metadata.redeemScript());
        var asset = new UtxoSigningRequest.Asset(chain, network, 1, 8, coinType);
        assertEquals(legacy ? signer.genRedeemScript(key, asset) : signer.genWitnessScript(key, asset),
                legacy ? metadata.redeemScript() : metadata.witnessScript());
        assertEquals(3, Arrays.stream(metadata.publicKeys().split(",")).distinct().count());
        assertEquals("m/44/" + coinType + "/3/7/2", metadata.path());

        var profile = profile(chain, "bitcoin-like", chain, coinType);
        profile.setNetwork(network.name().toLowerCase(java.util.Locale.ROOT));
        var hotWallet = bitcoinLikeService(publicKeys).deriveAddress(profile, 7, 3, 2, "DEPOSIT");
        String expected = chain.equals("BCH")
                ? BitcoinCashAddressCodec.fromLegacy(LegacyAddress.fromBase58(params, metadata.address()),
                        ((BitcoinCashNetworkParameters) params).cashPrefix()) : metadata.address();
        assertEquals(expected, hotWallet.getAddress());
        assertEquals(metadata.path(), hotWallet.getDerivationPath());
    }

    @ParameterizedTest @ValueSource(strings = {"BTC", "LTC", "BCH", "DOGE"})
    void unknownNetworksCannotSilentlyDeriveATestnetAddress(String chain) {
        var profile = profile(chain, "bitcoin-like", chain, 0);
        profile.setNetwork("unconfigured");
        var material = WalletKeyMaterialProvider.forApi(seed(0x22), seed(0x44),
                Bip32Node.getMasterKey(seedBytes(0x11)).pubSerialize(0, false),
                Bip32Node.getMasterKey(seedBytes(0x33)).pubSerialize(0, false));
        assertThrows(IllegalArgumentException.class,
                () -> bitcoinLikeService(new PubKeyConfig(material)).deriveAddress(profile, 7, 3, 2, "DEPOSIT"));
    }

    private static HotWalletAddressService bitcoinLikeService(PubKeyConfig keys) {
        return new HotWalletAddressService(
                org.mockito.Mockito.mock(com.surprising.wallet.repository.ChainJdbcRepository.class), keys,
                org.mockito.Mockito.mock(SolanaKeyService.class), org.mockito.Mockito.mock(SuiKeyService.class),
                org.mockito.Mockito.mock(AptosKeyService.class), org.mockito.Mockito.mock(TonKeyService.class),
                org.mockito.Mockito.mock(com.surprising.wallet.chain.xrp.XrpKeyService.class),
                org.mockito.Mockito.mock(com.surprising.wallet.chain.cardano.CardanoKeyService.class),
                org.mockito.Mockito.mock(com.surprising.wallet.chain.near.NearKeyService.class),
                org.mockito.Mockito.mock(com.surprising.wallet.chain.polkadot.PolkadotKeyService.class),
                org.mockito.Mockito.mock(com.surprising.wallet.chain.monero.MoneroAddressService.class));
    }

    private static byte[] seedBytes(int marker) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) marker);
        return bytes;
    }

    private static String seed(int marker) { return Base64.getEncoder().encodeToString(seedBytes(marker)); }

    /**
     * 验证 {@code tenantEd25519AddressesIncludeNamespaceAndSubject} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code tenantEd25519Profiles} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Stream<Arguments> tenantEd25519Profiles() {
        return Stream.of(
                Arguments.of(profile("SOLANA", "solana", "SOL", 501), 501),
                Arguments.of(profile("SUI", "sui", "SUI", 784), 784),
                Arguments.of(profile("APTOS", "aptos", "APT", 637), 637),
                Arguments.of(profile("TON", "ton", "TON", 607), 607));
    }

    /**
     * 验证 {@code profile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainProfile profile(String chain, String family, String symbol, int coinType) {
        return AccountChainProfile.builder()
                .chain(chain)
                .network("testnet")
                .family(family)
                .bip44CoinType(coinType)
                .nativeSymbol(symbol)
                .build();
    }

    /**
     * 验证 {@code tenantEd25519Service} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
