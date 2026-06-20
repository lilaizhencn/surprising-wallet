package com.surprising.wallet.service.chain.tron;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.tron.trident.abi.FunctionReturnDecoder;
import org.tron.trident.abi.TypeReference;
import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.abi.datatypes.Type;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.core.NodeType;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Response;
import org.tron.trident.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TronLiveFullFlowIntegrationTest {
    private static final String CHAIN = ChainType.TRON.name();
    private static final String NETWORK = "NILE";
    private static final String SOURCE_ADDRESS = "TB1x9vmH5SbBd1EUaUePGbZzqmXGosFtxK";
    private static final String SOURCE_PATH = "m/44/23/1/91001/0";
    private static final String NILE_USDT_CONTRACT = "TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf";
    private static final int TRX_DECIMALS = 6;
    private static final int USDT_DECIMALS = 6;
    private static final BigDecimal SUN = new BigDecimal("1000000");
    private static final long FEE_LIMIT_SUN = 100_000_000L;

    @Test
    void shouldExecuteNileTrxAndTrc20FullFlow() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("tron.live.flow.enabled"),
                "set -Dtron.live.flow.enabled=true to broadcast Nile TRX/TRC20 transactions");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbcTemplate);
        TronDepositScanner depositScanner = new TronDepositScanner(repository, new TronScanner());
        TronTransactionService trxService = new TronTransactionService();
        TronTrc20Service trc20Service = new TronTrc20Service();
        TronGasEstimator gasEstimator = new TronGasEstimator();
        TronWaitingGasStateService waitingGasStateService = new TronWaitingGasStateService();

        String fullNode = System.getProperty("tron.fullnode", "grpc.nile.trongrid.io:50051");
        String solidityNode = System.getProperty("tron.soliditynode", "grpc.nile.trongrid.io:50061");
        String apiKey = System.getProperty("tron.apiKey", "");
        int requiredConfirmations = Integer.getInteger("tron.confirmations", 1);
        String runId = "TRON-LIVE-" + System.currentTimeMillis();
        int userBase = Integer.getInteger("tron.live.userBase",
                93000 + (int) (System.currentTimeMillis() % 30000));

        try (TronTridentClient client = new TronTridentClient(fullNode, solidityNode, apiKey)) {
            Actor source = actor("source", 91001);
            assertEquals(SOURCE_ADDRESS, source.address(), "funded faucet address must match derived BTC ECKey path");
            Actor userA = actor("userA", userBase);
            Actor userB = actor("userB", userBase + 1);
            Actor userC = actor("userC", userBase + 2);
            Actor hot = actor("hot", userBase + 3);
            Actor external = actor("external", userBase + 4);

            prepareDatabase(jdbcTemplate, source, userA, userB, userC, hot, external, runId);
            upsertNileUsdt(jdbcTemplate);

            BigDecimal sourceTrx = trxBalance(client, source.address());
            BigDecimal sourceUsdt = trc20Balance(client, source.address(), NILE_USDT_CONTRACT, USDT_DECIMALS);
            assertTrue(sourceTrx.compareTo(new BigDecimal("120")) > 0,
                    "funded Nile source must have enough TRX; current=" + sourceTrx);
            assertTrue(sourceUsdt.compareTo(new BigDecimal("80")) > 0,
                    "funded Nile source must have enough USDT; current=" + sourceUsdt);

            Response.AccountResourceMessage sourceResources = client.getResources(source.address());
            Response.AccountNetMessage sourceBandwidth = client.getBandwidth(source.address());
            assertTrue(sourceResources.getEnergyLimit() >= 0);
            assertTrue(sourceBandwidth.getFreeNetLimit() >= 0);

            // TRX deposit scan: source -> user A. The scanner parses TransferContract from the real block.
            TxResult trxDeposit = sendTrx(client, trxService, source, userA.address(), new BigDecimal("5"));
            List<DepositEvent> trxDeposits = depositScanner.scanAndCreditTrx(client, trxDeposit.blockHeight(),
                    platform(userA), requiredConfirmations);
            assertTrue(trxDeposits.stream().anyMatch(event -> trxDeposit.txId().equalsIgnoreCase(event.txId())));
            BigDecimal userATrxAfterCredit = ledger(jdbcTemplate, "TRX", userA.address());
            depositScanner.scanAndCreditTrx(client, trxDeposit.blockHeight(), platform(userA), requiredConfirmations);
            assertBalanceEquals(userATrxAfterCredit, ledger(jdbcTemplate, "TRX", userA.address()),
                    "TRX duplicate scan must not double credit");

            // Single-user two TRX withdrawals from user A.
            TxResult trxWithdraw1 = withdrawTrx(jdbcTemplate, repository, client, trxService, runId + "-TRX-WD-1",
                    userA, external.address(), new BigDecimal("1"));
            TxResult trxWithdraw2 = withdrawTrx(jdbcTemplate, repository, client, trxService, runId + "-TRX-WD-2",
                    userA, external.address(), new BigDecimal("0.5"));
            assertNotEquals(trxWithdraw1.txId(), trxWithdraw2.txId());
            assertChainLedgerTrx(client, jdbcTemplate, userA);
            assertFalse(repository.freezeLedgerBalance(CHAIN, "TRX", userA.address(), new BigDecimal("1000")),
                    "overspend guard must reject impossible TRX withdrawal before broadcast");

            // TRC20 deposit scan: source -> user B and source -> user C.
            TxResult usdtDepositB = sendTrc20(client, trc20Service, source, NILE_USDT_CONTRACT,
                    userB.address(), new BigDecimal("30"), USDT_DECIMALS);
            scanAndAssertUsdtDeposit(depositScanner, client, jdbcTemplate, usdtDepositB, userB,
                    new BigDecimal("30"), requiredConfirmations);
            TxResult usdtDepositC = sendTrc20(client, trc20Service, source, NILE_USDT_CONTRACT,
                    userC.address(), new BigDecimal("20"), USDT_DECIMALS);
            scanAndAssertUsdtDeposit(depositScanner, client, jdbcTemplate, usdtDepositC, userC,
                    new BigDecimal("20"), requiredConfirmations);

            // WAITING_GAS + gas top-up + collection: user B has token but no TRX.
            BigDecimal userBTrxBeforeTopup = trxBalance(client, userB.address());
            var waitDecision = waitingGasStateService.evaluate(CHAIN, runId + "-COLLECT-USDT", userB.address(),
                    userBTrxBeforeTopup, new BigDecimal("8"), TronGasPolicy.nileDefault());
            assertTrue(waitDecision.waitingGas(), "token collection must wait for gas before top-up");
            insertGasTask(jdbcTemplate, waitDecision.gasTaskNo(), userB.address(), source.address(),
                    waitDecision.topupAmount(), "WAITING_GAS");
            TxResult gasTopupB = sendTrx(client, trxService, source, userB.address(), waitDecision.topupAmount());
            updateGasTaskConfirmed(jdbcTemplate, waitDecision.gasTaskNo(), gasTopupB.txId());
            repository.incrementLedgerBalance(CHAIN, "TRX", userB.address().toLowerCase(Locale.ROOT),
                    waitDecision.topupAmount());

            long estimatedEnergy = estimateTransferEnergy(client, userB, hot.address(), new BigDecimal("30"));
            BigDecimal estimatedFee = gasEstimator.estimateTrc20FeeTrx(Math.max(estimatedEnergy, 1L), 420L,
                    TronGasPolicy.nileDefault());
            assertTrue(estimatedFee.compareTo(BigDecimal.ZERO) > 0);

            TxResult usdtCollection = collectTrc20(jdbcTemplate, repository, client, trc20Service,
                    runId + "-COLLECT-USDT", userB, hot, new BigDecimal("30"));
            depositScanner.scanAndCreditTrc20(client, usdtCollection.blockHeight(), tokenMap(), platform(hot),
                    requiredConfirmations);
            assertBalanceEquals(BigDecimal.ZERO, ledger(jdbcTemplate, "USDT", userB.address()),
                    "collected user token ledger must be zero");
            assertBalanceEquals(new BigDecimal("30"), ledger(jdbcTemplate, "USDT", hot.address()),
                    "hot wallet token ledger must increase through collection scan");
            assertChainLedgerTrx(client, jdbcTemplate, userB);
            assertTokenLedger(client, jdbcTemplate, userB, BigDecimal.ZERO);
            assertTokenLedger(client, jdbcTemplate, hot, new BigDecimal("30"));

            // TRC20 withdrawal with gas already topped up.
            var gasDecisionC = waitingGasStateService.evaluate(CHAIN, runId + "-USDT-WD", userC.address(),
                    trxBalance(client, userC.address()), new BigDecimal("8"), TronGasPolicy.nileDefault());
            assertTrue(gasDecisionC.waitingGas());
            insertGasTask(jdbcTemplate, gasDecisionC.gasTaskNo(), userC.address(), source.address(),
                    gasDecisionC.topupAmount(), "WAITING_GAS");
            TxResult gasTopupC = sendTrx(client, trxService, source, userC.address(), gasDecisionC.topupAmount());
            updateGasTaskConfirmed(jdbcTemplate, gasDecisionC.gasTaskNo(), gasTopupC.txId());
            repository.incrementLedgerBalance(CHAIN, "TRX", userC.address().toLowerCase(Locale.ROOT),
                    gasDecisionC.topupAmount());

            TxResult trc20Withdraw = withdrawTrc20(jdbcTemplate, repository, client, trc20Service,
                    runId + "-USDT-WD", userC, external.address(), new BigDecimal("5"));
            assertTrue(trc20Withdraw.fee().compareTo(BigDecimal.ZERO) >= 0);
            assertTokenLedger(client, jdbcTemplate, userC, new BigDecimal("15"));
            assertChainLedgerTrx(client, jdbcTemplate, userC);

            assertNoNegativeOrLockedLedger(jdbcTemplate, userA, userB, userC, hot);
            assertDepositCount(jdbcTemplate, usdtDepositB.txId(), userB.address(), 1);
            assertDepositCount(jdbcTemplate, usdtDepositC.txId(), userC.address(), 1);
            assertDepositCount(jdbcTemplate, usdtCollection.txId(), hot.address(), 1);
            writeReport(runId, Map.ofEntries(
                    Map.entry("source", source.address()),
                    Map.entry("userA", userA.address()),
                    Map.entry("userB", userB.address()),
                    Map.entry("userC", userC.address()),
                    Map.entry("hot", hot.address()),
                    Map.entry("external", external.address()),
                    Map.entry("trxDepositTxid", trxDeposit.txId()),
                    Map.entry("trxWithdraw1Txid", trxWithdraw1.txId()),
                    Map.entry("trxWithdraw2Txid", trxWithdraw2.txId()),
                    Map.entry("usdtDepositBTxid", usdtDepositB.txId()),
                    Map.entry("usdtDepositCTxid", usdtDepositC.txId()),
                    Map.entry("gasTopupBTxid", gasTopupB.txId()),
                    Map.entry("usdtCollectionTxid", usdtCollection.txId()),
                    Map.entry("gasTopupCTxid", gasTopupC.txId()),
                    Map.entry("usdtWithdrawTxid", trc20Withdraw.txId()),
                    Map.entry("estimatedEnergy", String.valueOf(estimatedEnergy)),
                    Map.entry("estimatedFeeTrx", estimatedFee.toPlainString())
            ));
        }
    }

    private static void scanAndAssertUsdtDeposit(TronDepositScanner scanner, TronTridentClient client,
                                                 JdbcTemplate jdbcTemplate, TxResult tx, Actor actor,
                                                 BigDecimal expectedAmount, int confirmations) throws Exception {
        List<DepositEvent> deposits = scanner.scanAndCreditTrc20(client, tx.blockHeight(), tokenMap(), platform(actor),
                confirmations);
        assertTrue(deposits.stream().anyMatch(event -> tx.txId().equalsIgnoreCase(event.txId())));
        assertBalanceEquals(expectedAmount, ledger(jdbcTemplate, "USDT", actor.address()),
                "TRC20 deposit ledger must match amount");
        scanner.scanAndCreditTrc20(client, tx.blockHeight(), tokenMap(), platform(actor), confirmations);
        assertBalanceEquals(expectedAmount, ledger(jdbcTemplate, "USDT", actor.address()),
                "TRC20 duplicate scan must not double credit");
    }

    private static TxResult withdrawTrx(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                        TronTridentClient client, TronTransactionService service,
                                        String orderNo, Actor from, String to, BigDecimal amount) throws Exception {
        insertWithdrawal(jdbcTemplate, orderNo, from, to, "TRX", amount, "CREATED");
        assertTrue(repository.freezeLedgerBalance(CHAIN, "TRX", from.address(), amount));
        updateWithdrawal(jdbcTemplate, orderNo, "FROZEN", null, BigDecimal.ZERO);
        updateWithdrawal(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO);
        TxResult tx = sendTrx(client, service, from, to, amount);
        updateWithdrawal(jdbcTemplate, orderNo, "SENT", tx.txId(), tx.fee());
        assertTrue(repository.settleLockedDebit(CHAIN, "TRX", from.address(), amount));
        if (tx.fee().signum() > 0) {
            assertTrue(repository.debitLedgerBalance(CHAIN, "TRX", from.address(), tx.fee()));
        }
        updateWithdrawal(jdbcTemplate, orderNo, "CONFIRMED", tx.txId(), tx.fee());
        return tx;
    }

    private static TxResult withdrawTrc20(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                          TronTridentClient client, TronTrc20Service service,
                                          String orderNo, Actor from, String to, BigDecimal amount) throws Exception {
        insertWithdrawal(jdbcTemplate, orderNo, from, to, "USDT", amount, "CREATED");
        assertTrue(repository.freezeLedgerBalance(CHAIN, "USDT", from.address(), amount));
        updateWithdrawal(jdbcTemplate, orderNo, "FROZEN", null, BigDecimal.ZERO);
        updateWithdrawal(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO);
        TxResult tx = sendTrc20(client, service, from, NILE_USDT_CONTRACT, to, amount, USDT_DECIMALS);
        updateWithdrawal(jdbcTemplate, orderNo, "SENT", tx.txId(), tx.fee());
        assertTrue(repository.settleLockedDebit(CHAIN, "USDT", from.address(), amount));
        if (tx.fee().signum() > 0) {
            assertTrue(repository.debitLedgerBalance(CHAIN, "TRX", from.address(), tx.fee()));
        }
        updateWithdrawal(jdbcTemplate, orderNo, "CONFIRMED", tx.txId(), tx.fee());
        return tx;
    }

    private static TxResult collectTrc20(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                         TronTridentClient client, TronTrc20Service service,
                                         String collectionNo, Actor from, Actor to, BigDecimal amount) throws Exception {
        jdbcTemplate.update("""
                        insert into collection_record(collection_no, chain, asset_symbol, from_address, to_address,
                                                      amount, fee, status)
                        values (?, ?, 'USDT', ?, ?, ?, 0, 'CREATED')
                        on conflict (collection_no) do update set status = 'CREATED', updated_at = now()
                        """, collectionNo, CHAIN, from.address(), to.address(), amount);
        assertTrue(repository.freezeLedgerBalance(CHAIN, "USDT", from.address(), amount));
        jdbcTemplate.update("update collection_record set status = 'SIGNING', updated_at = now() where collection_no = ?",
                collectionNo);
        TxResult tx = sendTrc20(client, service, from, NILE_USDT_CONTRACT, to.address(), amount, USDT_DECIMALS);
        jdbcTemplate.update("""
                        update collection_record
                        set status = 'SENT', tx_hash = ?, fee = ?, updated_at = now()
                        where collection_no = ?
                        """, tx.txId(), tx.fee(), collectionNo);
        assertTrue(repository.settleLockedDebit(CHAIN, "USDT", from.address(), amount));
        if (tx.fee().signum() > 0) {
            assertTrue(repository.debitLedgerBalance(CHAIN, "TRX", from.address(), tx.fee()));
        }
        jdbcTemplate.update("""
                        update collection_record
                        set status = 'CONFIRMED', tx_hash = ?, fee = ?, updated_at = now()
                        where collection_no = ?
                        """, tx.txId(), tx.fee(), collectionNo);
        return tx;
    }

    private static TxResult sendTrx(TronTridentClient client, TronTransactionService service,
                                    Actor from, String to, BigDecimal amount) throws Exception {
        var signed = service.signTrxTransfer(client, from.keyPair(), to, toSun(amount));
        service.broadcast(client, signed);
        Response.TransactionInfo info = waitTransaction(client, signed.txId());
        return new TxResult(signed.txId(), info.getBlockNumber(), feeTrx(info));
    }

    private static TxResult sendTrc20(TronTridentClient client, TronTrc20Service service, Actor from,
                                      String contract, String to, BigDecimal amount, int decimals) throws Exception {
        var signed = service.signTransfer(client, from.keyPair(), contract, to, amount, decimals, FEE_LIMIT_SUN);
        service.broadcast(client, signed);
        Response.TransactionInfo info = waitTransaction(client, signed.txId());
        assertTrue(info.getLogCount() > 0, "TRC20 transfer must emit at least one log");
        return new TxResult(signed.txId(), info.getBlockNumber(), feeTrx(info));
    }

    private static Response.TransactionInfo waitTransaction(TronTridentClient client, String txId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Response.TransactionInfo info = client.getTransactionInfo(txId, NodeType.FULL_NODE);
                if (info != null && !info.getId().isEmpty() && info.getBlockNumber() > 0) {
                    assertEquals(Response.TransactionInfo.code.SUCESS, info.getResult(),
                            "TRON transaction must execute successfully: " + txId);
                    return info;
                }
            } catch (Exception ignored) {
                // Full nodes often return "TransactionInfo not found" for a few blocks
                // immediately after broadcast. Treat that as pending, not as failure.
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException("transactionInfo timeout: " + txId);
    }

    private static BigDecimal trc20Balance(TronTridentClient client, String owner, String contract, int decimals) {
        Function balanceOf = new Function("balanceOf",
                List.of(new Address(owner)),
                List.of(new TypeReference<Uint256>() {
                }));
        Response.TransactionExtention response = client.api().constantCall(owner, contract, balanceOf);
        if (response.getConstantResultCount() == 0) {
            return BigDecimal.ZERO;
        }
        List<Type> decoded = FunctionReturnDecoder.decode(
                Numeric.toHexString(response.getConstantResult(0).toByteArray()), balanceOf.getOutputParameters());
        BigInteger raw = (BigInteger) decoded.getFirst().getValue();
        return Trc20AbiCodec.fromRawAmount(raw, decimals);
    }

    private static long estimateTransferEnergy(TronTridentClient client, Actor owner, String to, BigDecimal amount) {
        Function transfer = new Function("transfer",
                List.of(new Address(to), new Uint256(Trc20AbiCodec.toRawAmount(amount, USDT_DECIMALS))),
                List.of(new TypeReference<org.tron.trident.abi.datatypes.Bool>() {
                }));
        Response.EstimateEnergyMessage estimate = client.api().estimateEnergy(owner.address(), NILE_USDT_CONTRACT,
                transfer, NodeType.FULL_NODE);
        return estimate.getEnergyRequired();
    }

    private static void prepareDatabase(JdbcTemplate jdbcTemplate, Actor source, Actor userA, Actor userB,
                                        Actor userC, Actor hot, Actor external, String runId) {
        for (Actor actor : List.of(userA, userB, userC, hot, external)) {
            jdbcTemplate.update("delete from ledger_balance where chain = ? and lower(account_id) = lower(?)",
                    CHAIN, actor.address());
            jdbcTemplate.update("delete from deposit_record where chain = ? and lower(to_address) = lower(?)",
                    CHAIN, actor.address());
            jdbcTemplate.update("delete from withdrawal_order where chain = ? and lower(from_address) = lower(?)",
                    CHAIN, actor.address());
            jdbcTemplate.update("delete from collection_record where chain = ? and lower(from_address) = lower(?)",
                    CHAIN, actor.address());
            insertAddress(jdbcTemplate, actor, "LIVE_" + actor.name().toUpperCase(Locale.ROOT));
        }
        insertAddress(jdbcTemplate, source, "LIVE_SOURCE");
        jdbcTemplate.update("delete from withdrawal_order where order_no like ?", runId + "%");
        jdbcTemplate.update("delete from collection_record where collection_no like ?", runId + "%");
        jdbcTemplate.update("delete from gas_topup_task where task_no like ?", runId + "%");
    }

    private static void insertAddress(JdbcTemplate jdbcTemplate, Actor actor, String role) {
        jdbcTemplate.update("""
                        insert into hot_wallet_address(chain, asset_symbol, address, address_index, wallet_role,
                                                       enabled, kms_key_ref)
                        values (?, 'TRX', ?, ?, ?, true, ?)
                        on conflict (chain, asset_symbol, wallet_role) do update set
                            address = excluded.address,
                            address_index = excluded.address_index,
                            enabled = true,
                            kms_key_ref = excluded.kms_key_ref,
                            updated_at = now()
                        """, CHAIN, actor.address(), actor.userId(), role, "derived:wallet-sig2-master:" + actor.path());
    }

    private static void upsertNileUsdt(JdbcTemplate jdbcTemplate) {
        String contractHex = TronAddressCodec.base58ToHex(NILE_USDT_CONTRACT);
        jdbcTemplate.update("""
                        insert into token_config(chain, network, symbol, standard, token_standard, contract_address,
                                                 contract_address_base58, contract_address_hex, decimals, enabled,
                                                 min_deposit, min_withdraw, min_deposit_amount, min_withdraw_amount,
                                                 collect_threshold, gas_strategy, confirmation_required, updated_at)
                        values (?, ?, 'USDT', 'TRC20', 'TRC20', ?, ?, ?, ?, true,
                                0.000001, 0.000001, 0.000001, 0.000001, 1, 'energy-bandwidth', 1, now())
                        on conflict (chain, symbol) do update set
                            network = excluded.network,
                            standard = excluded.standard,
                            token_standard = excluded.token_standard,
                            contract_address = excluded.contract_address,
                            contract_address_base58 = excluded.contract_address_base58,
                            contract_address_hex = excluded.contract_address_hex,
                            decimals = excluded.decimals,
                            enabled = true,
                            min_deposit_amount = excluded.min_deposit_amount,
                            min_withdraw_amount = excluded.min_withdraw_amount,
                            collect_threshold = excluded.collect_threshold,
                            gas_strategy = excluded.gas_strategy,
                            confirmation_required = excluded.confirmation_required,
                            updated_at = now()
                        """, CHAIN, NETWORK, NILE_USDT_CONTRACT, NILE_USDT_CONTRACT, contractHex, USDT_DECIMALS);
    }

    private static void insertWithdrawal(JdbcTemplate jdbcTemplate, String orderNo, Actor from, String to,
                                         String asset, BigDecimal amount, String status) {
        jdbcTemplate.update("""
                        insert into withdrawal_order(order_no, user_id, chain, asset_symbol, from_address,
                                                     to_address, amount, fee, status)
                        values (?, ?, ?, ?, ?, ?, ?, 0, ?)
                        on conflict (order_no) do update set status = excluded.status, updated_at = now()
                        """, orderNo, from.userId(), CHAIN, asset, from.address(), to, amount, status);
    }

    private static void updateWithdrawal(JdbcTemplate jdbcTemplate, String orderNo, String status,
                                         String txId, BigDecimal fee) {
        jdbcTemplate.update("""
                        update withdrawal_order
                        set status = ?, tx_hash = coalesce(?, tx_hash), fee = ?, updated_at = now()
                        where order_no = ?
                        """, status, txId, fee, orderNo);
    }

    private static void insertGasTask(JdbcTemplate jdbcTemplate, String taskNo, String target, String source,
                                      BigDecimal amount, String status) {
        jdbcTemplate.update("""
                        insert into gas_topup_task(task_no, chain, target_address, source_address, amount, status, reason)
                        values (?, ?, ?, ?, ?, ?, 'TRC20 energy/bandwidth top-up')
                        on conflict (task_no) do update set
                            amount = excluded.amount,
                            status = excluded.status,
                            updated_at = now()
                        """, taskNo, CHAIN, target, source, amount, status);
    }

    private static void updateGasTaskConfirmed(JdbcTemplate jdbcTemplate, String taskNo, String txId) {
        jdbcTemplate.update("""
                        update gas_topup_task
                        set status = 'CONFIRMED', tx_hash = ?, updated_at = now()
                        where task_no = ?
                        """, txId, taskNo);
    }

    private static void assertChainLedgerTrx(TronTridentClient client, JdbcTemplate jdbcTemplate, Actor actor) {
        assertBalanceEquals(trxBalance(client, actor.address()), ledger(jdbcTemplate, "TRX", actor.address()),
                actor.name() + " TRX ledger must match chain");
    }

    private static void assertTokenLedger(TronTridentClient client, JdbcTemplate jdbcTemplate, Actor actor,
                                          BigDecimal expected) {
        assertBalanceEquals(expected, ledger(jdbcTemplate, "USDT", actor.address()),
                actor.name() + " USDT ledger expected");
        assertBalanceEquals(trc20Balance(client, actor.address(), NILE_USDT_CONTRACT, USDT_DECIMALS),
                ledger(jdbcTemplate, "USDT", actor.address()), actor.name() + " USDT ledger must match chain");
    }

    private static void assertNoNegativeOrLockedLedger(JdbcTemplate jdbcTemplate, Actor... actors) {
        for (Actor actor : actors) {
            Integer badRows = jdbcTemplate.queryForObject("""
                            select count(*) from ledger_balance
                            where chain = ? and lower(account_id) = lower(?)
                              and (available_balance < 0 or locked_balance <> 0 or total_balance < 0)
                            """, Integer.class, CHAIN, actor.address());
            assertEquals(0, badRows);
        }
    }

    private static void assertDepositCount(JdbcTemplate jdbcTemplate, String txId, String to, int expected) {
        Integer count = jdbcTemplate.queryForObject("""
                        select count(*) from deposit_record
                        where chain = ? and tx_hash = ? and lower(to_address) = lower(?)
                          and status = 'CREDITED' and credited = true
                        """, Integer.class, CHAIN, txId, to);
        assertEquals(expected, count);
    }

    private static BigDecimal ledger(JdbcTemplate jdbcTemplate, String asset, String account) {
        List<BigDecimal> rows = jdbcTemplate.queryForList("""
                        select available_balance from ledger_balance
                        where chain = ? and asset_symbol = ? and lower(account_id) = lower(?)
                        """, BigDecimal.class, CHAIN, asset, account);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.getFirst();
    }

    private static BigDecimal trxBalance(TronTridentClient client, String address) {
        return new BigDecimal(client.getBalanceSun(address)).divide(SUN, TRX_DECIMALS, RoundingMode.DOWN)
                .stripTrailingZeros();
    }

    private static BigDecimal feeTrx(Response.TransactionInfo info) {
        return new BigDecimal(info.getFee()).divide(SUN, TRX_DECIMALS, RoundingMode.DOWN).stripTrailingZeros();
    }

    private static long toSun(BigDecimal trx) {
        return trx.movePointRight(TRX_DECIMALS).toBigIntegerExact().longValueExact();
    }

    private static Map<String, TronScanner.TokenConfig> tokenMap() {
        String contractHex = TronAddressCodec.base58ToHex(NILE_USDT_CONTRACT);
        return Map.of(contractHex, new TronScanner.TokenConfig("USDT", contractHex, USDT_DECIMALS));
    }

    private static Set<String> platform(Actor actor) {
        return Set.of(actor.address().toLowerCase(Locale.ROOT));
    }

    private static void assertBalanceEquals(BigDecimal expected, BigDecimal actual, String message) {
        assertEquals(0, expected.setScale(6, RoundingMode.DOWN).compareTo(actual.setScale(6, RoundingMode.DOWN)),
                message + ", expected=" + expected + ", actual=" + actual);
    }

    private static Actor actor(String name, int userId) throws Exception {
        String path = "m/44/23/1/" + userId + "/0";
        ECKey ecKey = deriveEcKey(path);
        KeyPair keyPair = TronTridentKeyFactory.fromBitcoinEcKey(ecKey);
        return new Actor(name, userId, path, keyPair, keyPair.toBase58CheckAddress());
    }

    private static ECKey deriveEcKey(String path) throws Exception {
        String[] parts = path.substring(2).split("/");
        Bip32Node node = Bip32Node.decode(sig2Master());
        for (String part : parts) {
            node = node.getChild(Integer.parseInt(part));
        }
        return node.getEcKey();
    }

    private static String sig2Master() throws Exception {
        String fromProperty = System.getProperty("tron.sig2.master");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = System.getenv("ATOMEX_SIG2_MASTER_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        Path yaml = projectRoot().resolve("backendservices/wallet-sig2/src/main/resources/application-test.yaml");
        for (String line : Files.readAllLines(yaml)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("masterKey:")) {
                return trimmed.substring("masterKey:".length()).trim();
            }
        }
        throw new IllegalStateException("missing sig2 master key for TRON live test");
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.exists(current.resolve("backendservices"))
                    && Files.exists(current.resolve("currency-sdks"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate project root");
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getProperty("tron.db.url", "jdbc:postgresql://127.0.0.1:5432/wallet"));
        dataSource.setUsername(System.getProperty("tron.db.user", "wallet"));
        dataSource.setPassword(System.getProperty("tron.db.password", "wallet123"));
        return dataSource;
    }

    private static void writeReport(String runId, Map<String, String> values) throws Exception {
        Path report = Path.of("target", "tron-live-flow-report.properties");
        Files.createDirectories(report.getParent());
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("runId", runId);
        ordered.putAll(values);
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            content.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        Files.writeString(report, content.toString());
    }

    private record Actor(String name, int userId, String path, KeyPair keyPair, String address) {
    }

    private record TxResult(String txId, long blockHeight, BigDecimal fee) {
    }
}
