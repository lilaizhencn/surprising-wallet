package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.CardanoSigningPayload;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.CardanoSubmissionService.*;
import com.surprising.wallet.testutil.CardanoReceiptTestSupport;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.testutil.MpcSigningTestSupport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG18 policy/rollback tests with a test-only local signature substitute. Native probes are separate. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CardanoSubmissionIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE = "ADA-MPC-TEST", ACCOUNT = "cardano-mpc-principal";
    private final ObjectMapper json = new ObjectMapper();
    private final String recipient = MpcCardanoTransactionCodec.address(HexFormat.of().parseHex("cd".repeat(32)), false);
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private ChainAddressRecord source;
    private CardanoSubmissionService service;
    private CardanoTransactionRepository transactions;
    private WithdrawalOrderRepository orders;
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource(); CustodyIntegrationDatabase.reset(ds); jdbc = new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='ADA'");
        jdbc.update("update chain_profile set enabled=true,withdraw_confirmations=3 where chain='ADA' and network='preprod'");
        jdbc.update("update chain_asset set active=true,decimals=6 where chain='ADA' and symbol='ADA'");
        jdbc.update("update token_config set enabled=true,collect_enabled=true,contract_address=? where chain='ADA' and network='preprod' and symbol='USDC'", CardanoReceiptTestSupport.USDC);
        fixture = new MpcSigningTestSupport(jdbc); source = fixture.address(jdbc, TENANT, ChainType.ADA, 42, 1, "DEPOSIT");
        var heights = new ChainScanHeightRepository(jdbc);
        heights.initializeNative("ADA", CardanoSubmissionService.SCANNER, CardanoReceiptTestSupport.GENESIS, CardanoReceiptTestSupport.POINT.height());
        new ChainScanBlockRepository(jdbc).insertNative("ADA", CardanoSubmissionService.SCANNER, CardanoReceiptTestSupport.POINT, null);
        orders = new WithdrawalOrderRepository(jdbc); transactions = new CardanoTransactionRepository(jdbc);
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class, () -> orders);
        context.registerBean(CardanoTransactionRepository.class, () -> transactions);
        context.registerBean(WalletMpcSignRequestRepository.class, () -> new WalletMpcSignRequestRepository(jdbc));
        context.registerBean(CollectionRecordRepository.class, () -> new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class, () -> new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class, () -> new ChainAddressRepository(jdbc));
        context.registerBean(ChainAssetRepository.class, () -> new ChainAssetRepository(jdbc));
        context.registerBean(TokenConfigRepository.class, () -> new TokenConfigRepository(jdbc));
        context.registerBean(ChainScanHeightRepository.class, () -> heights);
        context.registerBean(MpcSigningService.class, () -> fixture.signing); context.registerBean(MpcNodes.class, () -> fixture.nodes);
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(LedgerBalanceRepository.class, () -> new LedgerBalanceRepository(jdbc));
        context.registerBean(CustodyGasAccountingService.class, () -> CustodyTestSupport.gasAccounting(jdbc));
        context.registerBean(WithdrawalSettlementService.class); context.registerBean(CardanoSubmissionService.class); context.refresh();
        service = context.getBean(CardanoSubmissionService.class);
        createOrder(REFERENCE, "ADA");
    }
    @AfterEach void close() { if (context != null) context.close(); }
    private Operation op() { return new Operation(TENANT, REFERENCE, Kind.WITHDRAWAL); }
    private void createOrder(String reference, String symbol) {
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'ADA',?,?,5,5) on conflict(chain,asset_symbol,account_id) do nothing", TENANT, symbol, ACCOUNT);
        orders.createForTenant(TENANT, reference, 42, "ADA", symbol, source.getAddress(), ACCOUNT, recipient, BigDecimal.ONE, new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("ADA", symbol, ACCOUNT, new BigDecimal("1.1"), TENANT));
        orders.updateStatus(TENANT, "ADA", reference, "FROZEN", source.getAddress(), null, null);
        assertEquals(1, orders.claimSigningDurable(TENANT, "ADA", reference, source.getAddress()));
    }
    private CardanoSigningPayload draft(String symbol) {
        return CardanoReceiptTestSupport.draft(service.publicKey(source), source.getAddress(), recipient, symbol, BigDecimal.ONE, new BigDecimal("0.1"));
    }
    private MpcCardanoTransactionCodec.Signed prepared() {
        var request = service.reserve(op(), source, draft("ADA")); service.sign(op(), request.id()); return service.commit(op(), request.id());
    }

    @Test void bodyHashAdmissionPrecedesRemoteSignatureAndExactWireBindsAtomically() {
        var request = service.reserve(op(), source, draft("ADA"));
        var row = transactions.find(TENANT, request.id()).orElseThrow();
        assertEquals("SIGNING", row.status()); assertNotNull(row.txHash()); assertNull(row.rawPayload());
        fixture.beforeSignature = () -> assertEquals(row.txHash(), transactions.find(TENANT, request.id()).orElseThrow().txHash());
        service.sign(op(), request.id()); var signed = service.commit(op(), request.id());
        assertEquals(signed.txHash(), orders.findTxHash("ADA", REFERENCE, TENANT).orElseThrow());
        assertEquals(signed.transactionHex(), transactions.find(TENANT, request.id()).orElseThrow().rawPayload());
        assertEquals(request.id(), service.reserve(op(), source, null).id()); assertEquals(signed, service.commit(op(), request.id()));
        assertEquals(0, service.releaseUnsigned(op()));
    }

    @Test void failedBusinessHashBindingRollsBackWireButPreservesCompletedMpcResult() {
        var request = service.reserve(op(), source, draft("ADA")); service.sign(op(), request.id());
        jdbc.execute("create function public.cardano_test_reject() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject binding';end if;return new;end $$;create trigger cardano_test_reject before update on withdrawal_order for each row execute function public.cardano_test_reject()");
        try {
            assertThrows(RuntimeException.class, () -> service.commit(op(), request.id()));
            assertNull(transactions.find(TENANT, request.id()).orElseThrow().rawPayload());
            assertEquals(MpcSignRequest.State.COMPLETED, service.intent(op()).orElseThrow().state());
        } finally { jdbc.execute("drop trigger cardano_test_reject on withdrawal_order;drop function public.cardano_test_reject()"); }
        assertNotNull(service.commit(op(), request.id()));
    }

    @Test void nativeAndTokenRequestsCannotShareAnUnresolvedInputSnapshot() {
        service.reserve(op(), source, draft("ADA")); createOrder("ADA-TOKEN", "USDC");
        var token = new Operation(TENANT, "ADA-TOKEN", Kind.WITHDRAWAL);
        assertThrows(IllegalStateException.class, () -> service.reserve(token, source, draft("USDC")));
        assertTrue(service.intent(token).isEmpty());
        service.unknown(token, "admission failed before creating an immutable intent");
        assertEquals(1, service.releaseUnsigned(token));
    }

    @Test void failedAdmissionRollsBackIntentAndLeavesUnsignedBusinessRecoverable() {
        jdbc.execute("create function public.cardano_test_reject() returns trigger language plpgsql as $$ begin raise exception 'reject admission';end $$;create trigger cardano_test_reject before insert on cardano_transaction for each row execute function public.cardano_test_reject()");
        try { assertThrows(RuntimeException.class, () -> service.reserve(op(), source, draft("ADA"))); assertTrue(service.intent(op()).isEmpty()); }
        finally { jdbc.execute("drop trigger cardano_test_reject on cardano_transaction;drop function public.cardano_test_reject()"); }
        service.unknown(op(), "admission failed before creating an immutable intent");
        assertEquals(1, service.releaseUnsigned(op()));
    }

    @Test void changedPrincipalOrWeakenedConfirmationPolicyCannotCreateAnIntent() {
        var p = draft("ADA");
        assertThrows(IllegalStateException.class, () -> service.reserve(op(), source, new CardanoSigningPayload(p.symbol(), p.amount().add(BigDecimal.ONE),
                p.customerFee(), p.decimals(), p.requiredConfirmations(), p.observed(), p.unsigned())));
        assertThrows(IllegalStateException.class, () -> service.reserve(op(), source, new CardanoSigningPayload(p.symbol(), p.amount(),
                p.customerFee(), p.decimals(), 1, p.observed(), p.unsigned())));
        assertTrue(service.intent(op()).isEmpty());
    }

    @Test void lostResponseRestoresOriginalWireAndAcceptedPolicyAfterConfigurationChanges() {
        var signed = prepared(); service.unknown(op(), "lost native RPC response");
        jdbc.update("update token_config set enabled=false where chain='ADA'");
        jdbc.update("update chain_profile set withdraw_confirmations=99 where chain='ADA'");
        service.restoreSent(op(), signed.txHash());
        assertEquals("SENT", orders.find("ADA", REFERENCE, TENANT).orElseThrow().getStatus());
        assertEquals(3, service.payload(service.intent(op()).orElseThrow()).requiredConfirmations());
        assertEquals(signed, service.commit(op(), service.intent(op()).orElseThrow().id()));
        assertEquals(signed.transactionHex(), service.stored(op()).orElseThrow().rawPayload());
    }

    @Test void staleSnapshotAfterAnEarlierSettlementCannotReserveSpentInputs() {
        var signed = prepared(); var request = service.intent(op()).orElseThrow();
        assertEquals(1, transactions.recordProof(TENANT, request.id(), "{\"test\":true}", BigInteger.ZERO, BigInteger.ZERO, 101, "FAILED"));
        createOrder("ADA-AFTER", "ADA"); var next = new Operation(TENANT, "ADA-AFTER", Kind.WITHDRAWAL);
        assertThrows(IllegalStateException.class, () -> service.reserve(next, source, draft("ADA")));
        assertTrue(service.intent(next).isEmpty()); assertEquals(signed.txHash(), transactions.find(TENANT, request.id()).orElseThrow().txHash());
    }

    @Test void unrelatedExpiryProofCannotRefundFrozenFunds() throws Exception {
        var signed = prepared(); service.restoreSent(op(), signed.txHash());
        try (var resource = getClass().getResourceAsStream("/mpc/cardano-expired-unspent-reference.json")) {
            assertNotNull(resource); var foreign = json.readTree(resource);
            var proof = json.createObjectNode().put("kind", "EXPIRED_UNSPENT").put("genesisHash", CardanoReceiptTestSupport.GENESIS)
                    .put("blockBase64", foreign.path("blockBase64").asText()).put("unspentBase64", foreign.path("unspentBase64").asText());
            assertThrows(RuntimeException.class, () -> service.settle(op(), proof, Long.MAX_VALUE));
        }
        assertFalse(service.terminal(op()));
        assertEquals("SENT", orders.find("ADA", REFERENCE, TENANT).orElseThrow().getStatus());
        assertEquals(0, new BigDecimal("1.1").compareTo(jdbc.queryForObject("select locked_balance from ledger_balance where tenant_id=? and chain='ADA' and asset_symbol='ADA' and account_id=?", BigDecimal.class, TENANT, ACCOUNT)));
    }
}
