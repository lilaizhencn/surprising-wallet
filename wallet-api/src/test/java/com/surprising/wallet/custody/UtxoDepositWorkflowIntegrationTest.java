package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.model.UtxoBlock;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.queue.*;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class UtxoDepositWorkflowIntegrationTest {
    private static final long HEIGHT = 100_001;
    private static final String HASH = "a".repeat(64);
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private AccountChainProfile profile;
    private UUID tenant;
    private UUID custodyAddress;
    private long subject;
    private String account;
    private String address;
    private PgmqClient queue;
    private UtxoDepositService posting;
    private BitcoinLikeChainRuntime runtime;
    private WalletRuntimeConfigService switches;
    private BitcoinLikeDepositScanService scanner;

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.queryForList("select pgmq.create(?)", WalletQueue.DEPOSIT_EVENT.queueName());
        jdbc.update("delete from pgmq.q_wallet_deposit_event");
        jdbc.update("update chain_profile set deposit_confirmations = 2, scan_enabled = true, scan_start_height = ? where chain = 'BTC' and enabled", HEIGHT);
        var profiles = new ChainProfileRepository(jdbc);
        profile = profiles.findAccountByChain("BTC").orElseThrow();
        switches = new WalletRuntimeConfigService(profiles, new WalletSystemConfigRepository(jdbc));
        tenant = UUID.randomUUID();
        int namespace = jdbc.queryForObject("""
                insert into custody_tenant(id, slug, name) values (?, ?, 'UTXO workflow test')
                returning derivation_namespace
                """, Integer.class, tenant, "utxo-" + tenant);
        subject = Integer.toUnsignedLong(new CustodyDerivationSubjectRepository(jdbc).resolve(tenant, "customer"));
        account = Long.toString(subject);
        address = "btc-test-" + tenant;
        long chainAddress = jdbc.queryForObject("""
                insert into chain_address(tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                    address, derivation_path, wallet_role, enabled)
                values (?, 'BTC', 'BTC', ?, ?, ?, 0, ?, 'test-path', 'DEPOSIT', true) returning id
                """, Long.class, tenant, account, subject, namespace, address);
        custodyAddress = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, status, derivation_subject, derivation_child)
                values (?, ?, ?, 'BTC', ?, ?, 'customer', 'API', 'ACTIVE', ?, 0)
                """, custodyAddress, tenant, chainAddress, profile.getNetwork(), address, subject);
        UUID administrator = UUID.randomUUID();
        jdbc.update("""
                insert into custody_tenant_user(id, tenant_id, email, display_name, password_hash, role, status)
                values (?, ?, ?, 'UTXO administrator', 'test-only-hash', 'TENANT_ADMIN', 'ACTIVE')
                """, administrator, tenant, administrator + "@example.test");
        new CustodyTenantChainTableRepository(jdbc).setStatus(tenant, "BTC", "ACTIVE", administrator);
        jdbc.update("""
                insert into custody_webhook_endpoint(id, tenant_id, name, url, secret_ciphertext, status, verified_at)
                values (?, ?, 'test', 'https://example.test/hook', 'test-cipher', 'ACTIVE', now())
                """, UUID.randomUUID(), tenant);
        var mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        context = DepositTestSupport.context(jdbc, CustodyTestSupport.events(jdbc), mapper);
        queue = spy(new PgmqClient(jdbc));
        context.registerBean(UtxoDepositService.class, () -> new UtxoDepositService(
                new ChainAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc), new CustodyAddressRepository(jdbc),
                new UtxoRepository(jdbc), new ChainScanBlockRepository(jdbc), new ChainScanHeightRepository(jdbc),
                context.getBean(DepositService.class), switches, queue, mapper));
        context.registerBean(QueuedUtxoWithdrawalService.class, () -> new QueuedUtxoWithdrawalService(
                profiles, new WithdrawalOrderRepository(jdbc), new LedgerBalanceRepository(jdbc), switches));
        posting = context.getBean(UtxoDepositService.class);
        assertTrue(AopUtils.isAopProxy(posting));
        runtime = mock(BitcoinLikeChainRuntime.class);
        when(runtime.bestHeight(ChainType.BTC)).thenReturn(HEIGHT);
        when(runtime.blockHash(ChainType.BTC, HEIGHT)).thenReturn(HASH);
        scanner = scanner(runtime);
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void blockPostingPreservesIntOutpointsAndCommitsEveryProjectionAndTenantNotification() {
        var output = output("b", 40_000, "1.23456789", HASH, 2);
        posting.scanBlock(profile, block(output));
        assertAmount("1.23456789", balance("available_balance"));
        assertEquals(1, count("utxo_record"));
        assertEquals(1, count("deposit_record"));
        assertEquals(1, count("custody_deposit"));
        assertEquals(1, count("custody_ledger_entry"));
        assertEquals(1, count("custody_event"));
        assertEquals(1, count("custody_webhook_delivery"));
        assertEquals(HEIGHT, checkpoint());
        var stored = new UtxoRepository(jdbc).listSpendable("BTC", "BTC", 2, 5, 0, profile.getRuntimeCurrencyId()).getFirst();
        assertEquals(40_000, stored.getSeq());
        var message = queue.read(WalletQueue.DEPOSIT_EVENT, 30, 10).getFirst();
        var mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        var event = mapper.readValue(message.body(), UtxoDepositNotification.class);
        assertEquals(tenant, QueueTenant.require(mapper, message));
        assertEquals(tenant, event.tenantId());
        assertEquals(profile.getNetwork(), event.network());
        assertEquals(output, event.output());
        posting.scanBlock(profile, block(output));
        assertAmount("1.23456789", balance("available_balance"));
        assertEquals(1, count("custody_event"));
    }

    @Test void secondNotificationFailureRollsBackTheWholeBlockIncludingTheFirstQueueMessageAndCursor() {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("queue unavailable");
            return call.callRealMethod();
        }).when(queue).send(eq(WalletQueue.DEPOSIT_EVENT), anyString(), anyString());
        var block = new UtxoBlock(HEIGHT, HASH, "parent", List.of(
                output("b", 40_000, "1", HASH, 2), output("c", 1, "2", HASH, 2)));
        assertThrows(IllegalStateException.class, () -> posting.scanBlock(profile, block));
        assertNoPosting();
        doCallRealMethod().when(queue).send(eq(WalletQueue.DEPOSIT_EVENT), anyString(), anyString());
        posting.scanBlock(profile, block);
        assertAmount("3", balance("available_balance"));
        assertEquals(2, count("custody_event"));
        assertEquals(2, queueCount());
    }

    @Test void independentConfirmationCreditsObservedDepositsEvenWhenEveryNewTaskIsDisabled() {
        var output = output("b", 40_000, "2", HASH, 1);
        posting.scanBlock(profile, block(output));
        assertEquals(0, count("ledger_balance"));
        jdbc.update("update wallet_system_config set config_value = 'false' where config_key like 'global.%'");
        jdbc.update("update chain_profile set scan_enabled = false, withdraw_enabled = false where chain = 'BTC'");
        scanner.scan("BTC");
        verifyNoInteractions(runtime);
        when(runtime.bestHeight(ChainType.BTC)).thenReturn(HEIGHT);
        when(runtime.blockHash(ChainType.BTC, HEIGHT)).thenReturn(HASH);
        when(runtime.confirmations(ChainType.BTC, output.txHash())).thenReturn(2);
        new BitcoinLikeConfirmationService(new ChainProfileRepository(jdbc), scanner, runtime,
                new ChainSigningTransactionRepository(jdbc, new tools.jackson.databind.ObjectMapper()),
                mock(BitcoinLikeSettlementService.class)).confirmAll();
        assertAmount("2", balance("available_balance"));
        assertEquals(1, count("custody_event"));
        assertEquals(2, queueCount());
        assertEquals(HEIGHT, checkpoint());
    }

    @Test void replacementBlockAndLateConfirmationCannotReviveOrCreditAnOrphanedOutput() {
        var original = output("b", 40_000, "5", HASH, 2);
        posting.scanBlock(profile, block(original));
        long id = jdbc.queryForObject("select id from utxo_record where tx_hash = ?", Long.class, original.txHash());
        var stale = new UtxoRepository.PendingOutput(id, original);
        new ChainScanHeightRepository(jdbc).upsert("BTC", UtxoDepositService.scannerName(profile), HEIGHT + 10, HEIGHT + 10);
        String replacement = "d".repeat(64);
        posting.reconcileBlock(profile, new UtxoBlock(HEIGHT, replacement, "parent",
                List.of(output("c", 0, "3", replacement, 2))));
        posting.refresh(profile, stale, 99);
        assertAmount("3", balance("available_balance"));
        assertEquals("ORPHANED", jdbc.queryForObject("select state from utxo_record where tx_hash = ?", String.class, original.txHash()));
        assertEquals("REORGED", jdbc.queryForObject("select canonical_status from deposit_record where tx_hash = ?", String.class, original.txHash()));
        assertEquals(HEIGHT + 10, checkpoint());
        assertEquals(2, queueCount());
        assertEquals(3, count("custody_event"));
    }

    @Test void activeCollectionDestinationIsStoredAsAnOutputWithoutCustomerCreditOrNotification() {
        jdbc.update("""
                insert into custody_gas_account(id, tenant_id, custody_address_id, chain, network, native_symbol)
                values (?, ?, ?, 'BTC', ?, 'BTC')
                """, UUID.randomUUID(), tenant, custodyAddress, profile.getNetwork());
        posting.scanBlock(profile, block(output("b", 0, "2", HASH, 2)));
        assertEquals(1, count("utxo_record"));
        assertEquals(0, count("deposit_record"));
        assertEquals(0, count("ledger_balance"));
        assertEquals(0, queueCount());
    }

    @Test void incompleteRpcBlockCannotPersistPartialOutputsOrAdvanceTheCheckpoint() {
        var command = mock(com.surprising.wallet.chain.rpc.BtcCommand.class);
        var header = new com.surprising.wallet.sdk.bitcoinj.rpc.model.BtcLikeBlock();
        header.setTx(List.of("first", "missing"));
        header.setPreviousblockhash("parent");
        when(command.getBlockHash(HEIGHT)).thenReturn(HASH);
        when(command.getBlock(HASH)).thenReturn(header);
        when(command.getBlockCount()).thenReturn(HEIGHT);
        var raw = new com.surprising.wallet.sdk.bitcoinj.rpc.model.BtcLikeRawTransaction();
        var output = new com.surprising.wallet.sdk.bitcoinj.rpc.model.TxOutput();
        output.setN(40_000);
        output.setValue(BigDecimal.ONE);
        var script = new com.surprising.wallet.sdk.bitcoinj.rpc.model.ScriptPubKey();
        script.setAddress(address);
        output.setScriptPubKey(script);
        raw.setVout(List.of(output));
        when(command.getRawTransaction("first", true)).thenReturn(raw);
        var realRuntime = new BitcoinLikeChainRuntime(DepositTestSupport.repository(jdbc),
                mock(com.surprising.wallet.config.PubKeyConfig.class), command,
                mock(com.surprising.wallet.chain.ltc.LitecoinEsploraCommand.class),
                mock(com.surprising.wallet.chain.rpc.DogeCommand.class), mock(com.surprising.wallet.chain.rpc.BchCommand.class),
                new ChainAddressRepository(jdbc));
        assertThrows(IllegalStateException.class, () -> scanner(realRuntime).scan("BTC"));
        assertNoPosting();
    }

    @Test void queuedWithdrawalFreezesOnceAndRejectsAnotherTenantsGlobalReference() {
        jdbc.update("update wallet_system_config set config_value = 'true' where config_key in ('global.all.enabled', 'global.withdraw.enabled')");
        jdbc.update("update chain_profile set withdraw_enabled = true, transfer_enabled = true where chain = 'BTC' and enabled");
        posting.scanBlock(profile, block(output("b", 0, "2", HASH, 2)));
        var request = WithdrawRecord.builder().withdrawId("UTXO-QUEUED").userId(subject)
                .currency(profile.getRuntimeCurrencyId()).address("test-destination")
                .balance(BigDecimal.ONE).fee(new BigDecimal("0.1")).build();
        var service = context.getBean(QueuedUtxoWithdrawalService.class);
        assertTrue(service.accept(request, tenant));
        assertTrue(service.accept(request, tenant));
        assertAmount("0.9", balance("available_balance"));
        assertAmount("1.1", balance("locked_balance"));
        UUID foreign = UUID.randomUUID();
        jdbc.update("insert into custody_tenant(id, slug, name) values (?, ?, 'foreign')", foreign, "foreign-" + foreign);
        assertThrows(IllegalStateException.class, () -> service.accept(request, foreign));
        assertEquals(1, count("withdrawal_order"));
        assertAmount("1.1", balance("locked_balance"));
    }

    @Test void alreadyObservedDepositKeepsItsProjectionWhenTheTenantClosesTheChain() {
        var output = output("b", 0, "2", HASH, 1);
        posting.scanBlock(profile, block(output));
        jdbc.update("""
                update custody_tenant_chain set status = 'CLOSED', closed_by = opened_by, closed_at = now()
                 where tenant_id = ? and chain = 'BTC'
                """, tenant);
        long id = jdbc.queryForObject("select id from utxo_record where tx_hash = ?", Long.class, output.txHash());
        posting.refresh(profile, new UtxoRepository.PendingOutput(id, output), 2);
        assertAmount("2", balance("available_balance"));
        assertEquals(1, count("custody_deposit"));
        assertEquals(1, count("custody_ledger_entry"));
        assertEquals(1, count("custody_event"));
        assertEquals(1, count("custody_webhook_delivery"));
    }

    @Test void missingCustodyOwnerRollsBackTheWholeBlockInsteadOfLeavingAnUnprojectedCredit() {
        jdbc.update("delete from custody_address where id = ?", custodyAddress);
        assertThrows(IllegalStateException.class, () -> posting.scanBlock(profile, block(output("b", 0, "2", HASH, 2))));
        assertNoPosting();
    }

    private BitcoinLikeDepositScanService scanner(BitcoinLikeChainRuntime rpc) {
        return new BitcoinLikeDepositScanService(rpc, new ChainProfileRepository(jdbc),
                new ChainScanHeightRepository(jdbc), new ChainScanBlockRepository(jdbc), new UtxoRepository(jdbc),
                posting, switches);
    }
    private UtxoOutput output(String hash, int vout, String amount, String block, int confirmations) {
        return new UtxoOutput(hash.repeat(64), vout, address, new BigDecimal(amount), HEIGHT, block, confirmations);
    }
    private UtxoBlock block(UtxoOutput output) { return new UtxoBlock(HEIGHT, HASH, "parent", List.of(output)); }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private long checkpoint() {
        return new ChainScanHeightRepository(jdbc).findSafeHeight("BTC", UtxoDepositService.scannerName(profile)).orElseThrow();
    }
    private BigDecimal balance(String column) {
        return jdbc.queryForObject("select " + column + " from ledger_balance where tenant_id = ? and chain = 'BTC' and account_id = ?",
                BigDecimal.class, tenant, account);
    }
    private int queueCount() { return jdbc.queryForObject("select count(*) from pgmq.q_wallet_deposit_event", Integer.class); }
    private void assertNoPosting() {
        for (String table : List.of("utxo_record", "deposit_record", "ledger_balance", "custody_deposit",
                "custody_ledger_entry", "custody_event", "custody_webhook_delivery", "chain_scan_block", "chain_scan_height"))
            assertEquals(0, count(table), table);
        assertEquals(0, queueCount());
    }
    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
