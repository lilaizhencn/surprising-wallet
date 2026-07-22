package com.surprising.wallet.service.chain.cardano;

import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.TxResult;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoDevnetFullFlowIntegrationTest {
    private static final String CHAIN = "ADA";
    private static final long SOURCE_USER = 900_001L;
    private static final long USER = 100_001L;
    private static final long EXTERNAL_USER = 100_002L;
    private static final BigInteger TOKEN_SUPPLY = new BigInteger("1000000000");

    @Test
    void shouldExecuteNativeAndNativeAssetFullFlow() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cardano.devnet.flow.enabled"),
                "set -Dcardano.devnet.flow.enabled=true to run the local Cardano devnet flow");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        WalletKeyMaterialProvider keyMaterial = new WalletKeyMaterialProvider(
                new WalletKeyConfigStore(jdbc), WalletKeyMaterialProvider.Mode.WALLET_SERVER);
        CardanoKeyService keys = new CardanoKeyService(keyMaterial);
        ChainRpcNodeService rpcNodes = new ChainRpcNodeService(repository);
        setField(rpcNodes, "environmentName", "dev");
        setField(rpcNodes, "maxConcurrentRequestsPerProvider", 4);
        CardanoBackendClient backend = new CardanoBackendClient(repository, rpcNodes);
        CardanoTransactionService transactions = new CardanoTransactionService(backend, keys, repository);
        CardanoDepositScanner scanner = new CardanoDepositScanner(backend, repository);

        ChainAddressRecord hot = address(keys, 0L, 0, 0L, "DEPOSIT", CHAIN, "cardano-hot");
        ChainAddressRecord source = address(keys, SOURCE_USER, 9, 0L, "EXTERNAL", CHAIN, "cardano-source");
        ChainAddressRecord user = address(keys, USER, 1, 0L, "DEPOSIT", CHAIN, "cardano-user");
        ChainAddressRecord external = address(keys, EXTERNAL_USER, 1, 0L, "EXTERNAL", CHAIN,
                "cardano-external");
        repository.upsertChainAddress(hot);
        repository.upsertChainAddress(user);
        assertTrue(nativeAtomicBalance(backend, source.getAddress())
                .compareTo(CardanoTransactionService.toLovelace(new BigDecimal("1000"))) >= 0,
                "deterministic source was not funded by the devnet topup API");

        String nativeDeposit = transactions.sendNative(source, user.getAddress(),
                CardanoTransactionService.toLovelace(new BigDecimal("100")));
        List<DepositEvent> nativeEvents = scanUntil(scanner, nativeDeposit, CHAIN);
        assertTrue(nativeEvents.stream().anyMatch(event -> nativeDeposit.equals(event.txId())));
        assertAmount(new BigDecimal("100"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());
        scanner.scanAndCredit();
        assertAmount(new BigDecimal("100"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());

        String runId = "ADA-DEVNET-" + System.currentTimeMillis();
        withdraw(repository, transactions, runId + "-ADA-WD", user, external, null,
                new BigDecimal("20"));
        assertAmount(new BigDecimal("80"), ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());

        String nativeCollection = runId + "-ADA-COLLECT";
        assertEquals(1, repository.createCollectionRecord(nativeCollection, CHAIN, CHAIN,
                user.getAddress(), hot.getAddress(), new BigDecimal("10"), BigDecimal.ZERO, null));
        String nativeCollectionHash = transactions.collectNative(nativeCollection, user, hot.getAddress(),
                CardanoTransactionService.toLovelace(new BigDecimal("10")));
        assertEquals(nativeCollectionHash, transactions.collectNative(nativeCollection, user, hot.getAddress(),
                CardanoTransactionService.toLovelace(new BigDecimal("10"))));
        assertTrue(transactions.confirmCollection(repository.findProfileByChain(CHAIN).orElseThrow(),
                nativeCollection));

        BigDecimal attachedAda = tokenFlow(jdbc, repository, keys, backend, transactions, scanner,
                source, user, external, hot, "USDC", runId);
        attachedAda = attachedAda.add(tokenFlow(jdbc, repository, keys, backend, transactions, scanner,
                source, user, external, hot, "USDT", runId));

        assertFalse(repository.freezeLedgerBalance(CHAIN, "USDC", user.getAccountId(),
                new BigDecimal("1000")));
        assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance where chain='ADA' "
                + "and (available_balance < 0 or locked_balance < 0 or total_balance < 0)", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from ledger_balance where chain='ADA' "
                + "and locked_balance <> 0", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from withdrawal_order where chain='ADA' "
                + "and status <> 'CONFIRMED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from collection_record where chain='ADA' "
                + "and status <> 'CONFIRMED'", Integer.class));
        assertTrue(attachedAda.signum() > 0, "native-asset deposits must carry minimum ADA");
        assertAmount(new BigDecimal("80").add(attachedAda),
                ledger(repository, CHAIN, user.getAccountId()).getTotalBalance());
    }

    private static BigDecimal tokenFlow(JdbcTemplate jdbc, ChainJdbcRepository repository, CardanoKeyService keys,
                                        CardanoBackendClient backend, CardanoTransactionService transactions,
                                        CardanoDepositScanner scanner, ChainAddressRecord nativeSource,
                                        ChainAddressRecord nativeUser, ChainAddressRecord nativeExternal,
                                        ChainAddressRecord nativeHot, String symbol, String runId) throws Exception {
        String unit = mint(backend, keys, nativeSource, symbol);
        jdbc.update("""
                update token_config
                   set contract_address = ?, contract_address_hex = ?, contract_address_base58 = null,
                       decimals = 6, standard = 'CARDANO_NATIVE_ASSET',
                       token_standard = 'CARDANO_NATIVE_ASSET', enabled = true,
                       collect_enabled = true, updated_at = now()
                 where chain = 'ADA' and network = 'preprod' and symbol = ?
                """, unit.substring(0, 56) + "." + unit.substring(56), unit, symbol);
        TokenDefinition token = repository.findToken(CHAIN, symbol).orElseThrow();

        ChainAddressRecord source = tokenAddress(nativeSource, symbol, "cardano-source-" + symbol.toLowerCase());
        ChainAddressRecord user = tokenAddress(nativeUser, symbol, "cardano-user-" + symbol.toLowerCase());
        ChainAddressRecord external = tokenAddress(nativeExternal, symbol,
                "cardano-external-" + symbol.toLowerCase());
        ChainAddressRecord hot = tokenAddress(nativeHot, symbol, "cardano-hot-" + symbol.toLowerCase());
        repository.upsertChainAddress(user);
        repository.upsertChainAddress(hot);

        String depositHash = transactions.sendToken(source, token, user.getAddress(), new BigDecimal("100"));
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

        assertTokenBalance(backend, unit, user.getAddress(), new BigDecimal("50"));
        assertTokenBalance(backend, unit, external.getAddress(), new BigDecimal("20"));
        assertTokenBalance(backend, unit, hot.getAddress(), new BigDecimal("30"));
        return deposits.stream()
                .filter(event -> depositHash.equals(event.txId()) && CHAIN.equals(event.assetSymbol()))
                .map(DepositEvent::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String mint(CardanoBackendClient backend, CardanoKeyService keys,
                               ChainAddressRecord source, String symbol) throws Exception {
        Ed25519DerivedKey derived = keys.derive(source.getUserId(), source.getBiz(), source.getAddressIndex());
        SecretKey secretKey = SecretKey.create(derived.privateSeed());
        ScriptPubkey policy = ScriptPubkey.create(VerificationKey.create(derived.publicKey()));
        String unit = policy.getPolicyId()
                + HexFormat.of().formatHex(symbol.getBytes(StandardCharsets.UTF_8));
        Tx tx = new Tx()
                .mintAssets(policy, new Asset(symbol, TOKEN_SUPPLY), source.getAddress())
                .from(source.getAddress())
                .withChangeAddress(source.getAddress());
        TxResult result = backend.withBackend((service, node, profile) -> new QuickTxBuilder(service)
                .compose(tx)
                .feePayer(source.getAddress())
                .withSigner(SignerProviders.signerFrom(secretKey))
                .completeAndWait(Duration.ofSeconds(30)));
        assertTrue(result != null && result.isSuccessful(),
                "Cardano native asset mint failed: " + (result == null ? "<empty>" : result.getResponse()));
        return unit;
    }

    private static void withdraw(ChainJdbcRepository repository, CardanoTransactionService transactions,
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
                ? transactions.sendNative(from, to.getAddress(), CardanoTransactionService.toLovelace(amount))
                : transactions.sendToken(from, token, to.getAddress(), amount);
        assertEquals(1, repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), txHash));
        assertTrue(transactions.confirmWithdrawal(repository.findProfileByChain(CHAIN).orElseThrow(),
                orderNo, txHash, symbol, from.getAccountId(), amount));
    }

    private static List<DepositEvent> scanUntil(CardanoDepositScanner scanner, String txHash, String symbol) {
        for (int attempt = 0; attempt < 40; attempt++) {
            List<DepositEvent> events = scanner.scanAndCredit();
            if (events.stream().anyMatch(event -> txHash.equals(event.txId())
                    && symbol.equals(event.assetSymbol()))) {
                return events;
            }
            sleep(500L);
        }
        throw new IllegalStateException("Cardano scanner did not find " + symbol + " transaction " + txHash);
    }

    private static BigInteger nativeAtomicBalance(CardanoBackendClient backend, String address) {
        return atomicBalance(backend, CardanoAssetUnit.LOVELACE, address);
    }

    private static BigInteger atomicBalance(CardanoBackendClient backend, String unit, String address) {
        return backend.withBackend((service, node, profile) -> CardanoBackendClient.requireSuccess(
                        service.getUtxoService().getUtxos(address, 100, 1, OrderEnum.asc), "address UTXOs"))
                .stream()
                .flatMap(utxo -> utxo.getAmount().stream())
                .filter(amount -> unit.equals(CardanoAssetUnit.normalize(amount.getUnit())))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static void assertTokenBalance(CardanoBackendClient backend, String unit,
                                           String address, BigDecimal expected) {
        BigDecimal actual = BigDecimal.ZERO;
        for (int attempt = 0; attempt < 40; attempt++) {
            actual = new BigDecimal(atomicBalance(backend, unit, address)).movePointLeft(6).stripTrailingZeros();
            if (expected.compareTo(actual) == 0) {
                return;
            }
            sleep(500L);
        }
        assertAmount(expected, actual);
    }

    private static ChainAddressRecord address(CardanoKeyService keys, long userId, int biz, long index,
                                              String role, String symbol, String accountId) {
        return ChainAddressRecord.builder()
                .chain(CHAIN)
                .assetSymbol(symbol)
                .accountId(accountId)
                .userId(userId)
                .biz(biz)
                .addressIndex(index)
                .address(keys.address(userId, biz, index, false))
                .derivationPath(keys.derive(userId, biz, index).derivationPath())
                .walletRole(role)
                .enabled(true)
                .build();
    }

    private static ChainAddressRecord tokenAddress(ChainAddressRecord nativeAddress,
                                                   String symbol, String accountId) {
        return ChainAddressRecord.builder()
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
                .orElseThrow(() -> new IllegalStateException("missing ADA ledger " + symbol + "/" + accountId));
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected=" + expected + ", actual=" + actual);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("cardano.db.url", "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("cardano.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("cardano.db.password", "wallet123"));
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
            throw new IllegalStateException("interrupted while waiting for Cardano devnet", error);
        }
    }
}
