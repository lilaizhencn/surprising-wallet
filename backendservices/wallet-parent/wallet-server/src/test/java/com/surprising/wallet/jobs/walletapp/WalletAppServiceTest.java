package com.surprising.wallet.jobs.walletapp;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletAppServiceTest {
    @Test
    void dotTokensReuseNativeDepositAddressRows() {
        assertTrue(WalletAppService.usesMirroredNativeTokenAddress("DOT"));
    }

    @Test
    void tokenAddressStrategyMatchesAccountModels() {
        assertTrue(WalletAppService.usesMirroredNativeTokenAddress("ETH"));
        assertTrue(WalletAppService.usesMirroredNativeTokenAddress("TRON"));
        assertTrue(WalletAppService.usesMirroredNativeTokenAddress("ADA"));
        assertTrue(WalletAppService.usesMirroredNativeTokenAddress("XRP"));
        assertTrue(WalletAppService.usesMirroredNativeTokenAddress("NEAR"));
        assertTrue(WalletAppService.usesMirroredNativeTokenAddress("HYPERCORE"));
        assertFalse(WalletAppService.usesMirroredNativeTokenAddress("SOLANA"));
        assertFalse(WalletAppService.usesMirroredNativeTokenAddress("TON"));
        assertFalse(WalletAppService.usesMirroredNativeTokenAddress("XMR"));
    }

    @Test
    void preparedTokenAddressChainsRequireDepositPageSetupBeforeWithdrawal() {
        assertTrue(WalletAppService.requiresPreparedTokenAddress("XRP"));
        assertTrue(WalletAppService.requiresPreparedTokenAddress("NEAR"));
        assertTrue(WalletAppService.requiresPreparedTokenAddress("SOLANA"));
        assertTrue(WalletAppService.requiresPreparedTokenAddress("TON"));
        assertTrue(WalletAppService.requiresPreparedTokenAddress("APTOS"));
        assertTrue(WalletAppService.requiresPreparedTokenAddress("SUI"));
        assertFalse(WalletAppService.requiresPreparedTokenAddress("ETH"));
        assertFalse(WalletAppService.requiresPreparedTokenAddress("TRON"));
        assertFalse(WalletAppService.requiresPreparedTokenAddress("ADA"));
        assertFalse(WalletAppService.requiresPreparedTokenAddress("DOT"));
        assertFalse(WalletAppService.requiresPreparedTokenAddress("HYPERCORE"));
    }

    @Test
    void tokenAddressStrategyIsVisibleToClients() {
        assertEquals("NATIVE", WalletAppService.tokenAddressStrategy("ADA", true));
        assertEquals("MIRROR_NATIVE_ACCOUNT", WalletAppService.tokenAddressStrategy("DOT", false));
        assertEquals("PREPARED_NATIVE_ACCOUNT", WalletAppService.tokenAddressStrategy("NEAR", false));
        assertEquals("PREPARED_NATIVE_ACCOUNT", WalletAppService.tokenAddressStrategy("XRP", false));
        assertEquals("PREPARED_TOKEN_ACCOUNT", WalletAppService.tokenAddressStrategy("SOLANA", false));
        assertEquals("MIRROR_NATIVE_ACCOUNT", WalletAppService.tokenAddressStrategy("HYPERCORE", false));
    }

    @Test
    void forcedNativeDepositAddressDoesNotReuseExistingIndex() {
        ChainAddressRecord existing = nativeAddress("MANTLE", "MNT", "0xExisting");

        assertEquals(existing.getAddressIndex(), WalletAppService.preferredNativeAddressIndex(existing, false));
        assertNull(WalletAppService.preferredNativeAddressIndex(existing, true));
        assertNull(WalletAppService.preferredNativeAddressIndex(null, false));
    }

    @Test
    void dotTokenWithdrawalMaterializesRecipientTokenAddressFromNativeAddress() throws Exception {
        String address = "5DOTRecipientAddress";
        FakeRepository repository = new FakeRepository(nativeAddress("DOT", "DOT", address));
        WalletAppService service = service(repository);
        Object asset = assetMeta("DOT", "USDC", false, "DOT", "polkadot",
                "westend", 6, "1984", "ASSET_HUB_ASSET");

        Object target = withdrawalTarget(service, asset, address);

        assertEquals(address, recordValue(target, "requestedAddress"));
        assertEquals(address, recordValue(target, "broadcastAddress"));
        assertNotNull(repository.upserted);
        assertEquals("DOT", repository.upserted.getChain());
        assertEquals("USDC", repository.upserted.getAssetSymbol());
        assertEquals(address, repository.upserted.getAddress());
        assertEquals(repository.nativeAddress.getAccountId(), repository.upserted.getAccountId());
        assertEquals(repository.nativeAddress.getAddressIndex(), repository.upserted.getAddressIndex());
    }

    @Test
    void nearTokenWithdrawalRequiresPreparedTokenDepositAddress() throws Exception {
        FakeRepository repository = new FakeRepository(nativeAddress("NEAR", "NEAR",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        WalletAppService service = service(repository);
        Object asset = assetMeta("NEAR", "USDC", false, "NEAR", "near",
                "testnet", 6, "usdc.fakes.testnet", "NEP141");

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> withdrawalTarget(service, asset, repository.nativeAddress.getAddress()));
        ResponseStatusException cause = assertInstanceOf(ResponseStatusException.class, exception.getCause());
        assertEquals(HttpStatus.BAD_REQUEST, cause.getStatusCode());
        assertTrue(cause.getReason().contains("deposit page"));
    }

    @Test
    void xmrWithdrawalUsesHotWalletSourceAfterCollection() throws Exception {
        ChainAddressRecord userAddress = nativeAddress("XMR", "XMR", "89UserDepositAddress");
        ChainAddressRecord hotAddress = hotAddress("XMR", "XMR", "47DefaultHotWalletAddress");
        FakeRepository repository = new FakeRepository(userAddress, hotAddress);
        WalletAppService service = service(repository);
        Object asset = assetMeta("XMR", "XMR", true, "XMR", "monero",
                "regtest", 12, null, null);
        Object spend = spendAccount(userAddress.getAccountId(), userAddress.getAddress());

        String sourceAddress = withdrawalSourceAddress(service, asset, spend);

        assertEquals(hotAddress.getAddress(), sourceAddress);
    }

    private static WalletAppService service(ChainJdbcRepository repository) {
        return new WalletAppService(null, repository, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ChainAddressRecord nativeAddress(String chain, String symbol, String address) {
        return ChainAddressRecord.builder()
                .chain(chain)
                .assetSymbol(symbol)
                .accountId(address)
                .userId(7L)
                .biz(0)
                .addressIndex(3L)
                .address(address)
                .ownerAddress(address)
                .derivationPath("m/44/0/0/7/3")
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    private static ChainAddressRecord hotAddress(String chain, String symbol, String address) {
        return ChainAddressRecord.builder()
                .chain(chain)
                .assetSymbol(symbol)
                .accountId(address)
                .userId(0L)
                .biz(0)
                .addressIndex(0L)
                .address(address)
                .ownerAddress(address)
                .derivationPath("m/44/0/0/0/0")
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    private static Object assetMeta(String chain, String symbol, boolean nativeAsset, String nativeSymbol,
                                    String family, String network, int decimals,
                                    String contractAddress, String standard) throws Exception {
        Class<?> type = nestedClass("AssetMeta");
        Constructor<?> constructor = type.getDeclaredConstructor(
                String.class, String.class, boolean.class, String.class, String.class, String.class,
                int.class, String.class, String.class, BigDecimal.class);
        constructor.setAccessible(true);
        return constructor.newInstance(chain, symbol, nativeAsset, nativeSymbol, family, network,
                decimals, contractAddress, standard, BigDecimal.ZERO);
    }

    private static Object withdrawalTarget(WalletAppService service, Object asset, String address) throws Exception {
        Method method = WalletAppService.class.getDeclaredMethod("withdrawalTarget", asset.getClass(), String.class);
        method.setAccessible(true);
        return method.invoke(service, asset, address);
    }

    private static String withdrawalSourceAddress(WalletAppService service, Object asset, Object spend)
            throws Exception {
        Method method = WalletAppService.class.getDeclaredMethod(
                "withdrawalSourceAddress", asset.getClass(), spend.getClass());
        method.setAccessible(true);
        return String.valueOf(method.invoke(service, asset, spend));
    }

    private static Object spendAccount(String accountId, String address) throws Exception {
        Class<?> type = nestedClass("SpendAccount");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(accountId, address);
    }

    private static String recordValue(Object record, String methodName) throws Exception {
        Method method = record.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return String.valueOf(method.invoke(record));
    }

    private static Class<?> nestedClass(String simpleName) {
        return Arrays.stream(WalletAppService.class.getDeclaredClasses())
                .filter(type -> simpleName.equals(type.getSimpleName()))
                .findFirst()
                .orElseThrow();
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private final ChainAddressRecord nativeAddress;
        private final ChainAddressRecord hotAddress;
        private ChainAddressRecord upserted;

        private FakeRepository(ChainAddressRecord nativeAddress) {
            this(nativeAddress, null);
        }

        private FakeRepository(ChainAddressRecord nativeAddress, ChainAddressRecord hotAddress) {
            super(null);
            this.nativeAddress = nativeAddress;
            this.hotAddress = hotAddress;
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String assetSymbol,
                                                                      String address) {
            if (matches(upserted, chain, assetSymbol, address)) {
                return Optional.of(upserted);
            }
            if (matches(hotAddress, chain, assetSymbol, address)) {
                return Optional.of(hotAddress);
            }
            if (matches(nativeAddress, chain, assetSymbol, address)) {
                return Optional.of(nativeAddress);
            }
            return Optional.empty();
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String address) {
            if (matches(upserted, chain, null, address)) {
                return Optional.of(upserted);
            }
            if (matches(hotAddress, chain, null, address)) {
                return Optional.of(hotAddress);
            }
            if (matches(nativeAddress, chain, null, address)) {
                return Optional.of(nativeAddress);
            }
            return Optional.empty();
        }

        @Override
        public int upsertChainAddress(ChainAddressRecord address) {
            upserted = address;
            return 1;
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddress(String chain, String assetSymbol, long userId,
                                                            int biz, long addressIndex, String walletRole) {
            if (matches(upserted, chain, assetSymbol, userId, biz, addressIndex, walletRole)) {
                return Optional.of(upserted);
            }
            if (matches(hotAddress, chain, assetSymbol, userId, biz, addressIndex, walletRole)) {
                return Optional.of(hotAddress);
            }
            if (matches(nativeAddress, chain, assetSymbol, userId, biz, addressIndex, walletRole)) {
                return Optional.of(nativeAddress);
            }
            return Optional.empty();
        }

        @Override
        public List<ChainAddressRecord> listDefaultHotAddressCandidates(String chain, String assetSymbol) {
            return matches(hotAddress, chain, assetSymbol, 0L, 0, 0L, "DEPOSIT")
                    ? List.of(hotAddress)
                    : List.of();
        }

        private static boolean matches(ChainAddressRecord record, String chain, String symbol, String address) {
            return record != null
                    && record.getChain().equals(chain)
                    && (symbol == null || record.getAssetSymbol().equals(symbol))
                    && record.getAddress().equals(address);
        }

        private static boolean matches(ChainAddressRecord record, String chain, String symbol, long userId,
                                       int biz, long addressIndex, String walletRole) {
            return record != null
                    && record.getChain().equals(chain)
                    && record.getAssetSymbol().equals(symbol)
                    && record.getUserId() == userId
                    && record.getBiz() == biz
                    && record.getAddressIndex() == addressIndex
                    && record.getWalletRole().equals(walletRole);
        }
    }
}
