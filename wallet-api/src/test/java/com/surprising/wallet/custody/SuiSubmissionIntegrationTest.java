package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;
import com.surprising.wallet.common.signing.SuiSigningPayload;
import com.surprising.wallet.sdk.signing.sui.SuiCoin;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.sui.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.SuiSubmissionService.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.bitcoinj.base.Base58;
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

/** Actual PG18 object admission, atomic wire binding, recovery and money boundaries. Crypto/chain observations are synthetic here. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class SuiSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="SUI-MPC-TEST",ACCOUNT="sui-mpc-principal",GENESIS=Base58.encode(new byte[32]);
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private SuiSubmissionService service;
    private WithdrawalOrderRepository orders;
    private SuiTransactionRepository transactions;
    private ChainAddressRecord source;
    private String recipient="0x"+"ab".repeat(32);
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='SUI'");
        jdbc.update("update chain_profile set enabled=true,default_fee_rate=10000000 where chain='SUI' and network='testnet'");
        new com.surprising.wallet.repository.CustodyTenantChainTableRepository(jdbc).setStatus(TENANT,
                "SUI","ACTIVE",UUID.fromString("00000000-0000-0000-0000-000000000002"));
        fixture=new MpcSigningTestSupport(jdbc);source=fixture.address(jdbc,TENANT,ChainType.SUI,42,1,"DEPOSIT");
        orders=new WithdrawalOrderRepository(jdbc);transactions=new SuiTransactionRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(SuiTransactionRepository.class,()->transactions);
        context.registerBean(WalletMpcSignRequestRepository.class,()->new WalletMpcSignRequestRepository(jdbc));
        context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));
        context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository.class,
                ()->new com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository(jdbc));
        context.registerBean(com.surprising.wallet.service.HotWalletReservationLifecycleService.class);
        context.registerBean(WithdrawalSettlementService.class);context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(SuiSubmissionService.class);context.refresh();service=context.getBean(SuiSubmissionService.class);
        createOrder(REFERENCE,"SUI");
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private Operation op(){return op(REFERENCE);}
    private Operation op(String reference){return new Operation(TENANT,reference,Kind.WITHDRAWAL);}
    private void createOrder(String reference,String symbol){
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'SUI',?,?,5,5) on conflict(chain,asset_symbol,account_id) do nothing",TENANT,symbol,ACCOUNT);
        orders.createForTenant(TENANT,reference,42,"SUI",symbol,source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("SUI",symbol,ACCOUNT,new BigDecimal("1.1"),TENANT));
        orders.updateStatus(TENANT,"SUI",reference,"FROZEN",source.getAddress(),null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"SUI",reference,source.getAddress()));
    }
    private SuiSigningPayload draft(Operation op,String symbol){
        int decimals=9;String type=SuiRpcClient.SUI_COIN_TYPE;List<SuiCoin> inputs=List.of();
        if(!symbol.equals("SUI")){
            var token=new TokenConfigRepository(jdbc).findDefinition("SUI","testnet",symbol).orElseThrow();
            type=token.getContractAddress();decimals=token.getDecimals();inputs=List.of(coin("cd"));
        }
        return new SuiSigningPayload(symbol,BigDecimal.ONE,op.kind()==Kind.COLLECTION?BigDecimal.ZERO:new BigDecimal("0.1"),decimals,
                new MpcSuiTransactionCodec.Unsigned("testnet",GENESIS,source.getAddress(),recipient,BigDecimal.ONE.movePointRight(decimals).longValueExact(),type,inputs,List.of(coin("ef")),1000,10_000_000,service.publicKey(source)));
    }
    private static SuiCoin coin(String id){return new SuiCoin("0x"+id.repeat(32),"7",Base58.encode(new byte[32]),new BigDecimal("10000000000"));}
    private MpcSuiTransactionCodec.Signed prepared(Operation op,String symbol){var request=service.reserve(op,source,draft(op,symbol));service.sign(op,request.id());return service.commit(op,request.id());}
    private JsonNode receipt(Operation op,MpcSuiTransactionCodec.Signed signed,boolean paid,long fee){return SuiReceiptTestSupport.payment(service.payload(service.intent(op).orElseThrow()).unsigned(),signed,paid,fee);}
    @Test void objectReferencesAndIntentCommitTogetherBeforeMpcAndSelectedWireBeforeNetwork(){
        var request=service.reserve(op(),source,draft(op(),"SUI"));String digest=MpcSuiTransactionCodec.digest(service.payload(request).unsigned());
        assertEquals("SIGNING",transactions.find(digest).orElseThrow().getStatus());assertNull(transactions.find(digest).orElseThrow().getRawPayload());
        assertNull(orders.find("SUI",REFERENCE,TENANT).orElseThrow().getTxHash());
        service.sign(op(),request.id());var signed=service.commit(op(),request.id());
        assertEquals(signed.digest(),orders.findTxHash("SUI",REFERENCE,TENANT).orElseThrow());
        assertEquals(json.writeValueAsString(signed),transactions.find(digest).orElseThrow().getRawPayload());
        assertEquals(request.id(),service.reserve(op(),source,null).id());assertEquals(signed,service.commit(op(),request.id()));
        assertEquals(0,service.releaseUnsigned(op()));
    }
    @Test void intentInsertFailureRollsBackObjectAdmission(){
        jdbc.execute("create function public.sui_test_reject_intent() returns trigger language plpgsql as $$ begin raise exception 'reject intent';end $$;create trigger sui_test_reject_intent before insert on wallet_mpc_sign_request for each row execute function public.sui_test_reject_intent()");
        try{assertThrows(RuntimeException.class,()->service.reserve(op(),source,draft(op(),"SUI")));assertEquals(0,jdbc.queryForObject("select count(*) from sui_transaction",Integer.class));}
        finally{jdbc.execute("drop trigger sui_test_reject_intent on wallet_mpc_sign_request;drop function public.sui_test_reject_intent()");}
    }
    @Test void hashBindingFailureRetainsIntentAndRollsBackSelectedWire(){
        var request=service.reserve(op(),source,draft(op(),"SUI"));service.sign(op(),request.id());String digest=MpcSuiTransactionCodec.digest(service.payload(request).unsigned());
        jdbc.execute("create function public.sui_test_reject_hash() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject hash';end if;return new;end $$;create trigger sui_test_reject_hash before update on withdrawal_order for each row execute function public.sui_test_reject_hash()");
        try{assertThrows(RuntimeException.class,()->service.commit(op(),request.id()));assertNull(transactions.find(digest).orElseThrow().getRawPayload());assertEquals("SIGNING",transactions.find(digest).orElseThrow().getStatus());assertEquals(MpcSignRequest.State.COMPLETED,service.intent(op()).orElseThrow().state());}
        finally{jdbc.execute("drop trigger sui_test_reject_hash on withdrawal_order;drop function public.sui_test_reject_hash()");}
        assertNotNull(service.commit(op(),request.id()));
    }
    @Test void anotherAssetCannotReserveObjectsWhileTheSenderHasAnUnfinishedIntent(){
        service.reserve(op(),source,draft(op(),"SUI"));createOrder("TOKEN","USDC");
        assertThrows(RuntimeException.class,()->service.reserve(op("TOKEN"),source,draft(op("TOKEN"),"USDC")));
        assertTrue(service.intent(op("TOKEN")).isEmpty());assertEquals(1,jdbc.queryForObject("select count(*) from sui_transaction",Integer.class));
    }
    @Test void concurrentBusinessReservationsAdmitOnlyOneSenderIntent()throws Exception{
        createOrder("OTHER","SUI");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->reserveResult(op()));var second=executor.submit(()->reserveResult(op("OTHER")));
            assertEquals(1,first.get(20,TimeUnit.SECONDS)+second.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from sui_transaction",Integer.class));
    }
    private int reserveResult(Operation op){try{service.reserve(op,source,draft(op,"SUI"));return 1;}catch(IllegalStateException expected){return 0;}}
    @Test void staleObjectsCannotAssignAnAlreadyPaidDigestToANewBusiness(){
        var signed=prepared(op(),"SUI");service.restoreSent(op(),signed.digest());assertTrue(service.settle(op(),receipt(op(),signed,true,1_500_000),123,1));
        createOrder("REUSE","SUI");assertThrows(RuntimeException.class,()->service.reserve(op("REUSE"),source,draft(op("REUSE"),"SUI")));
        assertTrue(service.intent(op("REUSE")).isEmpty());assertEquals(1,jdbc.queryForObject("select count(*) from sui_transaction",Integer.class));
    }
    @Test void exactPaymentAndNegativeStorageExpenseSettleOnceAfterPersistedCheckpointDepth(){
        var signed=prepared(op(),"SUI");service.restoreSent(op(),signed.digest());
        assertFalse(service.settle(op(),receipt(op(),signed,true,-1_914_600),123,2));assertTrue(service.proof(op()).isPresent());
        assertTrue(service.settle(op(),null,124,2));assertFalse(service.settle(op(),null,124,2));
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"SUI","SUI",ACCOUNT).orElseThrow();
        assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("3.9").compareTo(balance.getTotalBalance()));
        assertEquals(0,new BigDecimal("-1914600").compareTo(transactions.findConfirmedFeeAtomic("SUI",signed.digest()).orElseThrow()));
        service.observed(op(),signed.digest());assertEquals("CONFIRMED",transactions.find(signed.digest()).orElseThrow().getStatus());
    }
    @Test void committedFailureRefundsOnceAndRetainsItsActualFee(){
        var signed=prepared(op(),"SUI");service.restoreSent(op(),signed.digest());
        assertTrue(service.settle(op(),receipt(op(),signed,false,1_000_000),123,1));assertFalse(service.settle(op(),null,123,1));
        assertEquals("FAILED",orders.find("SUI",REFERENCE,TENANT).orElseThrow().getStatus());
        assertEquals(0,new BigDecimal("5").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"SUI","SUI",ACCOUNT).orElseThrow().getTotalBalance()));
        assertEquals(0,new BigDecimal("1000000").compareTo(transactions.findConfirmedFeeAtomic("SUI",signed.digest()).orElseThrow()));
    }
    @Test void changedCustomerFeeAndAbsentOriginalProofCannotSettleFrozenPrincipal(){
        var signed=prepared(op(),"SUI");service.restoreSent(op(),signed.digest());
        assertThrows(RuntimeException.class,()->service.settle(op(),json.createObjectNode().put("digest",signed.digest()).put("checkpoint",123),123,1));
        jdbc.update("update withdrawal_order set fee=0.2 where tenant_id=? and order_no=?",TENANT,REFERENCE);
        assertThrows(RuntimeException.class,()->service.settle(op(),receipt(op(),signed,true,1_500_000),123,1));
        assertEquals("SENT",orders.find("SUI",REFERENCE,TENANT).orElseThrow().getStatus());assertTrue(service.proof(op()).isEmpty());
    }
    @Test void ledgerFailureRollsBackProofAndTerminalExpense(){
        var signed=prepared(op(),"SUI");service.restoreSent(op(),signed.digest());var proof=receipt(op(),signed,true,1_500_000);
        jdbc.update("update ledger_balance set locked_balance=0,available_balance=5 where chain='SUI' and account_id=?",ACCOUNT);
        assertThrows(RuntimeException.class,()->service.settle(op(),proof,123,1));assertTrue(service.proof(op()).isEmpty());assertEquals("SENT",transactions.find(signed.digest()).orElseThrow().getStatus());
        jdbc.update("update ledger_balance set locked_balance=1.1,available_balance=3.9 where chain='SUI' and account_id=?",ACCOUNT);
        assertTrue(service.settle(op(),proof,123,1));
    }
    @Test void existingTokenIntentSurvivesAssetDisableWithoutSelectingNewObjects(){
        createOrder("TOKEN","USDC");var op=op("TOKEN");var request=service.reserve(op,source,draft(op,"USDC"));
        jdbc.update("update token_config set enabled=false where chain='SUI'");
        assertEquals(request.id(),service.reserve(op,source,null).id());service.sign(op,request.id());assertNotNull(service.commit(op,request.id()));
    }
    @Test void lostReplyRecoversOriginalObjectsAndWireAfterNewTasksAreDisabled(){
        var rpc=mock(SuiRpcClient.class);when(rpc.serviceInfo()).thenReturn(new SuiRpcClient.ChainInfo(GENESIS,"unknown",0,123));when(rpc.referenceGasPrice()).thenReturn(1000L);when(rpc.coins(anyString(),anyString(),anyInt())).thenReturn(List.of(coin("ef")));
        var runtime=mock(WalletRuntimeConfigService.class);var transfers=new SuiTransactionService(rpc,service,new ChainProfileRepository(jdbc),new TokenConfigRepository(jdbc),orders,new CollectionRecordRepository(jdbc),runtime,
                new com.surprising.wallet.service.HotWalletBroadcastFence(
                        new com.surprising.wallet.repository.CustodyTenantChainTableRepository(jdbc),
                        new com.surprising.wallet.repository.CustodyHotWalletRepository(jdbc),
                        new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                        new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                        new CollectionRecordRepository(jdbc),
                        new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc),
                        fixture.manager));
        var accepted=new java.util.concurrent.atomic.AtomicBoolean();
        when(rpc.transactionBlock(anyString())).thenAnswer(call->accepted.get()?json.createObjectNode().put("digest",call.<String>getArgument(0)):json.createObjectNode());
        when(rpc.executeSignedTransaction(anyString(),anyString())).thenAnswer(call->{
            String digest=orders.findTxHash("SUI",REFERENCE,TENANT).orElseThrow();var wire=json.readValue(transactions.find(digest).orElseThrow().getRawPayload(),MpcSuiTransactionCodec.Signed.class);
            assertEquals(wire.txBytesBase64(),call.getArgument(0));assertEquals(wire.signatureBase64(),call.getArgument(1));accepted.set(true);throw new IllegalStateException("lost reply");
        });
        assertThrows(IllegalStateException.class,()->transfers.send(op(),source));service.unknown(op(),"lost reply");
        doThrow(new IllegalStateException("disabled")).when(runtime).requireTaskEnabled(anyString(),anyString(),anyString());
        var chainRows=new com.surprising.wallet.repository.CustodyTenantChainTableRepository(jdbc);
        accepted.set(false);
        assertEquals(1,chainRows.setWithdrawalPaused(TENANT,"SUI",true,
                UUID.fromString("00000000-0000-0000-0000-000000000002")));
        assertThrows(IllegalStateException.class,()->transfers.recover(op()));
        verify(rpc,times(1)).executeSignedTransaction(anyString(),anyString());
        accepted.set(true);
        assertTrue(transfers.recover(op()));verify(rpc,times(1)).executeSignedTransaction(anyString(),anyString());verify(rpc,times(1)).coins(anyString(),anyString(),anyInt());
        assertEquals("SENT",orders.find("SUI",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void unsignedBusinessCanReturnToQueueButOwnedIntentCannotBeReleased(){
        service.unknown(op(),"worker stopped before intent");
        assertEquals(1,service.releaseUnsigned(op()));assertEquals("FROZEN",orders.find("SUI",REFERENCE,TENANT).orElseThrow().getStatus());
        orders.claimSigningDurable(TENANT,"SUI",REFERENCE,source.getAddress());service.reserve(op(),source,draft(op(),"SUI"));assertEquals(0,service.releaseUnsigned(op()));
    }
    @Test void collectionRequiresAnOwnedCollectorAndRetainsSignedStorageExpense(){
        var collections=new CollectionRecordRepository(jdbc);UUID custodyId=UUID.randomUUID();
        new CustodyAddressRepository(jdbc).insert(custodyId,TENANT,source.getId(),"SUI","testnet",source.getAddress(),null,"sui-source",null,"{}","CONSOLE",42,0,1,null);
        String reference="SUI-COLLECTION";collections.create(TENANT,custodyId,reference,"SUI","SUI",source.getAddress(),recipient,BigDecimal.ONE,BigDecimal.ZERO,null);
        collections.claimSigningDurable(TENANT,"SUI",reference);var operation=new Operation(TENANT,reference,Kind.COLLECTION);
        assertThrows(RuntimeException.class,()->service.reserve(operation,source,draft(operation,"SUI")));
        var collector=fixture.address(jdbc,TENANT,ChainType.SUI,0,2,"HOT");recipient=collector.getAddress();
        jdbc.update("update collection_record set to_address=? where tenant_id=? and collection_no=?",recipient,TENANT,reference);
        var signed=prepared(operation,"SUI");service.restoreSent(operation,signed.digest());
        assertTrue(service.settle(operation,receipt(operation,signed,true,-936_480),123,1));
        service.unknown(operation,"late error");assertEquals("CONFIRMED",collections.findStatus(TENANT,"SUI",reference).orElseThrow());
        assertEquals(0,new BigDecimal("-0.00093648").compareTo(jdbc.queryForObject("select fee from collection_record where tenant_id=? and collection_no=?",BigDecimal.class,TENANT,reference)));
    }
    @Test void concurrentConfirmationsDebitTheFrozenPrincipalExactlyOnce()throws Exception {
        var signed=prepared(op(),"SUI");service.restoreSent(op(),signed.digest());var proof=receipt(op(),signed,true,1_500_000);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var results=new ArrayList<Future<Boolean>>();for(int i=0;i<12;i++)results.add(executor.submit(()->service.settle(op(),proof,123,1)));
            int changes=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))changes++;assertEquals(1,changes);
        }
        assertEquals(0,new BigDecimal("3.9").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"SUI","SUI",ACCOUNT).orElseThrow().getTotalBalance()));
    }
}
