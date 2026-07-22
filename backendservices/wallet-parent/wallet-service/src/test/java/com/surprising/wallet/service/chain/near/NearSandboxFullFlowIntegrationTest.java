package com.surprising.wallet.service.chain.near;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearSandboxFullFlowIntegrationTest {
    private static final String CHAIN = "NEAR";
    private static final String SCANNER = "near-block-scanner";
    private static final BigInteger GENESIS_BALANCE = new BigInteger("10000000000000000000000000000");
    private static final BigInteger STORAGE_DEPOSIT = new BigInteger("20000000000000000000000");
    private static final long SOURCE_USER = 900_001L;
    private static final long USER = 100_001L;
    private static final long EXTERNAL_USER = 100_002L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldExecuteNativeAndNep141FullFlow() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("near.sandbox.flow.enabled"),
                "set -Dnear.sandbox.flow.enabled=true to run the local NEAR sandbox flow");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        WalletKeyMaterialProvider keyMaterial = new WalletKeyMaterialProvider(
                new WalletKeyConfigStore(jdbc), WalletKeyMaterialProvider.Mode.WALLET_SERVER);
        NearKeyService keys = new NearKeyService(keyMaterial);
        NearRpcClient rpc = new NearRpcClient(JSON,
                System.getProperty("near.sandbox.rpc", "http://127.0.0.1:3032"));
        NearTransactionService transactions = new NearTransactionService(
                rpc, new NearTransactionSigner(keys), keys, repository);
        NearDepositScanner scanner = new NearDepositScanner(rpc, repository);

        ChainAddressRecord hot = address(keys, 0L, 0, 0L, "DEPOSIT", CHAIN, "near-hot");
        ChainAddressRecord source = address(keys, SOURCE_USER, 9, 0L, "EXTERNAL", CHAIN, "near-source");
        ChainAddressRecord user = address(keys, USER, 1, 0L, "DEPOSIT", CHAIN, "near-user");
        ChainAddressRecord external = address(keys, EXTERNAL_USER, 1, 0L, "EXTERNAL", CHAIN,
                "near-external");
        for (ChainAddressRecord actor : List.of(hot, source, user, external)) {
            assertTrue(transactions.accountExists(actor.getAddress()), "missing genesis actor " + actor.getAddress());
            repository.upsertChainAddress(actor);
            assertEquals(GENESIS_BALANCE, rpc.accountBalanceYocto(actor.getAddress()));
        }

        repository.updateScanHeight(CHAIN, SCANNER, rpc.latestFinalBlockHeight(), rpc.latestFinalBlockHeight());
        String nativeDeposit = transactions.sendNative(source, user.getAddress(),
                NearTransactionService.toYocto(new BigDecimal("100")));
        transactions.requireSuccessfulConfirmation(nativeDeposit, source.getAddress(), Duration.ofSeconds(30));
        List<DepositEvent> nativeEvents = scanUntil(scanner, nativeDeposit, CHAIN);
        assertTrue(nativeEvents.stream().anyMatch(event -> nativeDeposit.equals(event.txId())));
        assertAmount(new BigDecimal("100"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());
        scanner.scanAndCredit();
        assertAmount(new BigDecimal("100"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());

        String runId = "NEAR-SANDBOX-" + System.currentTimeMillis();
        withdraw(repository, transactions, runId + "-NEAR-WD", user, external, null,
                new BigDecimal("20"));
        assertAmount(new BigDecimal("80"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());

        String nativeCollection = runId + "-NEAR-COLLECT";
        assertEquals(1, repository.createCollectionRecord(nativeCollection, CHAIN, CHAIN,
                user.getAddress(), hot.getAddress(), new BigDecimal("10"), BigDecimal.ZERO, null));
        String nativeCollectionHash = transactions.collectNative(nativeCollection, user, hot.getAddress(),
                NearTransactionService.toYocto(new BigDecimal("10")));
        assertEquals(nativeCollectionHash, transactions.collectNative(nativeCollection, user, hot.getAddress(),
                NearTransactionService.toYocto(new BigDecimal("10"))));
        assertTrue(transactions.confirmCollection(repository.findProfileByChain(CHAIN).orElseThrow(),
                nativeCollection));

        byte[] contractCode = Files.readAllBytes(Path.of(System.getProperty("near.nep141.wasm")));
        tokenFlow(jdbc, repository, keys, rpc, transactions, scanner, contractCode,
                "USDC", 900_002L, runId);
        tokenFlow(jdbc, repository, keys, rpc, transactions, scanner, contractCode,
                "USDT", 900_003L, runId);

        assertFalse(repository.freezeLedgerBalance(CHAIN, "USDC", user.getAccountId(),
                new BigDecimal("1000")));
        assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance where chain='NEAR' "
                + "and (available_balance < 0 or locked_balance < 0 or total_balance < 0)", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance where chain='NEAR' "
                + "and locked_balance <> 0", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from withdrawal_order where chain='NEAR' "
                + "and status <> 'CONFIRMED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from collection_record where chain='NEAR' "
                + "and status <> 'CONFIRMED'", Integer.class));
    }

    private static void tokenFlow(JdbcTemplate jdbc, ChainJdbcRepository repository, NearKeyService keys,
                                  NearRpcClient rpc, NearTransactionService transactions,
                                  NearDepositScanner scanner, byte[] contractCode,
                                  String symbol, long contractUser, String runId) throws Exception {
        ChainAddressRecord source = address(keys, SOURCE_USER, 9, 0L, "EXTERNAL", symbol, "near-source");
        ChainAddressRecord user = address(keys, USER, 1, 0L, "DEPOSIT", symbol, "near-user");
        ChainAddressRecord external = address(keys, EXTERNAL_USER, 1, 0L, "EXTERNAL", symbol,
                "near-external");
        ChainAddressRecord hot = address(keys, 0L, 0, 0L, "DEPOSIT", symbol, "near-hot");
        ChainAddressRecord contract = address(keys, contractUser, 9, 0L, "CONTRACT", symbol,
                "near-contract-" + symbol.toLowerCase());
        for (ChainAddressRecord actor : List.of(source, user, external, hot, contract)) {
            repository.upsertChainAddress(actor);
        }

        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("spec", "ft-1.0.0");
        metadata.put("name", "Sandbox " + symbol);
        metadata.put("symbol", symbol);
        metadata.putNull("icon");
        metadata.putNull("reference");
        metadata.putNull("reference_hash");
        metadata.put("decimals", 6);
        ObjectNode initArgs = JSON.createObjectNode();
        initArgs.put("owner_id", source.getAddress());
        initArgs.put("total_supply", "1000000000");
        initArgs.set("metadata", metadata);
        NearTransactionService.DeployResult deployment = transactions.deployContractAndInit(
                contract, contractCode, "init", JSON.writeValueAsBytes(initArgs),
                300_000_000_000_000L, BigInteger.ZERO);
        transactions.requireSuccessfulConfirmation(deployment.txHash(), contract.getAddress(),
                Duration.ofSeconds(30));

        jdbc.update("""
                update token_config
                   set contract_address = ?, contract_address_base58 = ?, contract_address_hex = null,
                       decimals = 6, standard = 'NEP141', token_standard = 'NEP141', enabled = true,
                       collect_enabled = true, updated_at = now()
                 where chain = 'NEAR' and network = 'testnet' and symbol = ?
                """, contract.getAddress(), contract.getAddress(), symbol);
        TokenDefinition token = repository.findToken(CHAIN, symbol).orElseThrow();

        transactions.storageDeposit(source, token, user.getAddress(), STORAGE_DEPOSIT);
        transactions.storageDeposit(source, token, external.getAddress(), STORAGE_DEPOSIT);
        transactions.storageDeposit(source, token, hot.getAddress(), STORAGE_DEPOSIT);
        assertTrue(transactions.waitForTokenStorageRegistered(token, user.getAddress(), Duration.ofSeconds(10)));
        assertTrue(transactions.waitForTokenStorageRegistered(token, external.getAddress(), Duration.ofSeconds(10)));
        assertTrue(transactions.waitForTokenStorageRegistered(token, hot.getAddress(), Duration.ofSeconds(10)));

        String depositHash = transactions.sendToken(source, token, user.getAddress(), new BigDecimal("100"));
        transactions.requireSuccessfulConfirmation(depositHash, source.getAddress(), Duration.ofSeconds(30));
        List<DepositEvent> deposits = scanUntil(scanner, depositHash, symbol);
        assertTrue(deposits.stream().anyMatch(event -> depositHash.equals(event.txId())));
        assertAmount(new BigDecimal("100"), ledger(repository, symbol, user.getAccountId()).getTotalBalance());
        scanner.scanAndCredit();
        assertAmount(new BigDecimal("100"), ledger(repository, symbol, user.getAccountId()).getTotalBalance());

        withdraw(repository, transactions, runId + "-" + symbol + "-WD", user, external, token,
                new BigDecimal("20"));
        assertAmount(new BigDecimal("80"), ledger(repository, symbol, user.getAccountId()).getTotalBalance());

        String collectionNo = runId + "-" + symbol + "-COLLECT";
        assertEquals(1, repository.createCollectionRecord(collectionNo, CHAIN, symbol,
                user.getAddress(), hot.getAddress(), new BigDecimal("30"), BigDecimal.ZERO, null));
        String collectionHash = transactions.collectToken(collectionNo, user, token, hot.getAddress(),
                new BigDecimal("30"));
        assertEquals(collectionHash, transactions.collectToken(collectionNo, user, token, hot.getAddress(),
                new BigDecimal("30")));
        assertTrue(transactions.confirmCollection(repository.findProfileByChain(CHAIN).orElseThrow(),
                collectionNo));
        assertTokenBalance(rpc, token, user.getAddress(), new BigDecimal("50"));
        assertTokenBalance(rpc, token, external.getAddress(), new BigDecimal("20"));
        assertTokenBalance(rpc, token, hot.getAddress(), new BigDecimal("30"));
    }

    private static void withdraw(ChainJdbcRepository repository, NearTransactionService transactions,
                                 String orderNo, ChainAddressRecord from, ChainAddressRecord to,
                                 TokenDefinition token, BigDecimal amount) {
        String symbol = token == null ? CHAIN : token.getSymbol();
        assertEquals(1, repository.createWithdrawalOrder(orderNo, from.getUserId(), CHAIN, symbol,
                from.getAddress(), from.getAccountId(), to.getAddress(), amount, BigDecimal.ZERO));
        assertTrue(repository.freezeLedgerBalance(CHAIN, symbol, from.getAccountId(), amount));
        assertEquals(1, repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN",
                from.getAddress(), null, null));
        assertEquals(1, repository.claimWithdrawalSigning(CHAIN, orderNo, from.getAddress()));
        String txHash = token == null
                ? transactions.sendNative(from, to.getAddress(), NearTransactionService.toYocto(amount))
                : transactions.sendToken(from, token, to.getAddress(), amount);
        assertEquals(1, repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), txHash));
        assertTrue(transactions.confirmWithdrawal(repository.findProfileByChain(CHAIN).orElseThrow(),
                orderNo, txHash, symbol, from.getAccountId(), amount));
    }

    private static List<DepositEvent> scanUntil(NearDepositScanner scanner, String txHash, String symbol) {
        for (int attempt = 0; attempt < 20; attempt++) {
            List<DepositEvent> events = scanner.scanAndCredit();
            if (events.stream().anyMatch(event -> txHash.equals(event.txId())
                    && symbol.equals(event.assetSymbol()))) {
                return events;
            }
            sleep(250L);
        }
        throw new IllegalStateException("NEAR scanner did not find " + symbol + " transaction " + txHash);
    }

    private static BigDecimal tokenBalance(NearRpcClient rpc, TokenDefinition token, String accountId) {
        ObjectNode args = JSON.createObjectNode();
        args.put("account_id", accountId);
        JsonNode balance = rpc.viewFunctionJson(token.getContractAddress(), "ft_balance_of",
                args.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new BigDecimal(balance.asText("0")).movePointLeft(token.getDecimals()).stripTrailingZeros();
    }

    private static void assertTokenBalance(NearRpcClient rpc, TokenDefinition token,
                                           String accountId, BigDecimal expected) {
        BigDecimal actual = BigDecimal.ZERO;
        for (int attempt = 0; attempt < 40; attempt++) {
            actual = tokenBalance(rpc, token, accountId);
            if (expected.compareTo(actual) == 0) {
                return;
            }
            sleep(250L);
        }
        assertAmount(expected, actual);
    }

    private static ChainAddressRecord address(NearKeyService keys, long userId, int biz, long index,
                                              String role, String symbol, String accountId) {
        return ChainAddressRecord.builder()
                .chain(CHAIN)
                .assetSymbol(symbol)
                .accountId(accountId)
                .userId(userId)
                .biz(biz)
                .addressIndex(index)
                .address(keys.address(userId, biz, index))
                .derivationPath(keys.derive(userId, biz, index).derivationPath())
                .walletRole(role)
                .enabled(true)
                .build();
    }

    private static LedgerBalanceRecord ledger(ChainJdbcRepository repository, String symbol, String accountId) {
        return repository.findLedgerBalance(CHAIN, symbol, accountId)
                .orElseThrow(() -> new IllegalStateException("missing NEAR ledger " + symbol + "/" + accountId));
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected=" + expected + ", actual=" + actual);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("near.db.url", "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("near.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("near.db.password", "wallet123"));
        return dataSource;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for NEAR sandbox", error);
        }
    }
}
