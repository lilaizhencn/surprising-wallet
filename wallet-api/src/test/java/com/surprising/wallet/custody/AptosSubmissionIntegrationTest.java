package com.surprising.wallet.custody;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.aptos.*;
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
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** PG18 intent/sequence/ownership/raw/receipt/ledger transactions; native crypto is a test substitute. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class AptosSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="APT-MPC-TEST",ACCOUNT="apt-mpc-principal";
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private AptosSubmissionService service;
    private WithdrawalOrderRepository orders;
    private AptosTransactionRepository transactions;
    private ChainAddressRecord source;
    private String recipient="0x"+"ab".repeat(32);
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='APTOS'");jdbc.update("update chain_profile set enabled=true,chain_id=4 where chain='APTOS' and network='testnet'");
        fixture=new MpcSigningTestSupport(jdbc);source=fixture.address(jdbc,TENANT,ChainType.APTOS,42,1,"DEPOSIT");orders=new WithdrawalOrderRepository(jdbc);transactions=new AptosTransactionRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(AptosTransactionRepository.class,()->transactions);context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
        context.registerBean(WalletMpcSignRequestRepository.class,()->new WalletMpcSignRequestRepository(jdbc));context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(WithdrawalSettlementService.class);context.registerBean(ObjectMapper.class,()->json);context.registerBean(AptosSubmissionService.class);context.refresh();service=context.getBean(AptosSubmissionService.class);
        createOrder(REFERENCE,"APT");
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private AptosSubmissionService.Operation op(){return op(REFERENCE);}
    private AptosSubmissionService.Operation op(String reference){return new AptosSubmissionService.Operation(TENANT,reference,AptosSubmissionService.Kind.WITHDRAWAL);}
    private void createOrder(String reference,String symbol){
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'APTOS',?,?,5,5) on conflict(chain,asset_symbol,account_id) do nothing",TENANT,symbol,ACCOUNT);
        orders.createForTenant(TENANT,reference,42,"APTOS",symbol,source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("APTOS",symbol,ACCOUNT,new BigDecimal("1.1"),TENANT));orders.updateStatus(TENANT,"APTOS",reference,"FROZEN",source.getAddress(),null,null);assertEquals(1,orders.claimSigningDurable(TENANT,"APTOS",reference,source.getAddress()));
    }
    private AptosSigningPayload draft(AptosSubmissionService.Operation op,String symbol){
        int decimals=8;String metadata=null;if(!symbol.equals("APT")){var token=new TokenConfigRepository(jdbc).findDefinition("APTOS","testnet",symbol).orElseThrow();metadata=AptosFungibleAsset.requireMetadata(token);decimals=token.getDecimals();}
        return new AptosSigningPayload(symbol,BigDecimal.ONE,op.kind()==AptosSubmissionService.Kind.COLLECTION?BigDecimal.ZERO:new BigDecimal("0.1"),decimals,
                new MpcAptosTransactionCodec.Unsigned(source.getAddress(),7,recipient,BigDecimal.ONE.movePointRight(decimals).longValueExact(),metadata,50_000,100,2_000_000_000,4,service.publicKey(source)));
    }
    private MpcAptosTransactionCodec.Signed prepared(AptosSubmissionService.Operation op,String symbol){var request=service.reserve(op,source,draft(op,symbol));service.sign(op,request.id());return service.commit(op,request.id());}
    private ObjectNode receipt(AptosSubmissionService.Operation op,MpcAptosTransactionCodec.Signed signed,boolean failed,long refund){return AptosReceiptTestSupport.receipt(service.payload(service.intent(op).orElseThrow()).unsigned(),signed,failed,refund);}
    @Test void fixedSequenceRawAndHashCommitBeforeNetworkAndRetryCannotCreateAnotherPayment(){
        var signed=prepared(op(),"APT");var request=service.intent(op()).orElseThrow();assertEquals(signed.txId(),orders.find("APTOS",REFERENCE,TENANT).orElseThrow().getTxHash());assertEquals(signed.json(),transactions.findSigned(signed.txId()).orElseThrow().getRawPayload());
        assertEquals("PREPARED",transactions.findSigned(signed.txId()).orElseThrow().getStatus());assertEquals(request.id(),service.reserve(op(),source,null).id());assertEquals(signed,service.commit(op(),request.id()));
        assertEquals(8L,jdbc.queryForObject("select next_sequence from account_sequence where chain='APTOS' and address=?",Long.class,source.getAddress()));service.observed(op(),signed.txId());service.restoreSent(op(),signed.txId());assertEquals("SENT",orders.find("APTOS",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void pendingSignatureRetainsSequenceIntentAndFrozenPrincipal(){
        var request=service.reserve(op(),source,draft(op(),"APT"));fixture.signingPending.set(true);assertThrows(MpcPendingException.class,()->service.sign(op(),request.id()));service.unknown(op(),"pending");assertEquals(0,service.releaseUnsigned(op()));assertNull(orders.find("APTOS",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(0,new BigDecimal("1.1").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"APTOS","APT",ACCOUNT).orElseThrow().getLockedBalance()));fixture.signingPending.set(false);service.sign(op(),request.id());assertNotNull(service.commit(op(),request.id()));
    }
    @Test void onlyNoIntentAndNoHashMayRearm(){service.unknown(op(),"before intent");assertEquals(1,service.releaseUnsigned(op()));assertEquals("FROZEN",orders.find("APTOS",REFERENCE,TENANT).orElseThrow().getStatus());}
    @Test void staleObservedSequenceAllocatesDifferentDurableSequencesForDifferentOrders(){
        var first=prepared(op(),"APT");createOrder("APT-SECOND","APT");var second=prepared(op("APT-SECOND"),"APT");assertNotEquals(first.txId(),second.txId());assertEquals(8,service.payload(service.intent(op("APT-SECOND")).orElseThrow()).unsigned().sequence());
    }
    @Test void foreignOwnerOrChangedRecipientCannotAdmitAnyIntentOrSequence(){
        assertThrows(RuntimeException.class,()->service.reserve(new AptosSubmissionService.Operation(UUID.randomUUID(),REFERENCE,AptosSubmissionService.Kind.WITHDRAWAL),source,draft(op(),"APT")));
        recipient="0x"+"cd".repeat(32);assertThrows(RuntimeException.class,()->service.reserve(op(),source,draft(op(),"APT")));assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='APTOS'",Integer.class));
    }
    @Test void intentInsertFailureRollsBackReservedSequence(){
        jdbc.execute("create function public.apt_test_reject_intent() returns trigger language plpgsql as $$ begin raise exception 'reject intent';end $$;create trigger apt_test_reject_intent before insert on wallet_mpc_sign_request for each row execute function public.apt_test_reject_intent()");
        try{assertThrows(RuntimeException.class,()->service.reserve(op(),source,draft(op(),"APT")));assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='APTOS'",Integer.class));}finally{jdbc.execute("drop trigger apt_test_reject_intent on wallet_mpc_sign_request;drop function public.apt_test_reject_intent()");}
    }
    @Test void hashBindingFailureRollsBackRawAndRetainsCompletedWitness(){
        var request=service.reserve(op(),source,draft(op(),"APT"));service.sign(op(),request.id());jdbc.execute("create function public.apt_test_reject_hash() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject hash';end if;return new;end $$;create trigger apt_test_reject_hash before update on withdrawal_order for each row execute function public.apt_test_reject_hash()");
        try{assertThrows(RuntimeException.class,()->service.commit(op(),request.id()));assertEquals(0,jdbc.queryForObject("select count(*) from aptos_transaction",Integer.class));assertEquals(MpcSignRequest.State.COMPLETED,service.intent(op()).orElseThrow().state());}finally{jdbc.execute("drop trigger apt_test_reject_hash on withdrawal_order;drop function public.apt_test_reject_hash()");}assertNotNull(service.commit(op(),request.id()));
    }
    @Test void committedPaymentSettlesOnceAfterRequiredVersionDepthAndSubtractsStorageRefund(){
        var signed=prepared(op(),"APT");service.restoreSent(op(),signed.txId());var receipt=receipt(op(),signed,false,123);assertFalse(service.settle(op(),receipt,123,2));assertTrue(service.settle(op(),receipt,124,2));assertFalse(service.settle(op(),receipt,124,2));
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"APTOS","APT",ACCOUNT).orElseThrow();assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("3.9").compareTo(balance.getTotalBalance()));assertEquals(9877,transactions.findConfirmedFeeAtomic("APTOS",signed.txId()).orElseThrow().longValueExact());assertEquals(signed.json(),transactions.findSigned(signed.txId()).orElseThrow().getRawPayload());
    }
    @Test void committedFailureRefundsOnceAndStillRetainsActualFee(){
        var signed=prepared(op(),"APT");service.restoreSent(op(),signed.txId());assertTrue(service.settle(op(),receipt(op(),signed,true,0),123,1));assertFalse(service.settle(op(),receipt(op(),signed,true,0),123,1));
        assertEquals("FAILED",orders.find("APTOS",REFERENCE,TENANT).orElseThrow().getStatus());assertEquals(0,new BigDecimal("5").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"APTOS","APT",ACCOUNT).orElseThrow().getTotalBalance()));assertEquals(10000,transactions.findConfirmedFeeAtomic("APTOS",signed.txId()).orElseThrow().longValueExact());
    }
    @Test void wrongStoreOrChangedCustomerFeeCannotSettleFrozenPrincipal(){
        var signed=prepared(op(),"APT");service.restoreSent(op(),signed.txId());var wrong=receipt(op(),signed,false,0);((ObjectNode)wrong.path("events").path(1).path("data")).put("store","0x1");assertThrows(RuntimeException.class,()->service.settle(op(),wrong,124,1));
        jdbc.update("update withdrawal_order set fee=0.2 where tenant_id=? and order_no=?",TENANT,REFERENCE);assertThrows(RuntimeException.class,()->service.settle(op(),receipt(op(),signed,false,0),124,1));assertEquals("SENT",orders.find("APTOS",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void ledgerFailureRollsBackReceiptAndResumesExactlyTheSameEvidence(){
        var signed=prepared(op(),"APT");service.restoreSent(op(),signed.txId());jdbc.update("update ledger_balance set locked_balance=0,available_balance=5 where chain='APTOS' and account_id=?",ACCOUNT);assertThrows(RuntimeException.class,()->service.settle(op(),receipt(op(),signed,false,0),123,1));assertNull(transactions.findSigned(signed.txId()).orElseThrow().getVersion());
        jdbc.update("update ledger_balance set locked_balance=1.1,available_balance=3.9 where chain='APTOS' and account_id=?",ACCOUNT);assertTrue(service.settle(op(),receipt(op(),signed,false,0),123,1));
    }
    @Test void tokenMetadataAndPrecisionAreFixedBeforeDisableAndOriginalRequestStillRecovers(){
        createOrder("APT-FA","USDC");var op=op("APT-FA");var request=service.reserve(op,source,draft(op,"USDC"));jdbc.update("update token_config set enabled=false where chain='APTOS'");assertEquals(request.id(),service.reserve(op,source,null).id());service.sign(op,request.id());assertNotNull(service.commit(op,request.id()));
    }
    @Test void collectionRequiresOwnedJointCollectorAndLateErrorsCannotRegressSentOrTerminal(){
        var collections=new CollectionRecordRepository(jdbc);UUID custodyId=UUID.randomUUID();new CustodyAddressRepository(jdbc).insert(custodyId,TENANT,source.getId(),"APTOS","testnet",source.getAddress(),null,"apt-source",null,"{}","CONSOLE",42,0,1,null);
        String reference="APT-COLLECTION";collections.create(TENANT,custodyId,reference,"APTOS","APT",source.getAddress(),recipient,BigDecimal.ONE,BigDecimal.ZERO,null);collections.claimSigningDurable(TENANT,"APTOS",reference);var op=new AptosSubmissionService.Operation(TENANT,reference,AptosSubmissionService.Kind.COLLECTION);
        assertThrows(RuntimeException.class,()->service.reserve(op,source,draft(op,"APT")));var target=fixture.address(jdbc,TENANT,ChainType.APTOS,0,2,"HOT");recipient=target.getAddress();jdbc.update("update collection_record set to_address=? where tenant_id=? and collection_no=?",recipient,TENANT,reference);
        var signed=prepared(op,"APT");service.restoreSent(op,signed.txId());service.unknown(op,"late error");assertEquals("SENT",collections.findStatus(TENANT,"APTOS",reference).orElseThrow());assertTrue(service.settle(op,receipt(op,signed,false,0),123,1));service.unknown(op,"late error");assertEquals("CONFIRMED",collections.findStatus(TENANT,"APTOS",reference).orElseThrow());
    }
    @Test void concurrentConfirmationsDebitPrincipalExactlyOnce() throws Exception {
        var signed=prepared(op(),"APT");service.restoreSent(op(),signed.txId());try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<12;i++)futures.add(pool.submit(()->service.settle(op(),receipt(op(),signed,false,0),123,1)));int changes=0;for(var f:futures)if(f.get(20,TimeUnit.SECONDS))changes++;assertEquals(1,changes);}
    }
    @Test void lostReplyRecoversSelectedRawWithoutNewGasSequenceOrTaskApproval(){
        var rpc=mock(AptosRpcClient.class);when(rpc.estimateGasPrice()).thenReturn(100L);when(rpc.ledgerInfo()).thenReturn(json.createObjectNode().put("chain_id",4).put("ledger_timestamp","1793400000000000"));when(rpc.sequenceNumber(source.getAddress())).thenReturn(7L);
        var runtime=mock(WalletRuntimeConfigService.class);var profiles=new ChainProfileRepository(jdbc);var transfers=new AptosTransactionService(rpc,service,profiles,new TokenConfigRepository(jdbc),orders,new CollectionRecordRepository(jdbc),runtime);var accepted=new java.util.concurrent.atomic.AtomicBoolean();
        when(rpc.transactionByHash(anyString())).thenAnswer(call->accepted.get()?json.createObjectNode().put("type","pending_transaction"):null);
        when(rpc.submitTransaction(any())).thenAnswer(call->{String hash=orders.findTxHash("APTOS",REFERENCE,TENANT).orElseThrow();assertEquals(call.getArgument(0).toString(),transactions.findSigned(hash).orElseThrow().getRawPayload());accepted.set(true);throw new IllegalStateException("lost reply");});
        assertThrows(IllegalStateException.class,()->transfers.send(op(),source));service.unknown(op(),"lost reply");doThrow(new IllegalStateException("disabled")).when(runtime).requireTaskEnabled(anyString(),anyString(),anyString());
        assertTrue(transfers.recover(op()));verify(rpc,times(1)).submitTransaction(any());verify(rpc,times(1)).sequenceNumber(anyString());assertEquals("SENT",orders.find("APTOS",REFERENCE,TENANT).orElseThrow().getStatus());
    }
}
