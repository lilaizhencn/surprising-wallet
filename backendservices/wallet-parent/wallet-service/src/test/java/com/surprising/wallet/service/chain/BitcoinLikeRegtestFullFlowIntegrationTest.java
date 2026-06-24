package com.surprising.wallet.service.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real local-regtest verification for the unified bitcoin-like UTXO runtime.
 *
 * <p>The test talks to bitcoind/litecoind/dogecoind/BCHN through scripts/regtest, creates real
 * chain transactions and confirmations, then verifies deposit_record,
 * utxo_record, withdrawal_order, collection_record, ledger_balance and
 * idempotency against PostgreSQL. DB writes are rolled back; regtest chain
 * transactions remain in the local nodes.</p>
 *
 * <p>This is deliberately opt-in because it requires Docker, BTC/LTC/DOGE/BCH
 * regtest nodes, and PostgreSQL.</p>
 */
class BitcoinLikeRegtestFullFlowIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient RPC_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final int REQUIRED_CONFIRMATIONS = 6;

    private static final RegtestChain BTC = new RegtestChain(
            ChainType.BTC,
            "scripts/regtest/bitcoin-regtest.sh",
            "wallet-cli",
            "cli",
            new BigDecimal("1.00000000"),
            new BigDecimal("0.10000000"),
            new BigDecimal("0.00001000"),
            new BigDecimal("0.25000000"),
            new BigDecimal("0.00001000"));

    private static final RegtestChain LTC = new RegtestChain(
            ChainType.LTC,
            "scripts/regtest/litecoin-regtest.sh",
            "wallet-cli",
            "cli",
            new BigDecimal("1.00000000"),
            new BigDecimal("0.10000000"),
            new BigDecimal("0.00001000"),
            new BigDecimal("0.25000000"),
            new BigDecimal("0.00001000"));

    private static final RegtestChain DOGE = new RegtestChain(
            ChainType.DOGE,
            "scripts/regtest/dogecoin-regtest.sh",
            "cli",
            "cli",
            new BigDecimal("100.00000000"),
            new BigDecimal("10.00000000"),
            new BigDecimal("1.00000000"),
            new BigDecimal("25.00000000"),
            new BigDecimal("1.00000000"));

    private static final RegtestChain BCH = new RegtestChain(
            ChainType.BCH,
            "scripts/regtest/bitcoincash-regtest.sh",
            "wallet-cli",
            "cli",
            new BigDecimal("1.00000000"),
            new BigDecimal("0.10000000"),
            new BigDecimal("0.00001000"),
            new BigDecimal("0.25000000"),
            new BigDecimal("0.00001000"));

    @Test
    void bitcoinLikeRegtestFlowsMustUseUnifiedUtxoRecordEndToEnd() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bitcoinlike.regtest.enabled"),
                "set -Dbitcoinlike.regtest.enabled=true to run real BTC/LTC/DOGE/BCH regtest flow validation");
        Path root = repoRoot();
        Assumptions.assumeTrue(Files.isExecutable(root.resolve(BTC.script())),
                "missing executable " + BTC.script());
        Assumptions.assumeTrue(Files.isExecutable(root.resolve(LTC.script())),
                "missing executable " + LTC.script());
        Assumptions.assumeTrue(Files.isExecutable(root.resolve(DOGE.script())),
                "missing executable " + DOGE.script());
        Assumptions.assumeTrue(Files.isExecutable(root.resolve(BCH.script())),
                "missing executable " + BCH.script());

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("BITCOINLIKE_REGTEST_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("BITCOINLIKE_REGTEST_DB_USER", "wallet"),
                env("BITCOINLIKE_REGTEST_DB_PASSWORD", ""));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
            try {
                ensureBitcoinLikeProfiles(jdbc);
                runFullFlow(root, BTC, jdbc, repository);
                runFullFlow(root, LTC, jdbc, repository);
                runFullFlow(root, DOGE, jdbc, repository);
                runFullFlow(root, BCH, jdbc, repository);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void bitcoinLikeLedgerAndRoutingGuardsMustStayAtomicUnderConcurrency() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bitcoinlike.concurrency.enabled"),
                "set -Dbitcoinlike.concurrency.enabled=true to run PostgreSQL concurrency guard validation");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("BITCOINLIKE_REGTEST_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("BITCOINLIKE_REGTEST_DB_USER", "wallet"),
                env("BITCOINLIKE_REGTEST_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        String runId = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        try {
            ensureBitcoinLikeProfiles(jdbc);
            runConcurrencyGuards(BTC, jdbc, repository, runId);
            runConcurrencyGuards(LTC, jdbc, repository, runId);
            runConcurrencyGuards(DOGE, jdbc, repository, runId);
            runConcurrencyGuards(BCH, jdbc, repository, runId);
        } finally {
            cleanupConcurrencyRows(jdbc, runId);
        }
    }

    @Test
    void bitcoinLikeRegtestNodesMustBroadcastBulkDepositsAndWithdrawalsConcurrently() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bitcoinlike.broadcast.enabled"),
                "set -Dbitcoinlike.broadcast.enabled=true to run real node concurrent broadcast validation");
        Path root = repoRoot();
        int depositCount = intProperty("bitcoinlike.broadcast.deposits", 24);
        int withdrawalCount = intProperty("bitcoinlike.broadcast.withdrawals", 12);
        assertTrue(depositCount > 0 && depositCount <= 200, "deposit count must be between 1 and 200");
        assertTrue(withdrawalCount > 0 && withdrawalCount <= 100, "withdrawal count must be between 1 and 100");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("BITCOINLIKE_REGTEST_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("BITCOINLIKE_REGTEST_DB_USER", "wallet"),
                env("BITCOINLIKE_REGTEST_DB_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
        String runId = "b" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        List<String> cleanupTxids = Collections.synchronizedList(new ArrayList<>());

        try {
            ensureBitcoinLikeProfiles(jdbc);
            runBulkBroadcastFlow(root, BTC, jdbc, repository, runId, cleanupTxids, depositCount, withdrawalCount);
            runBulkBroadcastFlow(root, LTC, jdbc, repository, runId, cleanupTxids, depositCount, withdrawalCount);
            runBulkBroadcastFlow(root, DOGE, jdbc, repository, runId, cleanupTxids, depositCount, withdrawalCount);
            runBulkBroadcastFlow(root, BCH, jdbc, repository, runId, cleanupTxids, depositCount, withdrawalCount);
        } finally {
            cleanupBroadcastRows(jdbc, runId, cleanupTxids);
        }
    }

    private void runFullFlow(Path root, RegtestChain chain, JdbcTemplate jdbc,
                             ChainJdbcRepository repository) throws Exception {
        String chainName = chain.chainType().name();
        JsonNode info = json(rpc(root, chain, chain.chainRpc(), "getblockchaininfo"));
        assertEquals("regtest", info.path("chain").asText(), chainName + " node must be regtest");

        String accountId = "regtest-" + chainName.toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
        String depositAddress = walletRpc(root, chain, "getnewaddress").trim();
        RealTx depositTx = sendAndMine(root, chain, depositAddress, chain.depositAmount());
        Output depositOutput = findOutput(depositTx.raw(), depositAddress, chain.depositAmount());

        repository.upsertUtxo(
                chainName, chainName, depositTx.txid(), depositOutput.vout(),
                depositAddress, chain.depositAmount(), depositTx.blockHeight(),
                depositTx.confirmations(), false);
        DepositEvent depositEvent = new DepositEvent(
                chain.chainType(), chainName, depositTx.txid(), null, depositAddress,
                chain.depositAmount(), depositTx.blockHeight(), depositTx.confirmations(),
                null, depositTx.raw().toString());
        assertTrue(repository.recordAndCreditDeposit(
                depositEvent, depositOutput.vout(), REQUIRED_CONFIRMATIONS, accountId));
        assertFalse(repository.recordAndCreditDeposit(
                depositEvent, depositOutput.vout(), REQUIRED_CONFIRMATIONS, accountId));
        assertEquals(1L, count(jdbc, """
                select count(*) from deposit_record
                where chain = ? and tx_hash = ? and log_index = ? and credited = true
                """, chainName, depositTx.txid(), (long) depositOutput.vout()));
        assertLedger(jdbc, chainName, accountId, chain.depositAmount(), BigDecimal.ZERO, chain.depositAmount());
        assertNoNegativeLedger(jdbc, chainName);

        BigDecimal withdrawDebit = chain.withdrawAmount().add(chain.withdrawFee());
        String withdrawAddress = walletRpc(root, chain, "getnewaddress").trim();
        String withdrawalOrder = chainName.toLowerCase(Locale.ROOT) + "-regtest-withdraw-" + UUID.randomUUID();
        assertEquals(1, repository.createWithdrawalOrder(
                withdrawalOrder, 9901L, chainName, chainName,
                withdrawAddress, chain.withdrawAmount(), chain.withdrawFee()));
        assertEquals(0, repository.createWithdrawalOrder(
                withdrawalOrder, 9901L, chainName, chainName,
                withdrawAddress, chain.withdrawAmount(), chain.withdrawFee()));
        assertTrue(repository.freezeLedgerBalance(chainName, chainName, accountId, withdrawDebit));
        assertFalse(repository.freezeLedgerBalance(chainName, chainName, accountId, chain.depositAmount()),
                "freeze must reject over-spend and keep ledger non-negative");
        assertEquals(1, repository.updateWithdrawalStatus(
                chainName, withdrawalOrder, "FROZEN", depositAddress, null, null));
        RuntimeAsset currency = runtimeAsset(repository, chainName);
        WithdrawTransaction signing = repository.createBitcoinLikeSigningTransaction(
                currency,
                "WITHDRAW",
                withdrawalOrder,
                WithdrawTransaction.builder()
                        .txId("signing")
                        .balance(chain.depositAmount())
                        .signature("{}")
                        .currency(currency.getIndex())
                        .status(Constants.SIGNING)
                        .build());
        assertTrue(repository.findBitcoinLikeSigningTransactionById(currency, signing.getId()).isPresent());
        String signingLockRef = signing.getId().toString();
        assertEquals(1, repository.lockUtxo(chainName, depositTx.txid(), depositOutput.vout(), signingLockRef));
        assertEquals(0, repository.lockUtxo(chainName, depositTx.txid(), depositOutput.vout(), signingLockRef + "-again"));
        assertEquals(1, repository.updateWithdrawalStatus(
                chainName, withdrawalOrder, "UTXO_LOCKED", depositAddress, null, null));
        assertEquals(1, repository.updateWithdrawalStatus(
                chainName, withdrawalOrder, "SIGNING", depositAddress, null, null));

        RealTx withdrawTx = sendAndMine(root, chain, withdrawAddress, chain.withdrawAmount());
        findOutput(withdrawTx.raw(), withdrawAddress, chain.withdrawAmount());
        signing.setTxId(withdrawTx.txid());
        signing.setStatus(Constants.SENT);
        signing.setSignature(withdrawTx.raw().toString());
        assertEquals(1, repository.updateBitcoinLikeSigningTransaction(currency, signing));
        assertTrue(repository.bitcoinLikeSigningTransactionExists(currency, withdrawTx.txid()));
        assertTrue(repository.findBitcoinLikeSigningTransactionByTxId(currency, withdrawTx.txid()).isPresent());
        assertEquals(1, repository.updateWithdrawalStatus(
                chainName, withdrawalOrder, "SENT", depositAddress, withdrawTx.txid(), null));
        assertEquals(1, repository.markWithdrawalConfirmed(chainName, withdrawalOrder, withdrawTx.txid()));
        assertEquals(0, repository.markWithdrawalConfirmed(chainName, withdrawalOrder, withdrawTx.txid()));
        assertEquals(1, repository.markUtxosSpent(chainName, signingLockRef, withdrawTx.txid()));
        assertEquals(0, repository.markUtxosSpent(chainName, signingLockRef, withdrawTx.txid()));
        assertTrue(repository.settleLockedDebit(chainName, chainName, accountId, withdrawDebit));
        assertFalse(repository.settleLockedDebit(chainName, chainName, accountId, withdrawDebit));
        assertEquals(Optional.of("CONFIRMED"), repository.findWithdrawalStatus(chainName, withdrawalOrder));
        assertEquals(Optional.of(withdrawTx.txid()), repository.findWithdrawalTxHash(chainName, withdrawalOrder));
        BigDecimal afterWithdraw = chain.depositAmount().subtract(withdrawDebit);
        assertLedger(jdbc, chainName, accountId, afterWithdraw, BigDecimal.ZERO, afterWithdraw);
        assertEquals("SPENT", scalarString(jdbc, """
                select state from utxo_record
                where chain = ? and tx_hash = ? and vout = ?
                """, chainName, depositTx.txid(), depositOutput.vout()));
        assertNoNegativeLedger(jdbc, chainName);

        String collectionSource = walletRpc(root, chain, "getnewaddress").trim();
        String hotAddress = walletRpc(root, chain, "getnewaddress").trim();
        RealTx collectionDeposit = sendAndMine(root, chain, collectionSource, chain.collectionDepositAmount());
        Output collectionOutput = findOutput(
                collectionDeposit.raw(), collectionSource, chain.collectionDepositAmount());
        repository.upsertUtxo(
                chainName, chainName, collectionDeposit.txid(), collectionOutput.vout(),
                collectionSource, chain.collectionDepositAmount(), collectionDeposit.blockHeight(),
                collectionDeposit.confirmations(), false);
        DepositEvent collectionDepositEvent = new DepositEvent(
                chain.chainType(), chainName, collectionDeposit.txid(), null, collectionSource,
                chain.collectionDepositAmount(), collectionDeposit.blockHeight(),
                collectionDeposit.confirmations(), null, collectionDeposit.raw().toString());
        assertTrue(repository.recordAndCreditDeposit(
                collectionDepositEvent, collectionOutput.vout(), REQUIRED_CONFIRMATIONS, accountId));
        assertFalse(repository.recordAndCreditDeposit(
                collectionDepositEvent, collectionOutput.vout(), REQUIRED_CONFIRMATIONS, accountId));
        BigDecimal afterCollectionDeposit = afterWithdraw.add(chain.collectionDepositAmount());
        assertLedger(jdbc, chainName, accountId, afterCollectionDeposit, BigDecimal.ZERO, afterCollectionDeposit);

        String collectionNo = chainName.toLowerCase(Locale.ROOT) + "-regtest-collection-" + UUID.randomUUID();
        BigDecimal collectionOutputAmount = chain.collectionDepositAmount().subtract(chain.collectionFee());
        assertTrue(collectionOutputAmount.compareTo(BigDecimal.ZERO) > 0);
        assertEquals(1, repository.createCollectionRecord(
                collectionNo, chainName, chainName, collectionSource, hotAddress,
                collectionOutputAmount, chain.collectionFee(), "{}"));
        assertEquals(0, repository.createCollectionRecord(
                collectionNo, chainName, chainName, collectionSource, hotAddress,
                collectionOutputAmount, chain.collectionFee(), "{}"));
        assertEquals(1, repository.claimCollectionSigning(chainName, collectionNo, "{}"));
        assertEquals(0, repository.claimCollectionSigning(chainName, collectionNo, "{}"));
        WithdrawTransaction collectionSigning = repository.createBitcoinLikeSigningTransaction(
                currency,
                "COLLECTION",
                collectionNo,
                WithdrawTransaction.builder()
                        .txId("signing")
                        .balance(chain.collectionDepositAmount())
                        .signature("{}")
                        .currency(currency.getIndex())
                        .status(Constants.SIGNING)
                        .build());
        String collectionLockRef = collectionSigning.getId().toString();
        assertEquals(1, repository.lockUtxo(
                chainName, collectionDeposit.txid(), collectionOutput.vout(), collectionLockRef));
        assertEquals(0, repository.releaseUtxos(chainName, "unknown-" + collectionNo));

        RealTx collectionTx = sendAndMine(root, chain, hotAddress, collectionOutputAmount);
        findOutput(collectionTx.raw(), hotAddress, collectionOutputAmount);
        collectionSigning.setTxId(collectionTx.txid());
        collectionSigning.setStatus(Constants.SENT);
        collectionSigning.setSignature(collectionTx.raw().toString());
        assertEquals(1, repository.updateBitcoinLikeSigningTransaction(currency, collectionSigning));
        assertEquals(1, repository.updateCollectionStatus(
                chainName, collectionNo, "SENT", collectionTx.txid(), null, collectionTx.raw().toString()));
        assertEquals(1, repository.markCollectionConfirmed(chainName, collectionNo, collectionTx.txid()));
        assertEquals(0, repository.markCollectionConfirmed(chainName, collectionNo, collectionTx.txid()));
        assertEquals(1, repository.markUtxosSpent(chainName, collectionLockRef, collectionTx.txid()));
        assertEquals(0, repository.releaseUtxos(chainName, collectionLockRef));
        assertEquals(Optional.of("CONFIRMED"), repository.findCollectionStatus(chainName, collectionNo));
        assertEquals(Optional.of(collectionTx.txid()), repository.findCollectionTxHash(chainName, collectionNo));
        assertLedger(jdbc, chainName, accountId, afterCollectionDeposit, BigDecimal.ZERO, afterCollectionDeposit);
        assertNoNegativeLedger(jdbc, chainName);

        String scannerName = chainName.toLowerCase(Locale.ROOT) + "-regtest-scanner-" + UUID.randomUUID();
        repository.updateScanHeight(chainName, scannerName, collectionTx.blockHeight(), collectionTx.blockHeight() - 1);
        repository.updateScanHeight(chainName, scannerName, collectionTx.blockHeight() - 1, 0);
        assertEquals(collectionTx.blockHeight() - 1, scalarLong(jdbc, """
                select safe_height from chain_scan_height
                where chain = ? and scanner_name = ?
                """, chainName, scannerName));

        System.out.printf("""
                %s_REGTEST_FLOW deposit=%s withdraw=%s collection=%s depositAddress=%s withdrawAddress=%s hotAddress=%s ledger=%s%n""",
                chainName, depositTx.txid(), withdrawTx.txid(), collectionTx.txid(),
                depositAddress, withdrawAddress, hotAddress, afterCollectionDeposit.toPlainString());
    }

    private void runConcurrencyGuards(RegtestChain chain, JdbcTemplate jdbc,
                                      ChainJdbcRepository repository, String runId) throws Exception {
        String chainName = chain.chainType().name();
        BigDecimal one = new BigDecimal("1.00000000");

        String duplicateAccount = runId + "-" + chainName + "-duplicate-account";
        String duplicateTx = runId + "-" + chainName + "-duplicate-deposit";
        DepositEvent duplicateDeposit = syntheticDeposit(chain, duplicateTx,
                runId + "-" + chainName + "-duplicate-address", chain.depositAmount());
        List<Boolean> duplicateCredits = runConcurrently(24,
                ignored -> repository.recordAndCreditDeposit(
                        duplicateDeposit, 0L, REQUIRED_CONFIRMATIONS, duplicateAccount));
        assertEquals(1L, countTrue(duplicateCredits), chainName + " duplicate deposit credit");
        assertEquals(1L, count(jdbc, """
                select count(*) from deposit_record
                where chain = ? and tx_hash = ? and log_index = 0 and credited = true
                """, chainName, duplicateTx));
        assertLedger(jdbc, chainName, duplicateAccount,
                chain.depositAmount(), BigDecimal.ZERO, chain.depositAmount());

        String fanoutAccount = runId + "-" + chainName + "-fanout-account";
        BigDecimal fanoutAmount = new BigDecimal("0.01000000");
        List<Boolean> fanoutCredits = runConcurrently(32, index -> {
            String txHash = runId + "-" + chainName + "-fanout-" + index;
            return repository.recordAndCreditDeposit(
                    syntheticDeposit(chain, txHash, runId + "-" + chainName + "-fanout-address-" + index,
                            fanoutAmount),
                    0L, REQUIRED_CONFIRMATIONS, fanoutAccount);
        });
        assertEquals(32L, countTrue(fanoutCredits), chainName + " fanout deposit credits");
        BigDecimal fanoutTotal = fanoutAmount.multiply(new BigDecimal("32"));
        assertLedger(jdbc, chainName, fanoutAccount, fanoutTotal, BigDecimal.ZERO, fanoutTotal);

        String freezeAccount = runId + "-" + chainName + "-freeze-account";
        repository.incrementLedgerBalance(chainName, chainName, freezeAccount, one);
        BigDecimal freezeAmount = new BigDecimal("0.20000000");
        List<Boolean> freezeResults = runConcurrently(16,
                ignored -> repository.freezeLedgerBalance(chainName, chainName, freezeAccount, freezeAmount));
        assertEquals(5L, countTrue(freezeResults), chainName + " overspend freeze guard");
        assertLedger(jdbc, chainName, freezeAccount, BigDecimal.ZERO, one, one);
        assertNoNegativeLedger(jdbc, chainName);

        String utxoTx = runId + "-" + chainName + "-utxo";
        repository.upsertUtxo(chainName, chainName, utxoTx, 0,
                runId + "-" + chainName + "-utxo-address", one, 100L, REQUIRED_CONFIRMATIONS, true);
        List<Integer> lockResults = runConcurrently(16,
                index -> repository.lockUtxo(chainName, utxoTx, 0, runId + "-" + chainName + "-lock-" + index));
        assertEquals(1L, sumUpdates(lockResults), chainName + " single UTXO lock winner");
        String lockRef = scalarString(jdbc, """
                select lock_ref from utxo_record
                where chain = ? and tx_hash = ? and vout = 0
                """, chainName, utxoTx);
        assertTrue(lockRef.startsWith(runId + "-" + chainName + "-lock-"));
        assertEquals("LOCKED", scalarString(jdbc, """
                select state from utxo_record
                where chain = ? and tx_hash = ? and vout = 0
                """, chainName, utxoTx));

        String withdrawalOrder = runId + "-" + chainName + "-withdraw-claim";
        assertEquals(1, repository.createWithdrawalOrder(withdrawalOrder, 9902L, chainName, chainName,
                runId + "-" + chainName + "-withdraw-to", new BigDecimal("0.10000000"), BigDecimal.ZERO));
        assertEquals(1, repository.updateWithdrawalStatus(
                chainName, withdrawalOrder, "FROZEN", runId + "-" + chainName + "-withdraw-from", null, null));
        List<Integer> withdrawalClaims = runConcurrently(16,
                index -> repository.claimWithdrawalSigning(
                        chainName, withdrawalOrder, runId + "-" + chainName + "-withdraw-from-" + index));
        assertEquals(1L, sumUpdates(withdrawalClaims), chainName + " withdrawal claim winner");
        assertEquals(Optional.of("SIGNING"), repository.findWithdrawalStatus(chainName, withdrawalOrder));

        String collectionNo = runId + "-" + chainName + "-collection-claim";
        assertEquals(1, repository.createCollectionRecord(collectionNo, chainName, chainName,
                runId + "-" + chainName + "-collection-from",
                runId + "-" + chainName + "-collection-to",
                new BigDecimal("0.10000000"), BigDecimal.ZERO, "{}"));
        List<Integer> collectionClaims = runConcurrently(16,
                index -> repository.claimCollectionSigning(chainName, collectionNo, "{\"worker\":" + index + "}"));
        assertEquals(1L, sumUpdates(collectionClaims), chainName + " collection claim winner");
        assertEquals(Optional.of("SIGNING"), repository.findCollectionStatus(chainName, collectionNo));

        String scannerName = runId + "-" + chainName + "-scanner";
        runConcurrently(32, index -> {
            repository.updateScanHeight(chainName, scannerName, 100L + index, 90L + index);
            return 1;
        });
        assertEquals(131L, scalarLong(jdbc, """
                select best_height from chain_scan_height
                where chain = ? and scanner_name = ?
                """, chainName, scannerName));
        assertEquals(121L, scalarLong(jdbc, """
                select safe_height from chain_scan_height
                where chain = ? and scanner_name = ?
                """, chainName, scannerName));

        System.out.printf("""
                %s_CONCURRENCY_GUARD duplicateCredits=%d fanoutCredits=%d freezeWinners=%d utxoLockWinners=%d withdrawalClaims=%d collectionClaims=%d%n""",
                chainName,
                countTrue(duplicateCredits),
                countTrue(fanoutCredits),
                countTrue(freezeResults),
                sumUpdates(lockResults),
                sumUpdates(withdrawalClaims),
                sumUpdates(collectionClaims));
    }

    private static DepositEvent syntheticDeposit(RegtestChain chain, String txHash, String toAddress,
                                                 BigDecimal amount) {
        String chainName = chain.chainType().name();
        return new DepositEvent(
                chain.chainType(), chainName, txHash,
                "regtest-faucet", toAddress, amount, 100L, REQUIRED_CONFIRMATIONS,
                null, "{\"synthetic\":true}");
    }

    private void runBulkBroadcastFlow(Path root, RegtestChain chain, JdbcTemplate jdbc,
                                      ChainJdbcRepository repository, String runId, List<String> cleanupTxids,
                                      int depositCount, int withdrawalCount) throws Exception {
        String chainName = chain.chainType().name();
        rpc(root, chain, null, "init");
        rpc(root, chain, null, "mine", "160");
        JsonNode info = json(rpc(root, chain, chain.chainRpc(), "getblockchaininfo"));
        assertEquals("regtest", info.path("chain").asText(), chainName + " node must be regtest");

        String accountId = runId + "-" + chainName + "-bulk-account";
        BigDecimal depositAmount = bulkDepositAmount(chain);
        BigDecimal withdrawalAmount = bulkWithdrawalAmount(chain);
        BigDecimal withdrawalFee = bulkWithdrawalFee(chain);
        BigDecimal withdrawalDebit = withdrawalAmount.add(withdrawalFee);
        BigDecimal depositTotal = depositAmount.multiply(new BigDecimal(depositCount));
        BigDecimal withdrawalTotal = withdrawalDebit.multiply(new BigDecimal(withdrawalCount));
        assertTrue(depositTotal.compareTo(withdrawalTotal) > 0,
                chainName + " bulk deposits must fund requested withdrawals");

        List<BroadcastPayment> depositPayments = new ArrayList<>();
        for (int i = 0; i < depositCount; i++) {
            depositPayments.add(new BroadcastPayment(
                    i, walletRpcHttp(chain, "getnewaddress").asText(), depositAmount));
        }
        List<BroadcastResult> depositBroadcasts = broadcastPayments(root, chain, depositPayments, cleanupTxids);
        assertEquals(depositCount, depositBroadcasts.stream().map(BroadcastResult::txid).distinct().count(),
                chainName + " deposit broadcasts must have unique txids");
        rpc(root, chain, null, "mine", String.valueOf(REQUIRED_CONFIRMATIONS));
        List<RealTx> depositTxs = fetchBroadcastTransactions(root, chain, depositBroadcasts);

        List<Boolean> credited = runConcurrently(depositTxs.size(), index -> {
            BroadcastResult broadcast = depositBroadcasts.get(index);
            RealTx tx = depositTxs.get(index);
            Output output = findOutput(tx.raw(), broadcast.address(), broadcast.amount());
            repository.upsertUtxo(
                    chainName, chainName, tx.txid(), output.vout(), broadcast.address(), broadcast.amount(),
                    tx.blockHeight(), tx.confirmations(), false);
            DepositEvent event = new DepositEvent(
                    chain.chainType(), chainName, tx.txid(),
                    runId + "-" + chainName + "-bulk-faucet", broadcast.address(), broadcast.amount(),
                    tx.blockHeight(), tx.confirmations(), null,
                    "{\"runId\":\"" + runId + "\",\"type\":\"bulk-deposit\"}");
            return repository.recordAndCreditDeposit(event, output.vout(), REQUIRED_CONFIRMATIONS, accountId);
        });
        assertEquals(depositCount, countTrue(credited), chainName + " bulk deposit credits");
        assertEquals(depositCount, count(jdbc, """
                select count(*) from deposit_record
                where chain = ? and from_address = ? and credited = true
                """, chainName, runId + "-" + chainName + "-bulk-faucet"));
        assertLedger(jdbc, chainName, accountId, depositTotal, BigDecimal.ZERO, depositTotal);

        List<WithdrawalPlan> withdrawals = new ArrayList<>();
        for (int i = 0; i < withdrawalCount; i++) {
            withdrawals.add(new WithdrawalPlan(
                    i,
                    runId + "-" + chainName + "-bulk-wd-" + i,
                    walletRpcHttp(chain, "getnewaddress").asText(),
                    withdrawalAmount,
                    withdrawalFee));
        }
        List<Boolean> frozen = runConcurrently(withdrawals.size(), index -> {
            WithdrawalPlan plan = withdrawals.get(index);
            assertEquals(1, repository.createWithdrawalOrder(
                    plan.orderNo(), 9903L, chainName, chainName, plan.address(), plan.amount(), plan.fee()));
            assertTrue(repository.freezeLedgerBalance(chainName, chainName, accountId, withdrawalDebit));
            assertEquals(1, repository.updateWithdrawalStatus(
                    chainName, plan.orderNo(), "FROZEN", accountId, null, null));
            return true;
        });
        assertEquals(withdrawalCount, countTrue(frozen), chainName + " bulk withdrawal freezes");

        List<BroadcastPayment> withdrawalPayments = withdrawals.stream()
                .map(plan -> new BroadcastPayment(plan.index(), plan.address(), plan.amount()))
                .toList();
        List<BroadcastResult> withdrawalBroadcasts = broadcastPayments(root, chain, withdrawalPayments, cleanupTxids);
        assertEquals(withdrawalCount, withdrawalBroadcasts.stream().map(BroadcastResult::txid).distinct().count(),
                chainName + " withdrawal broadcasts must have unique txids");
        rpc(root, chain, null, "mine", String.valueOf(REQUIRED_CONFIRMATIONS));
        List<RealTx> withdrawalTxs = fetchBroadcastTransactions(root, chain, withdrawalBroadcasts);

        List<Boolean> settled = runConcurrently(withdrawals.size(), index -> {
            WithdrawalPlan plan = withdrawals.get(index);
            BroadcastResult broadcast = withdrawalBroadcasts.get(index);
            RealTx tx = withdrawalTxs.get(index);
            findOutput(tx.raw(), broadcast.address(), broadcast.amount());
            assertEquals(1, repository.updateWithdrawalStatus(
                    chainName, plan.orderNo(), "SENT", accountId, tx.txid(), null));
            assertEquals(1, repository.markWithdrawalConfirmed(chainName, plan.orderNo(), tx.txid()));
            assertTrue(repository.settleLockedDebit(chainName, chainName, accountId, withdrawalDebit));
            return true;
        });
        assertEquals(withdrawalCount, countTrue(settled), chainName + " bulk withdrawal settlements");
        assertEquals(withdrawalCount, count(jdbc, """
                select count(*) from withdrawal_order
                where chain = ? and order_no like ? and status = 'CONFIRMED' and tx_hash is not null
                """, chainName, runId + "-" + chainName + "-bulk-wd-%"));
        BigDecimal expectedAvailable = depositTotal.subtract(withdrawalTotal);
        assertLedger(jdbc, chainName, accountId, expectedAvailable, BigDecimal.ZERO, expectedAvailable);
        assertNoNegativeLedger(jdbc, chainName);

        System.out.printf("""
                %s_BULK_BROADCAST deposits=%d withdrawals=%d firstDeposit=%s firstWithdrawal=%s ledger=%s%n""",
                chainName, depositCount, withdrawalCount,
                depositBroadcasts.getFirst().txid(), withdrawalBroadcasts.getFirst().txid(),
                expectedAvailable.toPlainString());
    }

    private List<BroadcastResult> broadcastPayments(Path root, RegtestChain chain,
                                                    List<BroadcastPayment> payments,
                                                    List<String> cleanupTxids) throws Exception {
        return runConcurrently(payments.size(), index -> {
            BroadcastPayment payment = payments.get(index);
            String txid = walletRpcHttp(chain, "sendtoaddress",
                    payment.address(), payment.amount()).asText();
            cleanupTxids.add(txid);
            return new BroadcastResult(payment.index(), payment.address(), payment.amount(), txid);
        });
    }

    private List<RealTx> fetchBroadcastTransactions(Path root, RegtestChain chain,
                                                    List<BroadcastResult> broadcasts) throws Exception {
        return runConcurrently(broadcasts.size(), index -> {
            BroadcastResult broadcast = broadcasts.get(index);
            JsonNode raw = json(getRawTransaction(root, chain, broadcast.txid()));
            int confirmations = raw.path("confirmations").asInt();
            assertTrue(confirmations >= REQUIRED_CONFIRMATIONS,
                    chain.chainType() + " tx " + broadcast.txid() + " confirmations=" + confirmations);
            long blockHeight = json(rpc(root, chain, chain.chainRpc(), "getblock",
                    raw.path("blockhash").asText())).path("height").asLong();
            return new RealTx(broadcast.txid(), raw, blockHeight, confirmations);
        });
    }

    private static BigDecimal bulkDepositAmount(RegtestChain chain) {
        return chain.chainType() == ChainType.DOGE
                ? new BigDecimal("3.00000000")
                : new BigDecimal("0.02000000");
    }

    private static BigDecimal bulkWithdrawalAmount(RegtestChain chain) {
        return chain.chainType() == ChainType.DOGE
                ? new BigDecimal("1.00000000")
                : new BigDecimal("0.00500000");
    }

    private static BigDecimal bulkWithdrawalFee(RegtestChain chain) {
        return chain.chainType() == ChainType.DOGE
                ? new BigDecimal("1.00000000")
                : new BigDecimal("0.00001000");
    }

    private RealTx sendAndMine(Path root, RegtestChain chain, String address, BigDecimal amount) throws Exception {
        String txid = walletRpc(root, chain, "sendtoaddress", address, amount.toPlainString()).trim();
        rpc(root, chain, null, "mine", String.valueOf(REQUIRED_CONFIRMATIONS));
        JsonNode raw = json(getRawTransaction(root, chain, txid));
        int confirmations = raw.path("confirmations").asInt();
        assertTrue(confirmations >= REQUIRED_CONFIRMATIONS,
                chain.chainType() + " tx " + txid + " confirmations=" + confirmations);
        long blockHeight = json(rpc(root, chain, chain.chainRpc(), "getblock",
                raw.path("blockhash").asText())).path("height").asLong();
        return new RealTx(txid, raw, blockHeight, confirmations);
    }

    private String getRawTransaction(Path root, RegtestChain chain, String txid) throws Exception {
        String verbose = chain.chainType() == ChainType.DOGE ? "1" : "true";
        return rpc(root, chain, chain.chainRpc(), "getrawtransaction", txid, verbose);
    }

    private Output findOutput(JsonNode tx, String address, BigDecimal amount) {
        for (JsonNode output : tx.path("vout")) {
            if (sameAmount(output.path("value").decimalValue(), amount)
                    && scriptContainsAddress(output.path("scriptPubKey"), address)) {
                return new Output(output.path("n").asInt(), output.path("value").decimalValue());
            }
        }
        fail("missing output address=" + address + " amount=" + amount.toPlainString()
                + " in tx=" + tx.path("txid").asText());
        return null;
    }

    private static boolean scriptContainsAddress(JsonNode scriptPubKey, String expectedAddress) {
        if (expectedAddress.equals(scriptPubKey.path("address").asText())) {
            return true;
        }
        for (JsonNode address : scriptPubKey.path("addresses")) {
            if (expectedAddress.equals(address.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left.setScale(8).compareTo(right.setScale(8)) == 0;
    }

    private static String walletRpc(Path root, RegtestChain chain, String... args) throws Exception {
        return rpc(root, chain, chain.walletRpc(), args);
    }

    private static JsonNode walletRpcHttp(RegtestChain chain, String method, Object... params) throws Exception {
        return rpcHttp(chain, true, method, params);
    }

    private static JsonNode rpcHttp(RegtestChain chain, boolean wallet, String method, Object... params)
            throws Exception {
        String credentials = Base64.getEncoder().encodeToString((
                env(rpcUserEnv(chain), "wallet") + ":" + env(rpcPasswordEnv(chain), "wallet123"))
                .getBytes(StandardCharsets.UTF_8));
        String body = JSON.writeValueAsString(Map.of(
                "jsonrpc", "1.0",
                "id", "regtest-bulk-" + UUID.randomUUID(),
                "method", method,
                "params", List.of(params)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(rpcEndpoint(chain, wallet)))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = RPC_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), method + " HTTP response: " + response.body());
        JsonNode payload = json(response.body());
        assertTrue(payload.path("error").isNull(), method + " RPC error: " + payload.path("error"));
        return payload.path("result");
    }

    private static String rpcEndpoint(RegtestChain chain, boolean wallet) {
        String base = switch (chain.chainType()) {
            case BTC -> "http://127.0.0.1:" + env("BTC_REGTEST_RPC_PORT", "18444");
            case LTC -> "http://127.0.0.1:" + env("LTC_REGTEST_RPC_PORT", "19443");
            case DOGE -> "http://127.0.0.1:" + env("DOGE_REGTEST_RPC_PORT", "22555");
            case BCH -> "http://127.0.0.1:" + env("BCH_REGTEST_RPC_PORT", "18443");
            default -> throw new IllegalArgumentException("unsupported bitcoin-like regtest chain " + chain.chainType());
        };
        if (!wallet || chain.chainType() == ChainType.DOGE) {
            return base + "/";
        }
        return base + "/wallet/regtest-funder";
    }

    private static String rpcUserEnv(RegtestChain chain) {
        return switch (chain.chainType()) {
            case BTC -> "BTC_REGTEST_RPC_USER";
            case LTC -> "LTC_REGTEST_RPC_USER";
            case DOGE -> "DOGE_REGTEST_RPC_USER";
            case BCH -> "BCH_REGTEST_RPC_USER";
            default -> "REGTEST_RPC_USER";
        };
    }

    private static String rpcPasswordEnv(RegtestChain chain) {
        return switch (chain.chainType()) {
            case BTC -> "BTC_REGTEST_RPC_PASSWORD";
            case LTC -> "LTC_REGTEST_RPC_PASSWORD";
            case DOGE -> "DOGE_REGTEST_RPC_PASSWORD";
            case BCH -> "BCH_REGTEST_RPC_PASSWORD";
            default -> "REGTEST_RPC_PASSWORD";
        };
    }

    private static String rpc(Path root, RegtestChain chain, String rpcMode, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(root.resolve(chain.script()).toString());
        if (rpcMode != null && !rpcMode.isBlank()) {
            command.add(rpcMode);
        }
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, String.join(" ", command) + "\n" + output);
        return output.trim();
    }

    private static JsonNode json(String payload) throws IOException {
        return JSON.readTree(payload);
    }

    private static void assertLedger(JdbcTemplate jdbc, String chain, String accountId,
                                     BigDecimal available, BigDecimal locked, BigDecimal total) {
        LedgerBalanceRecord record = new ChainJdbcRepository(jdbc)
                .findLedgerBalance(chain, chain, accountId)
                .orElseThrow();
        assertDecimalEquals(available, record.getAvailableBalance(), chain + " available");
        assertDecimalEquals(locked, record.getLockedBalance(), chain + " locked");
        assertDecimalEquals(total, record.getTotalBalance(), chain + " total");
    }

    private static void assertNoNegativeLedger(JdbcTemplate jdbc, String chain) {
        assertEquals(0L, count(jdbc, """
                select count(*) from ledger_balance
                where chain = ?
                  and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                """, chain));
    }

    private static void assertDecimalEquals(BigDecimal expected, BigDecimal actual, String label) {
        assertEquals(0, expected.setScale(18).compareTo(actual.setScale(18)),
                label + " expected=" + expected.toPlainString() + " actual=" + actual.toPlainString());
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    private static long scalarLong(JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        assertNotNull(value);
        return value;
    }

    private static String scalarString(JdbcTemplate jdbc, String sql, Object... args) {
        String value = jdbc.queryForObject(sql, String.class, args);
        assertNotNull(value);
        return value;
    }

    private static long countTrue(List<Boolean> values) {
        return values.stream().filter(Boolean::booleanValue).count();
    }

    private static long sumUpdates(List<Integer> values) {
        return values.stream().mapToLong(Integer::longValue).sum();
    }

    private static RuntimeAsset runtimeAsset(ChainJdbcRepository repository, String chain) {
        AccountChainProfile profile = repository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException("missing chain_profile for " + chain));
        ChainAsset asset = repository.findAsset(profile.getChain(), profile.getNativeSymbol())
                .orElseThrow(() -> new IllegalStateException(
                        "missing chain_asset for " + profile.getChain() + "/" + profile.getNativeSymbol()));
        return RuntimeAsset.fromProfile(profile, asset);
    }

    private static void ensureBitcoinLikeProfiles(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into chain_profile(chain, network, family, runtime_currency_id, bip44_coin_type,
                                          native_symbol, deposit_confirmations, withdraw_confirmations,
                                          default_fee_rate, dust_threshold, enabled)
                values
                    ('BTC', 'regtest', 'bitcoin-like', 1, 0, 'BTC', 6, 6, 1, 546, true),
                    ('LTC', 'regtest', 'bitcoin-like', 24, 2, 'LTC', 6, 6, 10, 100000, true),
                    ('DOGE', 'regtest', 'bitcoin-like', 41, 3, 'DOGE', 6, 6, 1000, 1000000, true),
                    ('BCH', 'regtest', 'bitcoin-like', 42, 145, 'BCH', 6, 6, 1, 546, true)
                on conflict (chain, network) do update set
                    runtime_currency_id = excluded.runtime_currency_id,
                    bip44_coin_type = excluded.bip44_coin_type,
                    native_symbol = excluded.native_symbol,
                    deposit_confirmations = excluded.deposit_confirmations,
                    withdraw_confirmations = excluded.withdraw_confirmations,
                    default_fee_rate = excluded.default_fee_rate,
                    dust_threshold = excluded.dust_threshold,
                    enabled = excluded.enabled,
                    updated_at = now()
                """);
        jdbc.update("""
                insert into chain_asset(chain, symbol, asset_kind, decimals, native_asset, active, min_transfer, min_withdraw)
                values
                    ('BTC', 'BTC', 'NATIVE', 8, true, true, 546, 546),
                    ('LTC', 'LTC', 'NATIVE', 8, true, true, 100000, 100000),
                    ('DOGE', 'DOGE', 'NATIVE', 8, true, true, 1000000, 1000000),
                    ('BCH', 'BCH', 'NATIVE', 8, true, true, 546, 546)
                on conflict (chain, symbol) do update set
                    decimals = excluded.decimals,
                    native_asset = excluded.native_asset,
                    active = excluded.active,
                    min_transfer = excluded.min_transfer,
                    min_withdraw = excluded.min_withdraw,
                    updated_at = now()
                """);
    }

    private static void cleanupConcurrencyRows(JdbcTemplate jdbc, String runId) {
        String like = runId + "%";
        jdbc.update("delete from chain_scan_height where scanner_name like ?", like);
        jdbc.update("delete from collection_record where collection_no like ?", like);
        jdbc.update("delete from withdrawal_order where order_no like ?", like);
        jdbc.update("delete from utxo_record where tx_hash like ?", like);
        jdbc.update("delete from deposit_record where tx_hash like ?", like);
        jdbc.update("delete from ledger_balance where account_id like ?", like);
    }

    private static void cleanupBroadcastRows(JdbcTemplate jdbc, String runId, List<String> txids) {
        cleanupConcurrencyRows(jdbc, runId);
        for (String txid : txids) {
            jdbc.update("delete from utxo_record where tx_hash = ?", txid);
            jdbc.update("delete from deposit_record where tx_hash = ?", txid);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(BTC.script()))
                    && Files.exists(current.resolve(LTC.script()))
                    && Files.exists(current.resolve(DOGE.script()))
                    && Files.exists(current.resolve(BCH.script()))) {
                return current;
            }
            current = current.getParent();
        }
        fail("could not locate repo root containing scripts/regtest");
        return Path.of(".");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private record RegtestChain(
            ChainType chainType,
            String script,
            String walletRpc,
            String chainRpc,
            BigDecimal depositAmount,
            BigDecimal withdrawAmount,
            BigDecimal withdrawFee,
            BigDecimal collectionDepositAmount,
            BigDecimal collectionFee) {
    }

    private record RealTx(String txid, JsonNode raw, long blockHeight, int confirmations) {
    }

    private record Output(int vout, BigDecimal amount) {
    }

    private record BroadcastPayment(int index, String address, BigDecimal amount) {
    }

    private record BroadcastResult(int index, String address, BigDecimal amount, String txid) {
    }

    private record WithdrawalPlan(int index, String orderNo, String address, BigDecimal amount, BigDecimal fee) {
    }

    @FunctionalInterface
    private interface ConcurrentOperation<T> {
        T run(int index) throws Exception;
    }

    private static <T> List<T> runConcurrently(int workers, ConcurrentOperation<T> operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return operation.run(index);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}
