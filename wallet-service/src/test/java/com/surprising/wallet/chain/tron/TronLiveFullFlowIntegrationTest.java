package com.surprising.wallet.chain.tron;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TronLiveFullFlowIntegrationTest {
    private static final UUID TEST_TENANT_ID = UUID.fromString("77020000-0000-0000-0000-000000000002");
    private static final String CHAIN = ChainType.TRON.name();
    private static final int TRX_DECIMALS = 6;
    private static final int TOKEN_DECIMALS = 6;
    private static final BigDecimal SUN = new BigDecimal("1000000");
    private static final long FEE_LIMIT_SUN = 100_000_000L;

    @Test
    void shouldExecuteLocalTrxAndTrc20FullFlow() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("tron.live.flow.enabled"),
                "run scripts/regtest/run-tron-flow.sh for isolated TRX/TRC20 transactions");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        WalletKeyMaterialProvider keyMaterial = new WalletKeyMaterialProvider(
                new WalletKeyConfigStore(jdbcTemplate), WalletKeyMaterialProvider.Mode.WALLET_SERVER);
        ChainJdbcRepository repository = new ChainJdbcRepository(jdbcTemplate);
        TronDepositScanner depositScanner = new TronDepositScanner(repository, new TronScanner());
        TronTransactionService trxService = new TronTransactionService();
        TronTrc20Service trc20Service = new TronTrc20Service();
        TronGasEstimator gasEstimator = new TronGasEstimator();
        TronWaitingGasStateService waitingGasStateService = new TronWaitingGasStateService();

        String fullNode = System.getProperty("tron.fullnode", "127.0.0.1:50051");
        String solidityNode = System.getProperty("tron.soliditynode", "127.0.0.1:50052");
        String apiKey = System.getProperty("tron.apiKey", "");
        String network = requiredProperty("tron.live.network");
        String sourcePrivateKey = requiredEnvironment("TRON_LOCAL_SOURCE_KEY");
        TokenSpec usdt = new TokenSpec("USDT", requiredProperty("tron.live.usdt.contract"), TOKEN_DECIMALS);
        TokenSpec usdc = new TokenSpec("USDC", requiredProperty("tron.live.usdc.contract"), TOKEN_DECIMALS);
        int requiredConfirmations = Integer.getInteger("tron.confirmations", 1);
        String runId = "TRON-LIVE-" + System.currentTimeMillis();
        int userBase = Integer.getInteger("tron.live.userBase",
                93000 + (int) (System.currentTimeMillis() % 30000));

        try (TronTridentClient client = new TronTridentClient(fullNode, solidityNode, apiKey)) {
            Bip32Node sig2Root = keyMaterial.sig2Root();
            Actor source = actorFromPrivateKey("source", 91001, sourcePrivateKey);
            Actor userA = actor(sig2Root, "userA", userBase);
            Actor userB = actor(sig2Root, "userB", userBase + 1);
            Actor userC = actor(sig2Root, "userC", userBase + 2);
            Actor userD = actor(sig2Root, "userD", userBase + 3);
            Actor userE = actor(sig2Root, "userE", userBase + 4);
            Actor hot = actor(sig2Root, "hot", userBase + 5);
            Actor external = actor(sig2Root, "external", userBase + 6);

            prepareDatabase(jdbcTemplate, List.of(source, userA, userB, userC, userD, userE, hot, external), runId);
            upsertToken(jdbcTemplate, network, usdt);
            upsertToken(jdbcTemplate, network, usdc);

            BigDecimal sourceTrx = trxBalance(client, source.address());
            assertTrue(sourceTrx.compareTo(new BigDecimal("200")) > 0,
                    "fund TRON test source " + source.address() + " with TRX; current=" + sourceTrx);
            for (TokenSpec token : List.of(usdt, usdc)) {
                BigDecimal balance = trc20Balance(client, source.address(), token.contract(), token.decimals());
                assertTrue(balance.compareTo(new BigDecimal("80")) > 0,
                        "fund TRON test source " + source.address() + " with " + token.symbol()
                                + "; current=" + balance);
            }

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
            assertFalse(repository.freezeLedgerBalance(CHAIN, "TRX", ledgerAccount(userA), new BigDecimal("1000")),
                    "overspend guard must reject impossible TRX withdrawal before broadcast");
            TxResult trxCollection = collectTrx(jdbcTemplate, repository, client, trxService,
                    runId + "-TRX-COLLECT", userA, hot, new BigDecimal("1"));
            depositScanner.scanAndCreditTrx(client, trxCollection.blockHeight(), platform(hot),
                    requiredConfirmations);
            assertDepositCount(jdbcTemplate, trxCollection.txId(), hot.address(), 1);
            assertChainLedgerTrx(client, jdbcTemplate, userA);
            assertChainLedgerTrx(client, jdbcTemplate, hot);

            TokenFlowResult usdtFlow = executeTokenFlow(jdbcTemplate, repository, depositScanner, client,
                    trxService, trc20Service, gasEstimator, waitingGasStateService, runId, source,
                    userB, userC, hot, external, usdt, requiredConfirmations);
            TokenFlowResult usdcFlow = executeTokenFlow(jdbcTemplate, repository, depositScanner, client,
                    trxService, trc20Service, gasEstimator, waitingGasStateService, runId, source,
                    userD, userE, hot, external, usdc, requiredConfirmations);

            assertNoNegativeOrLockedLedger(jdbcTemplate, userA, userB, userC, userD, userE, hot);
            Map<String, String> report = new LinkedHashMap<>();
            report.put("source", source.address());
            report.put("userA", userA.address());
            report.put("userB", userB.address());
            report.put("userC", userC.address());
            report.put("userD", userD.address());
            report.put("userE", userE.address());
            report.put("hot", hot.address());
            report.put("external", external.address());
            report.put("trxDepositTxid", trxDeposit.txId());
            report.put("trxWithdraw1Txid", trxWithdraw1.txId());
            report.put("trxWithdraw2Txid", trxWithdraw2.txId());
            report.put("trxCollectionTxid", trxCollection.txId());
            appendTokenReport(report, "usdt", usdtFlow);
            appendTokenReport(report, "usdc", usdcFlow);
            writeReport(runId, report);
        }
    }

    private static TokenFlowResult executeTokenFlow(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                                    TronDepositScanner depositScanner, TronTridentClient client,
                                                    TronTransactionService trxService, TronTrc20Service trc20Service,
                                                    TronGasEstimator gasEstimator,
                                                    TronWaitingGasStateService waitingGasStateService,
                                                    String runId, Actor source, Actor collectionUser,
                                                    Actor withdrawalUser, Actor hot, Actor external,
                                                    TokenSpec token, int requiredConfirmations) throws Exception {
        String flowId = runId + "-" + token.symbol();
        TxResult depositForCollection = sendTrc20(client, trc20Service, source, token.contract(),
                collectionUser.address(), new BigDecimal("30"), token.decimals());
        scanAndAssertTokenDeposit(depositScanner, client, jdbcTemplate, depositForCollection, collectionUser,
                token, new BigDecimal("30"), requiredConfirmations);
        TxResult depositForWithdrawal = sendTrc20(client, trc20Service, source, token.contract(),
                withdrawalUser.address(), new BigDecimal("20"), token.decimals());
        scanAndAssertTokenDeposit(depositScanner, client, jdbcTemplate, depositForWithdrawal, withdrawalUser,
                token, new BigDecimal("20"), requiredConfirmations);

        var collectionGas = waitingGasStateService.evaluate(CHAIN, flowId + "-COLLECT", collectionUser.address(),
                trxBalance(client, collectionUser.address()), new BigDecimal("8"), TronGasPolicy.nileDefault());
        assertTrue(collectionGas.waitingGas(), token.symbol() + " collection must wait for gas before top-up");
        insertGasTask(jdbcTemplate, collectionGas.gasTaskNo(), collectionUser.address(), source.address(),
                collectionGas.topupAmount(), "WAITING_GAS");
        TxResult collectionGasTopup = sendTrx(client, trxService, source, collectionUser.address(),
                collectionGas.topupAmount());
        updateGasTaskConfirmed(jdbcTemplate, collectionGas.gasTaskNo(), collectionGasTopup.txId());
        repository.incrementLedgerBalance(TEST_TENANT_ID, CHAIN, "TRX",
                collectionUser.address().toLowerCase(Locale.ROOT),
                collectionGas.topupAmount());

        long estimatedEnergy = estimateTransferEnergy(client, collectionUser, hot.address(),
                new BigDecimal("30"), token);
        BigDecimal estimatedFee = gasEstimator.estimateTrc20FeeTrx(Math.max(estimatedEnergy, 1L), 420L,
                TronGasPolicy.nileDefault());
        assertTrue(estimatedFee.compareTo(BigDecimal.ZERO) > 0);

        TxResult collection = collectTrc20(jdbcTemplate, repository, client, trc20Service,
                flowId + "-COLLECT", collectionUser, hot, new BigDecimal("30"), token);
        depositScanner.scanAndCreditTrc20(client, collection.blockHeight(), tokenMap(token), platform(hot),
                requiredConfirmations);
        assertBalanceEquals(BigDecimal.ZERO, ledger(jdbcTemplate, token.symbol(), collectionUser.address()),
                "collected user token ledger must be zero");
        assertBalanceEquals(new BigDecimal("30"), ledger(jdbcTemplate, token.symbol(), hot.address()),
                "hot wallet token ledger must increase through collection scan");
        assertChainLedgerTrx(client, jdbcTemplate, collectionUser);
        assertTokenLedger(client, jdbcTemplate, collectionUser, token, BigDecimal.ZERO);
        assertTokenLedger(client, jdbcTemplate, hot, token, new BigDecimal("30"));

        var withdrawalGas = waitingGasStateService.evaluate(CHAIN, flowId + "-WITHDRAW", withdrawalUser.address(),
                trxBalance(client, withdrawalUser.address()), new BigDecimal("8"), TronGasPolicy.nileDefault());
        assertTrue(withdrawalGas.waitingGas());
        insertGasTask(jdbcTemplate, withdrawalGas.gasTaskNo(), withdrawalUser.address(), source.address(),
                withdrawalGas.topupAmount(), "WAITING_GAS");
        TxResult withdrawalGasTopup = sendTrx(client, trxService, source, withdrawalUser.address(),
                withdrawalGas.topupAmount());
        updateGasTaskConfirmed(jdbcTemplate, withdrawalGas.gasTaskNo(), withdrawalGasTopup.txId());
        repository.incrementLedgerBalance(TEST_TENANT_ID, CHAIN, "TRX",
                withdrawalUser.address().toLowerCase(Locale.ROOT),
                withdrawalGas.topupAmount());

        TxResult withdrawal = withdrawTrc20(jdbcTemplate, repository, client, trc20Service,
                flowId + "-WITHDRAW", withdrawalUser, external.address(), new BigDecimal("5"), token);
        assertTrue(withdrawal.fee().compareTo(BigDecimal.ZERO) >= 0);
        assertTokenLedger(client, jdbcTemplate, withdrawalUser, token, new BigDecimal("15"));
        assertChainLedgerTrx(client, jdbcTemplate, withdrawalUser);

        assertDepositCount(jdbcTemplate, depositForCollection.txId(), collectionUser.address(), 1);
        assertDepositCount(jdbcTemplate, depositForWithdrawal.txId(), withdrawalUser.address(), 1);
        assertDepositCount(jdbcTemplate, collection.txId(), hot.address(), 1);
        return new TokenFlowResult(depositForCollection.txId(), depositForWithdrawal.txId(),
                collectionGasTopup.txId(), collection.txId(), withdrawalGasTopup.txId(), withdrawal.txId(),
                estimatedEnergy, estimatedFee);
    }

    private static void appendTokenReport(Map<String, String> report, String prefix, TokenFlowResult result) {
        report.put(prefix + "DepositBTxid", result.depositForCollectionTxId());
        report.put(prefix + "DepositCTxid", result.depositForWithdrawalTxId());
        report.put(prefix + "GasTopupBTxid", result.collectionGasTopupTxId());
        report.put(prefix + "CollectionTxid", result.collectionTxId());
        report.put(prefix + "GasTopupCTxid", result.withdrawalGasTopupTxId());
        report.put(prefix + "WithdrawTxid", result.withdrawalTxId());
        report.put(prefix + "EstimatedEnergy", String.valueOf(result.estimatedEnergy()));
        report.put(prefix + "EstimatedFeeTrx", result.estimatedFee().toPlainString());
    }

    private static void scanAndAssertTokenDeposit(TronDepositScanner scanner, TronTridentClient client,
                                                  JdbcTemplate jdbcTemplate, TxResult tx, Actor actor,
                                                  TokenSpec token, BigDecimal expectedAmount,
                                                  int confirmations) throws Exception {
        List<DepositEvent> deposits = scanner.scanAndCreditTrc20(client, tx.blockHeight(), tokenMap(token),
                platform(actor),
                confirmations);
        assertTrue(deposits.stream().anyMatch(event -> tx.txId().equalsIgnoreCase(event.txId())));
        assertBalanceEquals(expectedAmount, ledger(jdbcTemplate, token.symbol(), actor.address()),
                "TRC20 deposit ledger must match amount");
        scanner.scanAndCreditTrc20(client, tx.blockHeight(), tokenMap(token), platform(actor), confirmations);
        assertBalanceEquals(expectedAmount, ledger(jdbcTemplate, token.symbol(), actor.address()),
                "TRC20 duplicate scan must not double credit");
    }

    private static TxResult withdrawTrx(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                        TronTridentClient client, TronTransactionService service,
                                        String orderNo, Actor from, String to, BigDecimal amount) throws Exception {
        insertWithdrawal(jdbcTemplate, orderNo, from, to, "TRX", amount, "CREATED");
        assertTrue(repository.freezeLedgerBalance(CHAIN, "TRX", ledgerAccount(from), amount));
        updateWithdrawal(jdbcTemplate, orderNo, "FROZEN", null, BigDecimal.ZERO);
        updateWithdrawal(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO);
        TxResult tx = sendTrx(client, service, from, to, amount);
        updateWithdrawal(jdbcTemplate, orderNo, "SENT", tx.txId(), tx.fee());
        assertTrue(repository.settleLockedDebit(CHAIN, "TRX", ledgerAccount(from), amount));
        if (tx.fee().signum() > 0) {
            assertTrue(repository.debitLedgerBalance(CHAIN, "TRX", ledgerAccount(from), tx.fee()));
        }
        updateWithdrawal(jdbcTemplate, orderNo, "CONFIRMED", tx.txId(), tx.fee());
        return tx;
    }

    private static TxResult collectTrx(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                       TronTridentClient client, TronTransactionService service,
                                       String collectionNo, Actor from, Actor to, BigDecimal amount) throws Exception {
        jdbcTemplate.update("""
                        insert into collection_record(collection_no, chain, asset_symbol, from_address, to_address,
                                                      amount, fee, status)
                        values (?, ?, 'TRX', ?, ?, ?, 0, 'CREATED')
                        on conflict (chain, collection_no) do update
                        set status = 'CREATED', updated_at = now()
                        """, collectionNo, CHAIN, from.address(), to.address(), amount);
        assertTrue(repository.freezeLedgerBalance(CHAIN, "TRX", ledgerAccount(from), amount));
        jdbcTemplate.update("update collection_record set status = 'SIGNING', updated_at = now() where collection_no = ?",
                collectionNo);
        TxResult tx = sendTrx(client, service, from, to.address(), amount);
        jdbcTemplate.update("""
                        update collection_record
                        set status = 'SENT', tx_hash = ?, fee = ?, updated_at = now()
                        where collection_no = ?
                        """, tx.txId(), tx.fee(), collectionNo);
        assertTrue(repository.settleLockedDebit(CHAIN, "TRX", ledgerAccount(from), amount));
        if (tx.fee().signum() > 0) {
            assertTrue(repository.debitLedgerBalance(CHAIN, "TRX", ledgerAccount(from), tx.fee()));
        }
        jdbcTemplate.update("""
                        update collection_record
                        set status = 'CONFIRMED', tx_hash = ?, fee = ?, updated_at = now()
                        where collection_no = ?
                        """, tx.txId(), tx.fee(), collectionNo);
        return tx;
    }

    private static TxResult withdrawTrc20(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                          TronTridentClient client, TronTrc20Service service,
                                          String orderNo, Actor from, String to, BigDecimal amount,
                                          TokenSpec token) throws Exception {
        insertWithdrawal(jdbcTemplate, orderNo, from, to, token.symbol(), amount, "CREATED");
        assertTrue(repository.freezeLedgerBalance(CHAIN, token.symbol(), ledgerAccount(from), amount));
        updateWithdrawal(jdbcTemplate, orderNo, "FROZEN", null, BigDecimal.ZERO);
        updateWithdrawal(jdbcTemplate, orderNo, "SIGNING", null, BigDecimal.ZERO);
        TxResult tx = sendTrc20(client, service, from, token.contract(), to, amount, token.decimals());
        updateWithdrawal(jdbcTemplate, orderNo, "SENT", tx.txId(), tx.fee());
        assertTrue(repository.settleLockedDebit(CHAIN, token.symbol(), ledgerAccount(from), amount));
        if (tx.fee().signum() > 0) {
            assertTrue(repository.debitLedgerBalance(CHAIN, "TRX", ledgerAccount(from), tx.fee()));
        }
        updateWithdrawal(jdbcTemplate, orderNo, "CONFIRMED", tx.txId(), tx.fee());
        return tx;
    }

    private static TxResult collectTrc20(JdbcTemplate jdbcTemplate, ChainJdbcRepository repository,
                                         TronTridentClient client, TronTrc20Service service,
                                         String collectionNo, Actor from, Actor to, BigDecimal amount,
                                         TokenSpec token) throws Exception {
        jdbcTemplate.update("""
                        insert into collection_record(collection_no, chain, asset_symbol, from_address, to_address,
                                                      amount, fee, status)
                        values (?, ?, ?, ?, ?, ?, 0, 'CREATED')
                        on conflict (chain, collection_no) do update
                        set status = 'CREATED', updated_at = now()
                        """, collectionNo, CHAIN, token.symbol(), from.address(), to.address(), amount);
        assertTrue(repository.freezeLedgerBalance(CHAIN, token.symbol(), ledgerAccount(from), amount));
        jdbcTemplate.update("update collection_record set status = 'SIGNING', updated_at = now() where collection_no = ?",
                collectionNo);
        TxResult tx = sendTrc20(client, service, from, token.contract(), to.address(), amount, token.decimals());
        jdbcTemplate.update("""
                        update collection_record
                        set status = 'SENT', tx_hash = ?, fee = ?, updated_at = now()
                        where collection_no = ?
                        """, tx.txId(), tx.fee(), collectionNo);
        assertTrue(repository.settleLockedDebit(CHAIN, token.symbol(), ledgerAccount(from), amount));
        if (tx.fee().signum() > 0) {
            assertTrue(repository.debitLedgerBalance(CHAIN, "TRX", ledgerAccount(from), tx.fee()));
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

    private static long estimateTransferEnergy(TronTridentClient client, Actor owner, String to, BigDecimal amount,
                                               TokenSpec token) {
        Function transfer = new Function("transfer",
                List.of(new Address(to), new Uint256(Trc20AbiCodec.toRawAmount(amount, token.decimals()))),
                List.of(new TypeReference<org.tron.trident.abi.datatypes.Bool>() {
                }));
        Response.EstimateEnergyMessage estimate = client.api().estimateEnergy(owner.address(), token.contract(),
                transfer, NodeType.FULL_NODE);
        return estimate.getEnergyRequired();
    }

    private static void prepareDatabase(JdbcTemplate jdbcTemplate, List<Actor> actors, String runId) {
        jdbcTemplate.update("""
                insert into custody_tenant(id, slug, name)
                values (?, 'tron-live-integration', 'TRON live integration tenant')
                on conflict (id) do nothing
                """, TEST_TENANT_ID);
        for (Actor actor : actors) {
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
        jdbcTemplate.update("delete from withdrawal_order where order_no like ?", runId + "%");
        jdbcTemplate.update("delete from collection_record where collection_no like ?", runId + "%");
        jdbcTemplate.update("delete from gas_topup_task where task_no like ?", runId + "%");
    }

    private static void insertAddress(JdbcTemplate jdbcTemplate, Actor actor, String role) {
        jdbcTemplate.update("""
                        insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                                                  address, owner_address, derivation_path, wallet_role, enabled)
                        values (?, ?, 'TRX', ?, ?, 1, 0, ?, ?, ?, ?, true)
                        on conflict (chain, asset_symbol, address) do update set
                            tenant_id = excluded.tenant_id,
                            account_id = excluded.account_id,
                            user_id = excluded.user_id,
                            biz = excluded.biz,
                            address_index = excluded.address_index,
                            owner_address = excluded.owner_address,
                            derivation_path = excluded.derivation_path,
                            wallet_role = excluded.wallet_role,
                            enabled = true,
                            updated_at = now()
                        """, TEST_TENANT_ID, CHAIN, actor.address(), actor.userId(), actor.address(), actor.address(),
                actor.path(), role);
    }

    private static void upsertToken(JdbcTemplate jdbcTemplate, String network, TokenSpec token) {
        String contractHex = TronAddressCodec.base58ToHex(token.contract());
        jdbcTemplate.update("""
                        insert into token_config(chain, network, symbol, standard, token_standard, contract_address,
                                                 contract_address_base58, contract_address_hex, decimals, enabled,
                                                 min_deposit, min_withdraw, min_deposit_amount, min_withdraw_amount,
                                                 collect_threshold, gas_strategy, confirmation_required, updated_at)
                        values (?, ?, ?, 'TRC20', 'TRC20', ?, ?, ?, ?, true,
                                0.000001, 0.000001, 0.000001, 0.000001, 1, 'energy-bandwidth', 1, now())
                        on conflict (chain, network, symbol) do update set
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
                        """, CHAIN, network, token.symbol(), token.contract(), token.contract(), contractHex,
                token.decimals());
    }

    private static void insertWithdrawal(JdbcTemplate jdbcTemplate, String orderNo, Actor from, String to,
                                         String asset, BigDecimal amount, String status) {
        jdbcTemplate.update("""
                        insert into withdrawal_order(tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                                                     to_address, amount, fee, status)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                        on conflict (chain, order_no) do update
                        set status = excluded.status, updated_at = now()
                        """, TEST_TENANT_ID, orderNo, from.userId(), CHAIN, asset,
                from.address(), to, amount, status);
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
                                          TokenSpec token, BigDecimal expected) {
        assertBalanceEquals(expected, ledger(jdbcTemplate, token.symbol(), actor.address()),
                actor.name() + " " + token.symbol() + " ledger expected");
        assertBalanceEquals(trc20Balance(client, actor.address(), token.contract(), token.decimals()),
                ledger(jdbcTemplate, token.symbol(), actor.address()),
                actor.name() + " " + token.symbol() + " ledger must match chain");
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

    private static Map<String, TronScanner.TokenConfig> tokenMap(TokenSpec token) {
        String contractHex = TronAddressCodec.base58ToHex(token.contract());
        return Map.of(contractHex, new TronScanner.TokenConfig(token.symbol(), contractHex, token.decimals()));
    }

    private static Set<String> platform(Actor actor) {
        return Set.of(actor.address().toLowerCase(Locale.ROOT));
    }

    private static String ledgerAccount(Actor actor) {
        return actor.address().toLowerCase(Locale.ROOT);
    }

    private static void assertBalanceEquals(BigDecimal expected, BigDecimal actual, String message) {
        assertEquals(0, expected.setScale(6, RoundingMode.DOWN).compareTo(actual.setScale(6, RoundingMode.DOWN)),
                message + ", expected=" + expected + ", actual=" + actual);
    }

    private static Actor actor(Bip32Node sig2Root, String name, int userId) {
        String path = "m/44/23/1/" + userId + "/0";
        ECKey ecKey = deriveEcKey(sig2Root, path);
        KeyPair keyPair = TronTridentKeyFactory.fromBitcoinEcKey(ecKey);
        return new Actor(name, userId, path, keyPair, keyPair.toBase58CheckAddress());
    }

    private static Actor actorFromPrivateKey(String name, int userId, String privateKey) {
        KeyPair keyPair = new KeyPair(privateKey);
        return new Actor(name, userId, "local-test-source", keyPair, keyPair.toBase58CheckAddress());
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("missing required test property: " + name);
        }
        return value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv().getOrDefault(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("missing required test environment variable: " + name);
        }
        return value;
    }

    private static ECKey deriveEcKey(Bip32Node sig2Root, String path) {
        String[] parts = path.substring(2).split("/");
        Bip32Node node = sig2Root;
        for (String part : parts) {
            node = node.getChild(Integer.parseInt(part));
        }
        return node.getEcKey();
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

    private record TokenSpec(String symbol, String contract, int decimals) {
    }

    private record TokenFlowResult(String depositForCollectionTxId, String depositForWithdrawalTxId,
                                   String collectionGasTopupTxId, String collectionTxId,
                                   String withdrawalGasTopupTxId, String withdrawalTxId,
                                   long estimatedEnergy, BigDecimal estimatedFee) {
    }

    private record TxResult(String txId, long blockHeight, BigDecimal fee) {
    }
}
