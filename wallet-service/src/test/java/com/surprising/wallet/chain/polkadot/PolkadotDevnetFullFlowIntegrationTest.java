package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import com.surprising.wallet.wallet.service.HotWalletAddressService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolkadotDevnetFullFlowIntegrationTest {
    private static final String CHAIN = "DOT";
    private static final long SOURCE_USER = 900_001L;
    private static final long USER = 100_001L;
    private static final long EXTERNAL_USER = 100_002L;
    private static final BigInteger TOKEN_SUPPLY = new BigInteger("1000000000");

    @Test
    void shouldExecuteNativeAndAssetHubFullFlow() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("polkadot.devnet.flow.enabled"),
                "set -Dpolkadot.devnet.flow.enabled=true to run the local Polkadot flow");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        UUID tenantId = PolkadotTenantIntegrationFixture.ensureTenant(jdbc);
        WalletKeyMaterialProvider keyMaterial = new WalletKeyMaterialProvider(
                new WalletKeyConfigStore(jdbc), WalletKeyMaterialProvider.Mode.WALLET_SERVER);
        PolkadotKeyService keys = new PolkadotKeyService(keyMaterial);
        ChainRpcNodeService rpcNodes = new ChainRpcNodeService(repository);
        setField(rpcNodes, "environmentName", "dev");
        setField(rpcNodes, "maxConcurrentRequestsPerProvider", 8);
        PolkadotRuntimeClient runtime = new PolkadotRuntimeClient(repository, rpcNodes);
        HotWalletAddressService hotWalletService = new HotWalletAddressService(repository,
                null, null, null, null, null, null, null, null, keys, null);
        PolkadotTransactionService transactions = new PolkadotTransactionService(
                runtime, keys, repository, hotWalletService);
        PolkadotDepositScanner scanner = new PolkadotDepositScanner(runtime, repository);

        ChainAddressRecord hot = address(tenantId, keys, 0L, 0, 0L, "DEPOSIT", CHAIN, "dot-hot");
        ChainAddressRecord source = address(
                tenantId, keys, SOURCE_USER, 9, 0L, "EXTERNAL", CHAIN, "dot-source");
        ChainAddressRecord user = address(
                tenantId, keys, USER, 1, 0L, "DEPOSIT", CHAIN, "dot-user");
        ChainAddressRecord external = address(tenantId, keys, EXTERNAL_USER, 1, 0L,
                "EXTERNAL", CHAIN, "dot-external");
        repository.upsertChainAddress(hot);
        repository.upsertChainAddress(user);
        UUID custodyAddressId = PolkadotTenantIntegrationFixture.attachDepositAddress(jdbc, user);
        assertTrue(runtime.nativeBalance(source.getAddress())
                .compareTo(PolkadotTransactionService.toPlanck(new BigDecimal("1000"))) >= 0,
                "deterministic source was not funded on the local Polkadot node");

        String nativeDeposit = transactions.sendNative(source, user.getAddress(),
                PolkadotTransactionService.toPlanck(new BigDecimal("100")));
        List<DepositEvent> nativeEvents = scanUntil(scanner, nativeDeposit, CHAIN);
        assertTrue(nativeEvents.stream().anyMatch(event -> nativeDeposit.equals(event.txId())));
        assertAmount(new BigDecimal("100"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());
        scanner.scanAndCredit();
        assertAmount(new BigDecimal("100"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());

        String runId = "DOT-DEVNET-" + System.currentTimeMillis();
        withdraw(tenantId, repository, transactions, runId + "-DOT-WD", user, external, null,
                new BigDecimal("20"));
        assertAmount(new BigDecimal("80"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());
        BigInteger hotBeforeCollection = runtime.nativeBalance(hot.getAddress());
        String nativeCollectionHash = collect(tenantId, custodyAddressId, repository, transactions,
                runId + "-DOT-COLLECT", user, hot, null, new BigDecimal("30"));
        scanUntil(scanner, nativeCollectionHash, CHAIN);
        assertInternalCollectionNotCredited(
                jdbc, repository, nativeCollectionHash, CHAIN, user.getAccountId());
        assertEquals(PolkadotTransactionService.toPlanck(new BigDecimal("30")),
                runtime.nativeBalance(hot.getAddress()).subtract(hotBeforeCollection));

        tokenFlow(tenantId, custodyAddressId, jdbc, repository, keys, runtime, transactions, scanner,
                source, user, external, hot, "USDC", "31337", true, runId);
        tokenFlow(tenantId, custodyAddressId, jdbc, repository, keys, runtime, transactions, scanner,
                source, user, external, hot, "USDT", "1984", false, runId);

        assertFalse(repository.freezeLedgerBalance(CHAIN, "USDC", user.getAccountId(),
                new BigDecimal("1000")));
        assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance where chain='DOT' "
                + "and (available_balance < 0 or locked_balance < 0 or total_balance < 0)", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance where chain='DOT' "
                + "and locked_balance <> 0", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from withdrawal_order where chain='DOT' "
                + "and status <> 'CONFIRMED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from collection_record where chain='DOT' "
                + "and status <> 'CONFIRMED'", Integer.class));
        assertAmount(new BigDecimal("80"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());
    }

    private static void tokenFlow(UUID tenantId, UUID custodyAddressId,
                                  JdbcTemplate jdbc, ChainJdbcRepository repository, PolkadotKeyService keys,
                                  PolkadotRuntimeClient runtime, PolkadotTransactionService transactions,
                                  PolkadotDepositScanner scanner, ChainAddressRecord nativeSource,
                                  ChainAddressRecord nativeUser, ChainAddressRecord nativeExternal,
                                  ChainAddressRecord nativeHot, String symbol, String assetId,
                                  boolean expectGasTopUp, String runId) {
        PolkadotTransactionService.DeployAssetResult deployed = transactions.deployAsset(nativeSource,
                assetId, "Local " + symbol, symbol, 6, BigInteger.ONE, TOKEN_SUPPLY, true);
        assertEquals(assetId, deployed.assetId());
        PolkadotRuntimeClient.AssetInfo info = runtime.assetInfo(assetId);
        assertTrue(info.exists());
        assertEquals(symbol, info.symbol());
        assertEquals(6, info.decimals());
        assertEquals(TOKEN_SUPPLY, info.supply());

        jdbc.update("""
                update token_config
                   set contract_address = ?, contract_address_hex = null,
                       contract_address_base58 = null, decimals = 6,
                       standard = 'ASSET_HUB_ASSET', token_standard = 'ASSET_HUB_ASSET',
                       enabled = true, collect_enabled = true, updated_at = now()
                 where chain = 'DOT' and network = 'westend' and symbol = ?
                """, assetId, symbol);
        TokenDefinition token = repository.findToken(CHAIN, symbol).orElseThrow();

        ChainAddressRecord source = tokenAddress(nativeSource, symbol, "dot-source-" + symbol.toLowerCase());
        ChainAddressRecord user = tokenAddress(nativeUser, symbol, "dot-user-" + symbol.toLowerCase());
        ChainAddressRecord external = tokenAddress(nativeExternal, symbol,
                "dot-external-" + symbol.toLowerCase());
        ChainAddressRecord hot = tokenAddress(nativeHot, symbol, "dot-hot-" + symbol.toLowerCase());
        repository.upsertChainAddress(user);
        repository.upsertChainAddress(hot);

        String depositHash = transactions.sendAsset(source, token, user.getAddress(), new BigDecimal("100"));
        List<DepositEvent> deposits = scanUntil(scanner, depositHash, symbol);
        assertTrue(deposits.stream().anyMatch(event -> depositHash.equals(event.txId())));
        assertAmount(new BigDecimal("100"), ledger(repository, symbol, user.getAccountId()).getTotalBalance());
        scanner.scanAndCredit();
        assertAmount(new BigDecimal("100"), ledger(repository, symbol, user.getAccountId()).getTotalBalance());

        BigInteger gasBefore = runtime.assetHubNativeBalance(user.getAddress());
        withdraw(tenantId, repository, transactions, runId + "-" + symbol + "-WD", user, external, token,
                new BigDecimal("20"));
        BigInteger gasAfter = runtime.assetHubNativeBalance(user.getAddress());
        assertEquals(expectGasTopUp, gasAfter.compareTo(gasBefore) > 0,
                "unexpected Asset Hub gas top-up result; before=" + gasBefore + ", after=" + gasAfter);
        String collectionHash = collect(tenantId, custodyAddressId, repository, transactions,
                runId + "-" + symbol + "-COLLECT", user, hot, token, new BigDecimal("30"));
        scanUntil(scanner, collectionHash, symbol);
        assertInternalCollectionNotCredited(jdbc, repository, collectionHash, symbol, user.getAccountId());

        assertAssetBalance(runtime, assetId, user.getAddress(), new BigDecimal("50"));
        assertAssetBalance(runtime, assetId, external.getAddress(), new BigDecimal("20"));
        assertAssetBalance(runtime, assetId, hot.getAddress(), new BigDecimal("30"));
        BigDecimal tenantCustody = assetBalance(runtime, assetId, user.getAddress())
                .add(assetBalance(runtime, assetId, hot.getAddress()));
        assertAmount(ledger(repository, symbol, user.getAccountId()).getTotalBalance(), tenantCustody);
    }

    private static void withdraw(UUID tenantId, ChainJdbcRepository repository,
                                 PolkadotTransactionService transactions,
                                 String orderNo, ChainAddressRecord from, ChainAddressRecord to,
                                 TokenDefinition token, BigDecimal amount) {
        String symbol = token == null ? CHAIN : token.getSymbol();
        assertEquals(1, repository.createTenantWithdrawalOrder(
                tenantId, orderNo, from.getUserId(), CHAIN, symbol,
                from.getAddress(), from.getAccountId(), to.getAddress(), amount, BigDecimal.ZERO));
        assertTrue(repository.freezeLedgerBalance(tenantId, CHAIN, symbol, from.getAccountId(), amount));
        assertEquals(1, repository.updateWithdrawalStatus(tenantId, CHAIN, orderNo, "FROZEN",
                from.getAddress(), null, null));
        assertEquals(1, repository.claimWithdrawalSigning(
                tenantId, CHAIN, orderNo, from.getAddress()));
        String txHash = token == null
                ? transactions.sendNative(from, to.getAddress(), PolkadotTransactionService.toPlanck(amount))
                : transactions.sendAsset(from, token, to.getAddress(), amount);
        assertEquals(1, repository.markWithdrawalSent(
                tenantId, CHAIN, orderNo, from.getAddress(), txHash));
        assertTrue(transactions.confirmWithdrawal(tenantId,
                repository.findProfileByChain(CHAIN).orElseThrow(), orderNo, txHash,
                symbol, from.getAccountId(), amount));
    }

    private static String collect(UUID tenantId, UUID custodyAddressId,
                                  ChainJdbcRepository repository, PolkadotTransactionService transactions,
                                  String collectionNo, ChainAddressRecord from, ChainAddressRecord hot,
                                  TokenDefinition token, BigDecimal amount) {
        String symbol = token == null ? CHAIN : token.getSymbol();
        assertEquals(1, repository.createCollectionRecord(
                tenantId, custodyAddressId, collectionNo, CHAIN, symbol,
                from.getAddress(), hot.getAddress(), amount, BigDecimal.ZERO, null));
        String txHash = token == null
                ? transactions.collectNative(
                        tenantId, collectionNo, from, hot.getAddress(),
                        PolkadotTransactionService.toPlanck(amount))
                : transactions.collectAsset(tenantId, collectionNo, from, token, hot.getAddress(), amount);
        assertEquals(txHash, token == null
                ? transactions.collectNative(
                        tenantId, collectionNo, from, hot.getAddress(),
                        PolkadotTransactionService.toPlanck(amount))
                : transactions.collectAsset(tenantId, collectionNo, from, token, hot.getAddress(), amount));
        assertTrue(transactions.confirmCollection(
                tenantId, repository.findProfileByChain(CHAIN).orElseThrow(), collectionNo, symbol));
        return txHash;
    }

    private static void assertInternalCollectionNotCredited(
            JdbcTemplate jdbc, ChainJdbcRepository repository, String txHash,
            String symbol, String userAccountId) {
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from deposit_record
                 where chain = ? and tx_hash = ? and asset_symbol = ?
                """, Integer.class, CHAIN, txHash, symbol));
        assertAmount(new BigDecimal("80"),
                ledger(repository, symbol, userAccountId).getTotalBalance());
    }

    private static List<DepositEvent> scanUntil(PolkadotDepositScanner scanner,
                                                String txHash, String symbol) {
        for (int attempt = 0; attempt < 40; attempt++) {
            List<DepositEvent> events = scanner.scanAndCredit();
            if (events.stream().anyMatch(event -> txHash.equals(event.txId())
                    && symbol.equals(event.assetSymbol()))) {
                return events;
            }
            sleep(500L);
        }
        throw new IllegalStateException("Polkadot scanner did not find " + symbol + " transaction " + txHash);
    }

    private static void assertAssetBalance(PolkadotRuntimeClient runtime, String assetId,
                                           String address, BigDecimal expected) {
        assertAmount(expected, assetBalance(runtime, assetId, address));
    }

    private static BigDecimal assetBalance(PolkadotRuntimeClient runtime, String assetId, String address) {
        return new BigDecimal(runtime.assetBalance(assetId, address)).movePointLeft(6).stripTrailingZeros();
    }

    private static ChainAddressRecord address(UUID tenantId, PolkadotKeyService keys,
                                              long userId, int biz, long index,
                                              String role, String symbol, String accountId) {
        return ChainAddressRecord.builder()
                .tenantId(tenantId)
                .chain(CHAIN)
                .assetSymbol(symbol)
                .accountId(accountId)
                .userId(userId)
                .biz(biz)
                .addressIndex(index)
                .address(keys.address(userId, biz, index, 42))
                .derivationPath(keys.derive(userId, biz, index).derivationPath())
                .walletRole(role)
                .enabled(true)
                .build();
    }

    private static ChainAddressRecord tokenAddress(ChainAddressRecord nativeAddress,
                                                   String symbol, String accountId) {
        return ChainAddressRecord.builder()
                .tenantId(nativeAddress.getTenantId())
                .chain(CHAIN)
                .assetSymbol(symbol)
                .accountId(accountId)
                .userId(nativeAddress.getUserId())
                .biz(nativeAddress.getBiz())
                .addressIndex(nativeAddress.getAddressIndex())
                .address(nativeAddress.getAddress())
                .derivationPath(nativeAddress.getDerivationPath())
                .walletRole(nativeAddress.getWalletRole())
                .enabled(true)
                .build();
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String symbol, String accountId) {
        return repository.findLedgerBalance(CHAIN, symbol, accountId)
                .orElseThrow(() -> new IllegalStateException("missing DOT ledger " + symbol + "/" + accountId));
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected=" + expected + ", actual=" + actual);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("polkadot.db.url", "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("polkadot.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("polkadot.db.password", "wallet123"));
        return dataSource;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Polkadot devnet", error);
        }
    }
}
