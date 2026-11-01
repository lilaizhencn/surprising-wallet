package com.surprising.wallet.custody;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.polkadot.*;
import com.surprising.wallet.chain.polkadot.MpcPolkadotTransactionCodec.Role;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.PolkadotSubmissionService.*;
import com.surprising.wallet.testutil.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG18 rollback/concurrency; test-only signature substitute and synthetic chain receipts. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class PolkadotSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="DOT-MPC-TEST",ACCOUNT="dot-mpc-principal",GENESIS="0x"+"11".repeat(32);
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;private JdbcTemplate jdbc;private MpcSigningTestSupport fixture;
    private PolkadotSubmissionService service;private WithdrawalOrderRepository orders;private PolkadotTransactionRepository transactions;
    private ChainAddressRecord source;private final String recipient=MpcPolkadotTransactionCodec.address(HexFormat.of().parseHex("ab".repeat(32)),42);
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup()throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='DOT'");jdbc.update("update chain_profile set enabled=true,chain_id=42 where chain='DOT' and network='westend'");
        jdbc.update("update chain_asset set decimals=12,active=true where chain='DOT' and symbol='DOT'");
        jdbc.update("update token_config set enabled=true,collect_enabled=true,contract_address='10002' where chain='DOT' and network='westend' and symbol='USDC'");
        fixture=new MpcSigningTestSupport(jdbc);source=fixture.address(jdbc,TENANT,ChainType.DOT,42,1,"DEPOSIT");
        orders=new WithdrawalOrderRepository(jdbc);transactions=new PolkadotTransactionRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(PolkadotTransactionRepository.class,()->transactions);
        context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));context.registerBean(WalletMpcSignRequestRepository.class,()->new WalletMpcSignRequestRepository(jdbc));
        context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(ChainAssetRepository.class,()->new ChainAssetRepository(jdbc));context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));context.registerBean(CustodyGasAccountingService.class,()->CustodyTestSupport.gasAccounting(jdbc));
        context.registerBean(WithdrawalSettlementService.class);context.registerBean(ObjectMapper.class,()->json);context.registerBean(PolkadotSubmissionService.class);context.refresh();service=context.getBean(PolkadotSubmissionService.class);
        createOrder(REFERENCE,"DOT");
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private Operation op(){return op(REFERENCE);}
    private Operation op(String reference){return new Operation(TENANT,reference,Kind.WITHDRAWAL);}
    private void createOrder(String reference,String symbol) {
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'DOT',?,?,5,5) on conflict(chain,asset_symbol,account_id) do nothing",TENANT,symbol,ACCOUNT);
        orders.createForTenant(TENANT,reference,42,"DOT",symbol,source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("DOT",symbol,ACCOUNT,new BigDecimal("1.1"),TENANT));orders.updateStatus(TENANT,"DOT",reference,"FROZEN",source.getAddress(),null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"DOT",reference,source.getAddress()));
    }
    private PolkadotSigningPayload draft(String symbol) {
        int decimals=symbol.equals("DOT")?12:6;Role role=Role.ASSET_HUB;
        return new PolkadotSigningPayload(symbol,BigDecimal.ONE,new BigDecimal("0.1"),decimals,12,BigInteger.TEN.pow(10),BigInteger.TEN.pow(9),
                new MpcPolkadotTransactionCodec.Unsigned("westend",role,GENESIS,1,1,source.getAddress(),service.publicKey(source),recipient,BigInteger.TEN.pow(decimals),symbol.equals("DOT")?null:"10002",7,64,100,GENESIS,
                        List.of("CheckSpecVersion","CheckTxVersion","CheckGenesis","CheckMortality","CheckNonce","ChargeTransactionPayment"),new MpcPolkadotTransactionCodec.CallIndices("0x0403","0x0400"),true));
    }
    private MpcPolkadotTransactionCodec.Signed prepared(Operation op,String symbol){var request=service.reserve(op,source,draft(symbol));service.sign(op,request.id());return service.commit(op,request.id());}
    private JsonNode receipt(Operation op,MpcPolkadotTransactionCodec.Signed signed,boolean paid){return PolkadotReceiptTestSupport.payment(service.payload(service.intent(op).orElseThrow()).unsigned(),signed,paid,1_000_000_000);}
    @Test void nonceIntentAndAdmissionCommitBeforeMpcThenWireBindsAtomically() {
        var request=service.reserve(op(),source,draft("DOT"));var row=transactions.find(TENANT,request.id()).orElseThrow();assertEquals("SIGNING",row.status());assertNull(row.txHash());assertNull(row.rawPayload());
        service.sign(op(),request.id());var signed=service.commit(op(),request.id());assertEquals(signed.txHash(),orders.findTxHash("DOT",REFERENCE,TENANT).orElseThrow());
        assertEquals(signed.extrinsicHex(),transactions.find(TENANT,request.id()).orElseThrow().rawPayload());assertEquals(request.id(),service.reserve(op(),source,null).id());assertEquals(signed,service.commit(op(),request.id()));assertEquals(0,service.releaseUnsigned(op()));
    }
    @Test void chainAdmissionFailureRollsBackImmutableRequestAndNonce() {
        jdbc.execute("create function public.dot_test_reject() returns trigger language plpgsql as $$ begin raise exception 'reject admission';end $$;create trigger dot_test_reject before insert on polkadot_transaction for each row execute function public.dot_test_reject()");
        try{assertThrows(RuntimeException.class,()->service.reserve(op(),source,draft("DOT")));assertTrue(service.intent(op()).isEmpty());assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='DOT'",Integer.class));}
        finally{jdbc.execute("drop trigger dot_test_reject on polkadot_transaction;drop function public.dot_test_reject()");}
    }
    @Test void businessBindingFailurePreservesCompletedSignatureButRollsBackWire() {
        var request=service.reserve(op(),source,draft("DOT"));service.sign(op(),request.id());
        jdbc.execute("create function public.dot_test_reject() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject binding';end if;return new;end $$;create trigger dot_test_reject before update on withdrawal_order for each row execute function public.dot_test_reject()");
        try{assertThrows(RuntimeException.class,()->service.commit(op(),request.id()));assertNull(transactions.find(TENANT,request.id()).orElseThrow().rawPayload());assertEquals(MpcSignRequest.State.COMPLETED,service.intent(op()).orElseThrow().state());}
        finally{jdbc.execute("drop trigger dot_test_reject on withdrawal_order;drop function public.dot_test_reject()");}
        assertNotNull(service.commit(op(),request.id()));
    }
    @Test void nativeAndTokenPaymentsShareTheAssetHubNonceAdmission() {
        var nativeRequest=service.reserve(op(),source,draft("DOT"));createOrder("HUB","USDC");
        assertEquals(Role.ASSET_HUB,service.payload(nativeRequest).unsigned().role());
        assertThrows(IllegalStateException.class,()->service.reserve(op("HUB"),source,draft("USDC")));
        assertEquals(1,jdbc.queryForObject("select count(*) from account_sequence where chain='DOT'",Integer.class));
    }
    @Test void concurrentSameRolePaymentsAdmitOnlyOne()throws Exception {
        createOrder("OTHER","DOT");try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {var a=executor.submit(()->reserve(op()));var b=executor.submit(()->reserve(op("OTHER")));assertEquals(1,a.get(20,TimeUnit.SECONDS)+b.get(20,TimeUnit.SECONDS));}
        assertEquals(1,jdbc.queryForObject("select count(*) from polkadot_transaction",Integer.class));
    }
    private int reserve(Operation op){try{service.reserve(op,source,draft("DOT"));return 1;}catch(IllegalStateException expected){return 0;}}
    @Test void exactFeeAndPrincipalSettleOnceAfterCachedFinalizedDepth() {
        var signed=prepared(op(),"DOT");service.restoreSent(op(),signed.txHash());assertFalse(service.settle(op(),receipt(op(),signed,true),123,2));assertTrue(service.proof(op()).isPresent());
        assertTrue(service.settle(op(),null,124,2));assertFalse(service.settle(op(),null,124,2));service.observed(op(),signed.txHash());
        assertEquals("CONFIRMED",transactions.find(TENANT,service.intent(op()).orElseThrow().id()).orElseThrow().status());
        assertEquals(0,new BigDecimal("0.001").compareTo(transactions.findConfirmedFee(signed.txHash()).orElseThrow()));
        assertEquals(0,new BigDecimal("3.9").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"DOT","DOT",ACCOUNT).orElseThrow().getTotalBalance()));
    }
    @Test void finalizedDispatchFailureRefundsPrincipalAndRetainsActualFee() {
        var signed=prepared(op(),"DOT");service.restoreSent(op(),signed.txHash());assertTrue(service.settle(op(),receipt(op(),signed,false),123,1));assertFalse(service.settle(op(),null,123,1));
        assertEquals("FAILED",orders.find("DOT",REFERENCE,TENANT).orElseThrow().getStatus());assertEquals(0,new BigDecimal("5").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"DOT","DOT",ACCOUNT).orElseThrow().getTotalBalance()));
        assertEquals(0,new BigDecimal("0.001").compareTo(transactions.findConfirmedFee(signed.txHash()).orElseThrow()));
    }
    @Test void assetAccountMayEmptyWhileTheNativeAccountRetainsItsNonce() {
        createOrder("FULL-ASSET","USDC");var p=draft("USDC");var u=p.unsigned();
        var transfer=new MpcPolkadotTransactionCodec.Unsigned(u.network(),u.role(),u.genesisHash(),u.specVersion(),u.transactionVersion(),u.sender(),u.publicKeyHex(),u.recipient(),u.amountAtomic(),u.assetId(),u.nonce(),u.eraPeriod(),u.blockHeight(),u.blockHash(),u.extensions(),u.callIndices(),false);
        var request=service.reserve(op("FULL-ASSET"),source,new PolkadotSigningPayload(p.symbol(),p.amount(),p.customerFee(),p.decimals(),p.nativeDecimals(),p.feeBudget(),p.existentialDeposit(),transfer));
        assertFalse(service.payload(request).unsigned().keepAlive(),"Asset-account preservation must not prevent emptying the asset; the native ED reserve preserves the system nonce");
    }
    @Test void unsafeAllowDeathAndWrongPrincipalAreRejectedBeforeAdmission() {
        var p=draft("DOT");var u=p.unsigned();var death=new MpcPolkadotTransactionCodec.Unsigned(u.network(),u.role(),u.genesisHash(),u.specVersion(),u.transactionVersion(),u.sender(),u.publicKeyHex(),u.recipient(),u.amountAtomic(),u.assetId(),u.nonce(),u.eraPeriod(),u.blockHeight(),u.blockHash(),u.extensions(),u.callIndices(),false);
        assertThrows(RuntimeException.class,()->service.reserve(op(),source,new PolkadotSigningPayload(p.symbol(),p.amount(),p.customerFee(),p.decimals(),p.nativeDecimals(),p.feeBudget(),p.existentialDeposit(),death)));
        assertThrows(RuntimeException.class,()->service.reserve(op(),source,new PolkadotSigningPayload(p.symbol(),BigDecimal.TEN,p.customerFee(),p.decimals(),p.nativeDecimals(),p.feeBudget(),p.existentialDeposit(),u)));assertTrue(service.intent(op()).isEmpty());
    }
}
