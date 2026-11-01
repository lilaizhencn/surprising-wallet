package com.surprising.wallet.custody;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.ton.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual PG18 failure/concurrency boundaries; synthetic crypto/chain observations have separate native acceptance. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class TonSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="TON-MPC-TEST",ACCOUNT="ton-mpc-principal";
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private TonSubmissionService service;
    private WithdrawalOrderRepository orders;
    private TonTransactionRepository transactions;
    private ChainAddressRecord source;
    private String recipient="0:"+"ab".repeat(32);
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='TON'");
        jdbc.update("update chain_profile set enabled=true where chain='TON' and network='testnet'");
        fixture=new MpcSigningTestSupport(jdbc);source=fixture.address(jdbc,TENANT,ChainType.TON,42,1,"DEPOSIT");
        orders=new WithdrawalOrderRepository(jdbc);transactions=new TonTransactionRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(TonTransactionRepository.class,()->transactions);
        context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
        context.registerBean(WalletMpcSignRequestRepository.class,()->new WalletMpcSignRequestRepository(jdbc));
        context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));
        context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(WithdrawalSettlementService.class);context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(TonSubmissionService.class);context.refresh();service=context.getBean(TonSubmissionService.class);
        createOrder(REFERENCE,"TON");
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private TonSubmissionService.Operation op(){return op(REFERENCE);}
    private TonSubmissionService.Operation op(String reference){return new TonSubmissionService.Operation(TENANT,reference,TonSubmissionService.Kind.WITHDRAWAL);}
    private void createOrder(String reference,String symbol){
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'TON',?,?,5,5) on conflict(chain,asset_symbol,account_id) do nothing",TENANT,symbol,ACCOUNT);
        orders.createForTenant(TENANT,reference,42,"TON",symbol,source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("TON",symbol,ACCOUNT,new BigDecimal("1.1"),TENANT));
        orders.updateStatus(TENANT,"TON",reference,"FROZEN",source.getAddress(),null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"TON",reference,source.getAddress()));
    }
    private TonSigningPayload draft(TonSubmissionService.Operation op,String symbol){
        int decimals=9;String master=null,sourceWallet=null,targetWallet=null;
        if(!symbol.equals("TON")){
            var token=new TokenConfigRepository(jdbc).findDefinition("TON","testnet",symbol).orElseThrow();
            master=MpcTonTransactionCodec.canonicalAddress(token.getContractAddress());decimals=token.getDecimals();
            sourceWallet="0:"+"cd".repeat(32);targetWallet="0:"+"ef".repeat(32);
        }
        return new TonSigningPayload(symbol,BigDecimal.ONE,op.kind()==TonSubmissionService.Kind.COLLECTION?BigDecimal.ZERO:new BigDecimal("0.1"),decimals,source.getAddress(),
                new MpcTonTransactionCodec.Unsigned("testnet",source.getAddress(),recipient,BigDecimal.ONE.movePointRight(decimals).toBigIntegerExact(),master,sourceWallet,targetWallet,
                        7,2_000_000_000,master==null?0:1,op.reference(),service.publicKey(source)));
    }
    private MpcTonTransactionCodec.Signed prepared(TonSubmissionService.Operation op,String symbol){
        var request=service.reserve(op,source,draft(op,symbol));service.sign(op,request.id());return service.commit(op,request.id());
    }
    private TonExecutionReceipt.Trace receipt(TonSubmissionService.Operation op,MpcTonTransactionCodec.Signed signed,boolean skipped){
        return TonReceiptTestSupport.nativePayment(service.payload(service.intent(op).orElseThrow()).unsigned(),signed,skipped);
    }
    @Test void fixedSequenceBocAndHashCommitBeforeNetworkAndRetryCannotCreateAnotherPayment(){
        var signed=prepared(op(),"TON");var request=service.intent(op()).orElseThrow();
        assertEquals(signed.messageHashHex(),orders.find("TON",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(signed.bocBase64(),transactions.findSigned(signed.messageHashHex()).orElseThrow().getRawPayload());
        assertEquals("PREPARED",transactions.findSigned(signed.messageHashHex()).orElseThrow().getStatus());
        assertEquals(request.id(),service.reserve(op(),source,null).id());assertEquals(signed,service.commit(op(),request.id()));
        assertEquals(8L,jdbc.queryForObject("select next_sequence from account_sequence where chain='TON' and address=?",Long.class,MpcTonTransactionCodec.canonicalAddress(source.getAddress())));
        service.observed(op(),signed.messageHashHex());service.restoreSent(op(),signed.messageHashHex());
        assertEquals("SENT",orders.find("TON",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void pendingSignatureRetainsSequenceIntentAndFrozenPrincipal(){
        var request=service.reserve(op(),source,draft(op(),"TON"));fixture.signingPending.set(true);
        assertThrows(MpcPendingException.class,()->service.sign(op(),request.id()));service.unknown(op(),"pending");
        assertEquals(0,service.releaseUnsigned(op()));assertNull(orders.find("TON",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(0,new BigDecimal("1.1").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"TON","TON",ACCOUNT).orElseThrow().getLockedBalance()));
        fixture.signingPending.set(false);service.sign(op(),request.id());assertNotNull(service.commit(op(),request.id()));
    }
    @Test void onlyNoIntentAndNoHashMayRearm(){service.unknown(op(),"before intent");assertEquals(1,service.releaseUnsigned(op()));assertEquals("FROZEN",orders.find("TON",REFERENCE,TENANT).orElseThrow().getStatus());}
    @Test void differentOrdersReserveDifferentSequencesEvenWithStaleChainObservation(){
        var first=prepared(op(),"TON");createOrder("TON-SECOND","TON");var second=prepared(op("TON-SECOND"),"TON");
        assertNotEquals(first.messageHashHex(),second.messageHashHex());assertEquals(8,service.payload(service.intent(op("TON-SECOND")).orElseThrow()).unsigned().sequence());
    }
    @Test void foreignOwnerChangedRecipientAndForgedSourceCannotReserveAnySequence(){
        assertThrows(RuntimeException.class,()->service.reserve(new TonSubmissionService.Operation(UUID.randomUUID(),REFERENCE,TonSubmissionService.Kind.WITHDRAWAL),source,draft(op(),"TON")));
        recipient="0:"+"cd".repeat(32);assertThrows(RuntimeException.class,()->service.reserve(op(),source,draft(op(),"TON")));
        recipient="0:"+"ab".repeat(32);var forged=source.toBuilder().id(Long.MAX_VALUE).build();
        assertThrows(RuntimeException.class,()->service.reserve(op(),forged,draft(op(),"TON")));
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='TON'",Integer.class));
    }
    @Test void intentInsertFailureRollsBackReservedSequence(){
        jdbc.execute("create function public.ton_test_reject_intent() returns trigger language plpgsql as $$ begin raise exception 'reject intent';end $$;create trigger ton_test_reject_intent before insert on wallet_mpc_sign_request for each row execute function public.ton_test_reject_intent()");
        try{assertThrows(RuntimeException.class,()->service.reserve(op(),source,draft(op(),"TON")));assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='TON'",Integer.class));}
        finally{jdbc.execute("drop trigger ton_test_reject_intent on wallet_mpc_sign_request;drop function public.ton_test_reject_intent()");}
    }
    @Test void hashBindingFailureRollsBackBocAndRetainsCompletedWitness(){
        var request=service.reserve(op(),source,draft(op(),"TON"));service.sign(op(),request.id());
        jdbc.execute("create function public.ton_test_reject_hash() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject hash';end if;return new;end $$;create trigger ton_test_reject_hash before update on withdrawal_order for each row execute function public.ton_test_reject_hash()");
        try{assertThrows(RuntimeException.class,()->service.commit(op(),request.id()));assertEquals(0,jdbc.queryForObject("select count(*) from ton_transaction",Integer.class));assertEquals(MpcSignRequest.State.COMPLETED,service.intent(op()).orElseThrow().state());}
        finally{jdbc.execute("drop trigger ton_test_reject_hash on withdrawal_order;drop function public.ton_test_reject_hash()");}
        assertNotNull(service.commit(op(),request.id()));
    }
    @Test void exactPaymentSettlesOnceAfterPersistedObservationDepth(){
        var signed=prepared(op(),"TON");service.restoreSent(op(),signed.messageHashHex());var receipt=receipt(op(),signed,false);
        assertFalse(service.settle(op(),receipt,123,2));assertNotNull(service.proof(op()).orElseThrow());
        assertTrue(service.settle(op(),null,124,2));assertFalse(service.settle(op(),null,124,2));
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"TON","TON",ACCOUNT).orElseThrow();
        assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("3.9").compareTo(balance.getTotalBalance()));
        assertTrue(transactions.findConfirmedFeeAtomic("TON",signed.messageHashHex()).orElseThrow().signum()>0);
    }
    @Test void skippedSendRefundsOnceAndRetainsActualExpense(){
        var signed=prepared(op(),"TON");service.restoreSent(op(),signed.messageHashHex());
        assertFalse(service.settle(op(),receipt(op(),signed,true),123,2));
        assertTrue(service.settle(op(),null,124,2));assertFalse(service.settle(op(),null,124,2));
        assertEquals("FAILED",orders.find("TON",REFERENCE,TENANT).orElseThrow().getStatus());
        assertEquals(0,new BigDecimal("5").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"TON","TON",ACCOUNT).orElseThrow().getTotalBalance()));
        assertTrue(transactions.findConfirmedFeeAtomic("TON",signed.messageHashHex()).orElseThrow().signum()>0);
    }
    @Test void missingDeliveryAndChangedCustomerFeeCannotSettleFrozenPrincipal(){
        var signed=prepared(op(),"TON");service.restoreSent(op(),signed.messageHashHex());var correct=receipt(op(),signed,false);
        assertThrows(RuntimeException.class,()->service.settle(op(),new TonExecutionReceipt.Trace(correct.owner(),null,null,List.of()),123,1));
        jdbc.update("update withdrawal_order set fee=0.2 where tenant_id=? and order_no=?",TENANT,REFERENCE);
        assertThrows(RuntimeException.class,()->service.settle(op(),correct,123,1));
        assertEquals("SENT",orders.find("TON",REFERENCE,TENANT).orElseThrow().getStatus());assertTrue(service.proof(op()).isEmpty());
    }
    @Test void ledgerFailureRollsBackExecutionProofAndResumesTheSameEvidence(){
        var signed=prepared(op(),"TON");service.restoreSent(op(),signed.messageHashHex());var receipt=receipt(op(),signed,false);
        jdbc.update("update ledger_balance set locked_balance=0,available_balance=5 where chain='TON' and account_id=?",ACCOUNT);
        assertThrows(RuntimeException.class,()->service.settle(op(),receipt,123,1));assertTrue(service.proof(op()).isEmpty());
        jdbc.update("update ledger_balance set locked_balance=1.1,available_balance=3.9 where chain='TON' and account_id=?",ACCOUNT);
        assertTrue(service.settle(op(),receipt,123,1));
    }
    @Test void configuredMasterAndPrecisionStayFixedBeforeAssetDisable(){
        createOrder("TON-JETTON","USDC");var op=op("TON-JETTON");var request=service.reserve(op,source,draft(op,"USDC"));
        jdbc.update("update token_config set enabled=false where chain='TON'");
        assertEquals(request.id(),service.reserve(op,source,null).id());service.sign(op,request.id());assertNotNull(service.commit(op,request.id()));
    }
    @Test void collectionRequiresOwnedCollectorAndLateErrorsDoNotRegressState(){
        var collections=new CollectionRecordRepository(jdbc);UUID custodyId=UUID.randomUUID();
        new CustodyAddressRepository(jdbc).insert(custodyId,TENANT,source.getId(),"TON","testnet",source.getAddress(),null,"ton-source",null,"{}","CONSOLE",42,0,1,null);
        String reference="TON-COLLECTION";collections.create(TENANT,custodyId,reference,"TON","TON",source.getAddress(),recipient,BigDecimal.ONE,BigDecimal.ZERO,null);
        collections.claimSigningDurable(TENANT,"TON",reference);var op=new TonSubmissionService.Operation(TENANT,reference,TonSubmissionService.Kind.COLLECTION);
        assertThrows(RuntimeException.class,()->service.reserve(op,source,draft(op,"TON")));
        var target=fixture.address(jdbc,TENANT,ChainType.TON,0,2,"HOT");recipient=target.getAddress();
        jdbc.update("update collection_record set to_address=? where tenant_id=? and collection_no=?",recipient,TENANT,reference);
        var signed=prepared(op,"TON");service.restoreSent(op,signed.messageHashHex());service.unknown(op,"late error");
        assertEquals("SENT",collections.findStatus(TENANT,"TON",reference).orElseThrow());
        assertTrue(service.settle(op,receipt(op,signed,false),123,1));service.unknown(op,"late error");
        assertEquals("CONFIRMED",collections.findStatus(TENANT,"TON",reference).orElseThrow());
    }
    @Test void concurrentConfirmationsDebitPrincipalExactlyOnce() throws Exception {
        var signed=prepared(op(),"TON");service.restoreSent(op(),signed.messageHashHex());var receipt=receipt(op(),signed,false);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<12;i++)futures.add(pool.submit(()->service.settle(op(),receipt,123,1)));
            int changes=0;for(var f:futures)if(f.get(20,TimeUnit.SECONDS))changes++;assertEquals(1,changes);
        }
    }
    @Test void lostReplyRecoversOriginalBocWithoutNewSequenceOrTaskApproval(){
        var rpc=mock(TonCenterClient.class);String owner=MpcTonTransactionCodec.canonicalAddress(source.getAddress());when(rpc.seqno(owner)).thenReturn(7L);
        var runtime=mock(WalletRuntimeConfigService.class);var transfers=new TonTransactionService(rpc,service,new ChainProfileRepository(jdbc),new TokenConfigRepository(jdbc),orders,new CollectionRecordRepository(jdbc),runtime);
        var accepted=new java.util.concurrent.atomic.AtomicBoolean();
        when(rpc.findExternalMessageTransaction(anyString(),anyString(),anyInt())).thenAnswer(call->accepted.get()?Optional.of(json.createObjectNode()):Optional.empty());
        when(rpc.sendBoc(any())).thenAnswer(call->{
            String hash=orders.findTxHash("TON",REFERENCE,TENANT).orElseThrow();
            assertArrayEquals(call.getArgument(0),Base64.getDecoder().decode(transactions.findSigned(hash).orElseThrow().getRawPayload()));
            accepted.set(true);throw new IllegalStateException("lost reply");
        });
        assertThrows(IllegalStateException.class,()->transfers.send(op(),source));service.unknown(op(),"lost reply");
        doThrow(new IllegalStateException("disabled")).when(runtime).requireTaskEnabled(anyString(),anyString(),anyString());
        assertTrue(transfers.recover(op()));verify(rpc,times(1)).sendBoc(any());verify(rpc,times(1)).seqno(anyString());
        assertEquals("SENT",orders.find("TON",REFERENCE,TENANT).orElseThrow().getStatus());
    }
}
