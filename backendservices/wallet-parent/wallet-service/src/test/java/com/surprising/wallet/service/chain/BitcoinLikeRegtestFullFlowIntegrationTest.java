package com.surprising.wallet.service.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real local-regtest verification for the unified bitcoin-like UTXO runtime.
 *
 * <p>The test talks to dogecoind/BCHN through scripts/regtest, creates real
 * chain transactions and confirmations, then verifies deposit_record,
 * utxo_record, withdrawal_order, collection_record, ledger_balance and
 * idempotency against PostgreSQL. DB writes are rolled back; regtest chain
 * transactions remain in the local nodes.</p>
 *
 * <p>This is deliberately opt-in because it requires Docker, DOGE regtest,
 * BCH regtest, and PostgreSQL.</p>
 */
class BitcoinLikeRegtestFullFlowIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REQUIRED_CONFIRMATIONS = 6;

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
    void dogeAndBchRegtestFlowsMustUseUnifiedUtxoRecordEndToEnd() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bitcoinlike.regtest.enabled"),
                "set -Dbitcoinlike.regtest.enabled=true to run real DOGE/BCH regtest flow validation");
        Path root = repoRoot();
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
                runFullFlow(root, DOGE, jdbc, repository);
                runFullFlow(root, BCH, jdbc, repository);
            } finally {
                connection.rollback();
            }
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
        assertEquals(1, repository.lockUtxo(chainName, depositTx.txid(), depositOutput.vout(), withdrawalOrder));
        assertEquals(0, repository.lockUtxo(chainName, depositTx.txid(), depositOutput.vout(), withdrawalOrder + "-again"));
        assertEquals(1, repository.updateWithdrawalStatus(
                chainName, withdrawalOrder, "UTXO_LOCKED", depositAddress, null, null));
        assertEquals(1, repository.updateWithdrawalStatus(
                chainName, withdrawalOrder, "SIGNING", depositAddress, null, null));

        RealTx withdrawTx = sendAndMine(root, chain, withdrawAddress, chain.withdrawAmount());
        findOutput(withdrawTx.raw(), withdrawAddress, chain.withdrawAmount());
        assertEquals(1, repository.updateWithdrawalStatus(
                chainName, withdrawalOrder, "SENT", depositAddress, withdrawTx.txid(), null));
        assertEquals(1, repository.markWithdrawalConfirmed(chainName, withdrawalOrder, withdrawTx.txid()));
        assertEquals(0, repository.markWithdrawalConfirmed(chainName, withdrawalOrder, withdrawTx.txid()));
        assertEquals(1, repository.markUtxosSpent(chainName, withdrawalOrder, withdrawTx.txid()));
        assertEquals(0, repository.markUtxosSpent(chainName, withdrawalOrder, withdrawTx.txid()));
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
        assertEquals(1, repository.lockUtxo(
                chainName, collectionDeposit.txid(), collectionOutput.vout(), collectionNo));
        assertEquals(0, repository.releaseUtxos(chainName, "unknown-" + collectionNo));

        RealTx collectionTx = sendAndMine(root, chain, hotAddress, collectionOutputAmount);
        findOutput(collectionTx.raw(), hotAddress, collectionOutputAmount);
        assertEquals(1, repository.updateCollectionStatus(
                chainName, collectionNo, "SENT", collectionTx.txid(), null, collectionTx.raw().toString()));
        assertEquals(1, repository.markCollectionConfirmed(chainName, collectionNo, collectionTx.txid()));
        assertEquals(0, repository.markCollectionConfirmed(chainName, collectionNo, collectionTx.txid()));
        assertEquals(1, repository.markUtxosSpent(chainName, collectionNo, collectionTx.txid()));
        assertEquals(0, repository.releaseUtxos(chainName, collectionNo));
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

    private static void ensureBitcoinLikeProfiles(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into chain_profile(chain, network, family, runtime_currency_id, bip44_coin_type,
                                          native_symbol, deposit_confirmations, withdraw_confirmations,
                                          default_fee_rate, dust_threshold, enabled)
                values
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

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(DOGE.script()))
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
}
