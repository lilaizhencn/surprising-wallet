package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.queue.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.service.*;
import com.surprising.wallet.sig.first.FirstSignerRegistry;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import com.surprising.wallet.sig.first.service.*;
import com.surprising.wallet.sig.second.SecondSignerRegistry;
import com.surprising.wallet.sig.second.Sig2KeyDerivation;
import com.surprising.wallet.sig.second.impl.*;
import com.surprising.wallet.sig.second.service.SecondSigningService;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Actual PGMQ stages and two independent signing keys, with only the chain RPC mocked. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class UtxoSigningPipelineIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String INPUT = "c".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private PgmqClient queue;
    private ChainSigningTransactionRepository signing;
    private WithdrawalOrderRepository orders;
    private UtxoRepository utxos;
    private LedgerBalanceRepository balances;
    private BlockchainRuntimeService runtime;
    private UtxoSigningSubmissionService submissions;
    private UtxoBroadcastService broadcasts;
    private BitcoinLikeSettlementService settlement;
    private FirstSigningService first;
    private SecondSigningService second;
    private WithdrawalQueueService done;
    private RbfBumpService rbf;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        for (WalletQueue stage : WalletQueue.values()) {
            jdbc.execute("truncate pgmq.q_" + stage.queueName() + ", pgmq.a_" + stage.queueName()
                    + ", pgmq.q_" + stage.queueName() + "_dead, pgmq.a_" + stage.queueName() + "_dead");
        }
        var manager = new DataSourceTransactionManager(ds);
        queue = new PgmqClient(jdbc);
        var worker = new QueueWorker(queue, manager);
        signing = new ChainSigningTransactionRepository(jdbc, json);
        orders = new WithdrawalOrderRepository(jdbc);
        utxos = new UtxoRepository(jdbc);
        balances = new LedgerBalanceRepository(jdbc);
        runtime = mock(BlockchainRuntimeService.class);
        when(runtime.broadcastSignedTransaction(any(), any())).thenAnswer(call -> {
            UtxoSignedTransaction signed = call.getArgument(1);
            var tx = Transaction.read(ByteBuffer.wrap(HexFormat.of().parseHex(signed.transactionHex())));
            assertEquals(signed.txId(), tx.getTxId().toString());
            return signed.txId();
        });
        var switches = mock(WalletRuntimeConfigService.class);
        var rates = mock(ChainFeeRateRepository.class);
        when(rates.find("btc")).thenReturn("10");
        var leases = mock(WalletTaskLeaseService.class);
        when(leases.ownerId()).thenReturn("pipeline-test");
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.registerBean(ChainSigningTransactionRepository.class, () -> signing);
        context.registerBean(WithdrawalOrderRepository.class, () -> orders);
        context.registerBean(CollectionRecordRepository.class, () -> new CollectionRecordRepository(jdbc));
        context.registerBean(UtxoRepository.class, () -> utxos);
        context.registerBean(LedgerBalanceRepository.class, () -> balances);
        context.registerBean(PgmqClient.class, () -> queue);
        context.registerBean(QueueWorker.class, () -> worker);
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(BlockchainRuntimeService.class, () -> runtime);
        context.registerBean(WalletRuntimeConfigService.class, () -> switches);
        context.registerBean(ChainFeeRateRepository.class, () -> rates);
        context.registerBean(WalletTaskLeaseService.class, () -> leases);
        context.registerBean(TransactionService.class, () -> mock(TransactionService.class));
        context.registerBean(WithdrawalSettlementService.class);
        context.registerBean(BitcoinLikeSettlementService.class);
        context.registerBean(UtxoBroadcastService.class);
        context.registerBean(UtxoSigningSubmissionService.class);
        context.registerBean(WithdrawalQueueService.class);
        context.registerBean(RbfBumpService.class);
        context.refresh();
        submissions = context.getBean(UtxoSigningSubmissionService.class);
        broadcasts = context.getBean(UtxoBroadcastService.class);
        settlement = context.getBean(BitcoinLikeSettlementService.class);
        done = context.getBean(WithdrawalQueueService.class);
        rbf = context.getBean(RbfBumpService.class);
        var firstKeys = WalletKeyMaterialProvider.forSig1(seed(0x11),
                Bip32Node.getMasterKey(bytes(0x22)).pubSerialize(0, false),
                Bip32Node.getMasterKey(bytes(0x33)).pubSerialize(0, false));
        var publicKeys = new PubKeyConfig(firstKeys);
        var secondKeys = new Sig2KeyDerivation(WalletKeyMaterialProvider.forSig2(seed(0x22)));
        first = new FirstSigningService(worker, json, new FirstSignerRegistry(List.of(
                new BtcFirstSignService(publicKeys, firstKeys), new LtcFirstSignService(publicKeys, firstKeys),
                new BchFirstSignService(publicKeys, firstKeys), new DogeFirstSignService(publicKeys, firstKeys))));
        second = new SecondSigningService(worker, new SecondSignerRegistry(List.of(
                new BtcSecondSignService(secondKeys), new LtcSecondSignService(secondKeys),
                new BchSecondSignService(secondKeys), new DogeSecondSignService(secondKeys))), json);
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @ParameterizedTest @ValueSource(strings = {"BTC", "LTC", "BCH", "DOGE"})
    void nativeChainsReachConfirmationWithoutDuplicateBroadcastOrDebit(String chain) {
        var transaction = prepare(chain, false);
        var result = sign(transaction);
        assertTrue(result.successful(), result.error());
        done.broadcast();
        assertEquals(Constants.SENT, current(transaction).status());
        assertEquals("SENT", orderStatus(chain));
        assertEquals("LOCKED", inputStatus(chain));
        assertEquals(1, queueCount("q_wallet_withdraw_event"));
        queue.send(WalletQueue.SIGN_DONE, JacksonJson.writeValue(json, result), QueueTenant.headers(TENANT));
        done.broadcast();
        verify(runtime, times(1)).broadcastSignedTransaction(any(), any());
        assertEquals(1, queueCount("q_wallet_withdraw_event"));
        settlement.settleConfirmed(chain, transaction.id(), result.transaction().txId());
        settlement.settleConfirmed(chain, transaction.id(), result.transaction().txId());
        queue.send(WalletQueue.SIGN_DONE, JacksonJson.writeValue(json, result), QueueTenant.headers(TENANT));
        done.broadcast();
        assertConfirmed(transaction, result.transaction().txId());
        verify(runtime, times(1)).broadcastSignedTransaction(any(), any());
        assertEquals(3, jdbc.queryForObject("select count(*) from pgmq.a_wallet_sign_done", Integer.class));
    }

    @Test void initialUnknownBroadcastRetainsSignedHashAndInputsUntilItConfirms() {
        var transaction = prepare("BTC", false);
        var result = sign(transaction);
        doReturn("").when(runtime).broadcastSignedTransaction(any(), any());
        done.broadcast();
        assertEquals("BROADCAST_UNKNOWN", orderStatus("BTC"));
        assertEquals(result.transaction().txId(), current(transaction).txId());
        assertEquals(1, signing.listPendingConfirmation("BTC").size());
        broadcasts.apply(UtxoSigningResult.failure(new UtxoSigningTask(transaction.id(), transaction.request()), "late failure"));
        assertEquals("LOCKED", inputStatus("BTC"));
        assertTrue(balances.find(TENANT, "BTC", "BTC", account("BTC")).orElseThrow().getLockedBalance().signum() > 0);
        settlement.settleConfirmed("BTC", transaction.id(), result.transaction().txId());
        assertConfirmed(transaction, result.transaction().txId());
    }

    @Test void firstSigningFailureReleasesOnlyTheUnbroadcastIntent() {
        var transaction = prepare("BTC", true);
        var result = sign(transaction);
        assertFalse(result.successful());
        done.broadcast();
        assertEquals(Constants.DELETE, current(transaction).status());
        assertEquals("FAILED", orderStatus("BTC"));
        assertEquals("AVAILABLE", inputStatus("BTC"));
        var balance = balances.find(TENANT, "BTC", "BTC", account("BTC")).orElseThrow();
        assertEquals(0, balance.getLockedBalance().signum());
        assertEquals(0, BigDecimal.ONE.compareTo(balance.getAvailableBalance()));
        verifyNoInteractions(runtime);
    }

    @Test void eventQueueFailureRollsBackBusinessStateAndRetriesTheSameSignedTransaction() {
        var transaction = prepare("BTC", false);
        var result = sign(transaction);
        jdbc.execute("alter table pgmq.q_wallet_withdraw_event rename to q_wallet_withdraw_event_unavailable");
        try {
            done.broadcast();
        } finally {
            jdbc.execute("alter table pgmq.q_wallet_withdraw_event_unavailable rename to q_wallet_withdraw_event");
        }
        assertEquals(Constants.SIGNING, current(transaction).status());
        assertNull(current(transaction).signed());
        assertEquals("SIGNING", orderStatus("BTC"));
        assertEquals("LOCKED", inputStatus("BTC"));
        assertEquals(1, queueCount("q_wallet_sign_done"));
        assertEquals(0, queueCount("a_wallet_sign_done"));
        var pending = json.readValue(jdbc.queryForObject("select message::text from pgmq.q_wallet_sign_done", String.class), UtxoSigningResult.class);
        assertEquals(result, pending);
        jdbc.queryForList("select pgmq.set_vt('wallet_sign_done', msg_id, 0) from pgmq.q_wallet_sign_done");
        done.broadcast();
        verify(runtime, times(2)).broadcastSignedTransaction(any(), eq(result.transaction()));
        assertEquals(1, queueCount("q_wallet_withdraw_event"));
        assertEquals(0, queueCount("q_wallet_sign_done"));
        settlement.settleConfirmed("BTC", transaction.id(), result.transaction().txId());
        assertConfirmed(transaction, result.transaction().txId());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void originalWithdrawalCanConfirmDuringReplacementSigningOrAfterBroadcast(boolean broadcastReplacement) {
        var transaction = prepare("BTC", false);
        var original = sign(transaction);
        done.broadcast();
        var replacement = rbf.bumpFee(transaction.id(), TENANT, original.transaction().txId());
        assertNotNull(replacement);
        assertEquals("SIGNING", orderStatus("BTC"));
        if (broadcastReplacement) {
            var result = sign(current(transaction));
            assertTrue(result.successful(), result.error());
            assertNotEquals(original.transaction().txId(), result.transaction().txId());
            done.broadcast();
            assertEquals("SENT", orderStatus("BTC"));
        }
        settlement.settleConfirmed("BTC", transaction.id(), original.transaction().txId());
        settlement.settleConfirmed("BTC", transaction.id(), original.transaction().txId());
        assertConfirmed(transaction, original.transaction().txId());
        if (!broadcastReplacement) {
            var obsolete = new UtxoSigningTask(transaction.id(), replacement.request());
            broadcasts.apply(UtxoSigningResult.failure(obsolete, "obsolete failure"));
            assertConfirmed(transaction, original.transaction().txId());
        }
    }

    @Test void generatedIdentityColumnsMatchTheIntentAndRejectIndependentWrites() {
        var transaction = prepare("BTC", false);
        var row = jdbc.queryForMap("select chain, tenant_id, signing_request_id, business_type from chain_signing_transaction where id = ?", transaction.id());
        assertEquals("BTC", row.get("chain"));
        assertEquals(TENANT, row.get("tenant_id"));
        assertEquals(transaction.request().requestId(), row.get("signing_request_id"));
        assertEquals("WITHDRAWAL", row.get("business_type"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update chain_signing_transaction set tenant_id = ? where id = ?", UUID.randomUUID(), transaction.id()));
        var request = transaction.request();
        var foreign = new UtxoSigningRequest(UUID.randomUUID(), UUID.randomUUID(), request.asset(), request.operation(),
                null, request.inputs(), request.payments(), request.changeAddress(), request.feeRate(), request.dustThreshold());
        assertThrows(DataAccessException.class, () -> signing.create(foreign, "FOREIGN"));
    }

    private UtxoSigningTransaction prepare(String chain, boolean dustOutput) {
        int coinType = switch (chain) { case "BTC" -> 0; case "LTC" -> 2; case "DOGE" -> 3; default -> 145; };
        var asset = new UtxoSigningRequest.Asset(chain, BitcoinLikeNetwork.REGTEST, 1, 8, coinType);
        String recipient = LegacyAddress.fromKey(asset.network().parameters(chain), ECKey.fromPrivate(BigInteger.valueOf(5))).toBase58();
        var input = new BigDecimal(chain.equals("DOGE") ? "10" : "1");
        var amount = new BigDecimal(dustOutput ? "0.00000001" : chain.equals("DOGE") ? "5" : "0.005");
        var fee = new BigDecimal("0.001");
        var request = new UtxoSigningRequest(TENANT, UUID.randomUUID(), asset, UtxoSigningRequest.Operation.WITHDRAWAL,
                null, List.of(new UtxoSigningRequest.Input(INPUT, 0, "source", input, new UtxoSigningRequest.Key(100, 0, 0, null))),
                List.of(new UtxoSigningRequest.Payment("WD-PIPELINE", 100, recipient, amount, fee)), recipient,
                chain.equals("DOGE") ? DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE : 10, 546);
        assertEquals(1, orders.createForTenant(TENANT, "WD-PIPELINE", 100, chain, chain, "source", account(chain), recipient, amount, fee));
        assertEquals(1, orders.updateStatus(TENANT, chain, "WD-PIPELINE", "SIGNING", "source", null, null));
        jdbc.update("insert into ledger_balance(tenant_id, chain, asset_symbol, account_id, available_balance, total_balance) values (?, ?, ?, ?, ?, ?)",
                TENANT, chain, chain, account(chain), input, input);
        assertTrue(balances.freeze(chain, chain, account(chain), amount.add(fee), TENANT));
        var transaction = signing.create(request, "WD-PIPELINE");
        utxos.upsert(chain, chain, INPUT, 0, "source", input, 1, "block", 6, true);
        assertEquals(1, utxos.lock(chain, INPUT, 0, Integer.toString(transaction.id())));
        return transaction;
    }

    private UtxoSigningResult sign(UtxoSigningTransaction transaction) {
        submissions.enqueue(TENANT, transaction.request().asset().chain(), transaction.id(), transaction.request().requestId());
        first.process();
        second.process();
        assertEquals(0, queueCount("q_wallet_sign_first"));
        assertEquals(0, queueCount("q_wallet_sign_second"));
        assertEquals(1, queueCount("q_wallet_sign_done"));
        return json.readValue(jdbc.queryForObject("select message::text from pgmq.q_wallet_sign_done", String.class), UtxoSigningResult.class);
    }

    private void assertConfirmed(UtxoSigningTransaction transaction, String hash) {
        var chain = transaction.request().asset().chain();
        assertEquals(Constants.CONFIRM, current(transaction).status());
        assertEquals(hash, current(transaction).txId());
        var order = orders.find(chain, "WD-PIPELINE", TENANT).orElseThrow();
        assertEquals("CONFIRMED", order.getStatus());
        assertEquals(hash, order.getTxHash());
        assertEquals("SPENT", inputStatus(chain));
        var balance = balances.find(TENANT, chain, chain, account(chain)).orElseThrow();
        assertEquals(0, balance.getLockedBalance().signum());
        var payment = transaction.request().payments().getFirst();
        assertEquals(0, transaction.request().inputAmount().subtract(payment.amount()).subtract(payment.customerFee())
                .compareTo(balance.getTotalBalance()));
    }
    private UtxoSigningTransaction current(UtxoSigningTransaction transaction) {
        return signing.findById(transaction.request().asset().chain(), transaction.id()).orElseThrow();
    }
    private String orderStatus(String chain) { return orders.find(chain, "WD-PIPELINE", TENANT).orElseThrow().getStatus(); }
    private String inputStatus(String chain) { return jdbc.queryForObject("select state from utxo_record where chain = ? and tx_hash = ?", String.class, chain, INPUT); }
    private int queueCount(String table) { return jdbc.queryForObject("select count(*) from pgmq." + table, Integer.class); }
    private static String account(String chain) { return "pipeline-" + chain; }
    private static byte[] bytes(int fill) { var bytes = new byte[32]; Arrays.fill(bytes, (byte) fill); return bytes; }
    private static String seed(int fill) { return Base64.getEncoder().encodeToString(bytes(fill)); }
}
