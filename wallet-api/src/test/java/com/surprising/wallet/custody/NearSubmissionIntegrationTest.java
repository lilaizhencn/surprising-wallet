package com.surprising.wallet.custody;

import com.surprising.wallet.common.signing.NearOperation.Kind;

import com.surprising.wallet.common.signing.NearRegistrationPayload;

import com.surprising.wallet.common.signing.NearOperation;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;
import com.surprising.wallet.common.signing.NearSigningPayload;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.near.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;

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

/** Actual PG18 nonce admission, atomic wire binding, recovery and money boundaries. Crypto/chain observations are synthetic here. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class NearSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="NEAR-MPC-TEST",ACCOUNT="near-mpc-principal",GENESIS=Base58.encode(new byte[32]);
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private NearSubmissionService service;
    private WithdrawalOrderRepository orders;
    private NearTransactionRepository transactions;
    private ChainAddressRecord source;
    private String recipient="ab".repeat(32);
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='NEAR'");
        jdbc.update("update chain_profile set enabled=true,default_fee_rate=10000000 where chain='NEAR' and network='testnet'");
        jdbc.update("update token_config set enabled=true,collect_enabled=true where chain='NEAR' and network='testnet' and symbol='USDC'");
        fixture=new MpcSigningTestSupport(jdbc);source=fixture.address(jdbc,TENANT,ChainType.NEAR,42,1,"DEPOSIT");
        orders=new WithdrawalOrderRepository(jdbc);transactions=new NearTransactionRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(NearTransactionRepository.class,()->transactions);
        context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
        context.registerBean(WalletMpcSignRequestRepository.class,()->new WalletMpcSignRequestRepository(jdbc));
        context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));
        context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(CustodyHotWalletWithdrawalReservationRepository.class,()->new CustodyHotWalletWithdrawalReservationRepository(jdbc));
        context.registerBean(HotWalletReservationLifecycleService.class);
        context.registerBean(WithdrawalSettlementService.class);context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(CustodyGasUsageRepository.class,()->new CustodyGasUsageRepository(jdbc));
        context.registerBean(CustodyAddressRepository.class,()->new CustodyAddressRepository(jdbc));
        context.registerBean(CustodyGasAccountRepository.class,()->new CustodyGasAccountRepository(jdbc));
        context.registerBean(CustodyGasAccountingService.class,()->CustodyTestSupport.gasAccounting(jdbc));
        context.registerBean(NearRegistrationService.class);context.registerBean(NearSubmissionService.class);context.refresh();service=context.getBean(NearSubmissionService.class);
        createOrder(REFERENCE,"NEAR");
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private static HotWalletBroadcastFence permissiveBroadcastFence() {
        var fence=mock(HotWalletBroadcastFence.class);
        try {
            when(fence.withPayoutBroadcastPermit(any(),anyString(),any(),any()))
                    .thenAnswer(call->((HotWalletBroadcastFence.Broadcast<?>)call.getArgument(3)).send());
            when(fence.withAccountCollectionBroadcastPermit(any(),anyString(),anyString(),anyString(),anyString(),any(),any()))
                    .thenAnswer(call->((HotWalletBroadcastFence.Broadcast<?>)call.getArgument(6)).send());
        } catch(Exception error){throw new IllegalStateException(error);}
        return fence;
    }
    private record RegistrationFixture(NearOperation op,ChainAddressRecord gas,NearRegistrationPayload payload) { }
    private RegistrationFixture registrationFixture() {
        createOrder("TOKEN","USDC");var op=op("TOKEN");
        var gas=fixture.address(jdbc,TENANT,ChainType.NEAR,90,1,"SYSTEM_GAS");UUID custody=UUID.randomUUID();
        jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,derivation_subject,derivation_child) values (?,?,?,'NEAR','testnet',?,'near-gas','API',90,1)",custody,TENANT,gas.getId(),gas.getAddress());
        jdbc.update("insert into custody_gas_account(id,tenant_id,custody_address_id,chain,network,native_symbol,status) values (?,?,?,'NEAR','testnet','NEAR','ACTIVE')",UUID.randomUUID(),TENANT,custody);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'NEAR','NEAR',?,10,10)",TENANT,gas.getAccountId());
        var token=new TokenConfigRepository(jdbc).findDefinition("NEAR","testnet","USDC").orElseThrow();
        var unsigned=new MpcNearTransactionCodec.Registration("testnet",GENESIS,gas.getAddress(),7,recipient,token.getContractAddress(),new java.math.BigInteger("1250000000000000000000"),30_000_000_000_000L,100,200,GENESIS,fixture.signing.requireKey(TENANT,gas.getMpcKeyId()).publicKeyHex());
        return new RegistrationFixture(op,gas,new NearRegistrationPayload(op,service.payment(op),source.getId(),source.getAddress(),6,java.math.BigInteger.valueOf(1000),unsigned));
    }
    private NearRegistrationService registrations(){return context.getBean(NearRegistrationService.class);}
    private MpcNearTransactionCodec.Signed selectRegistration(RegistrationFixture f) {
        registrations().reserve(f.op(),source,f.payload());registrations().sign(f.op());return registrations().commit(f.op());
    }
    @Test void storageIntentNonceAndGasCommitBeforeRemoteSignatureAndSelectedWireBeforeRpc() {
        var f=registrationFixture();var r=registrations().reserve(f.op(),source,f.payload());
        assertTrue(service.intent(f.op()).isEmpty());
        assertEquals("RESERVED",new CustodyGasUsageRepository(jdbc).find(TENANT,"ADDRESS_PREPARATION",r.id()).orElseThrow().status());
        fixture.beforeSignature=()->assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        registrations().sign(f.op());var selected=registrations().commit(f.op());
        assertEquals(json.writeValueAsString(selected),transactions.find(selected.txHash()).orElseThrow().getRawPayload());
        assertEquals(r.id(),registrations().reserve(f.op(),source,null).id());assertEquals(selected,registrations().commit(f.op()));
    }
    @Test void unfundedStorageGasRollsBackTheNonceRequestAndChainAdmission() {
        var f=registrationFixture();jdbc.update("update ledger_balance set available_balance=0,total_balance=0 where tenant_id=? and account_id=?",TENANT,f.gas().getAccountId());
        assertThrows(RuntimeException.class,()->registrations().reserve(f.op(),source,f.payload()));
        assertTrue(registrations().intent(f.op()).isEmpty());assertEquals(0,jdbc.queryForObject("select count(*) from near_transaction",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='NEAR'",Integer.class));
    }
    @Test void unknownStorageSignatureHoldsTheOriginalParentAndBlocksPaymentAdmission() {
        var f=registrationFixture();registrations().reserve(f.op(),source,f.payload());service.unknown(f.op(),"storage signature unknown");
        assertEquals(0,service.releaseUnsigned(f.op()));assertThrows(RuntimeException.class,()->service.reserve(f.op(),source,draft(f.op(),"USDC")));
        assertTrue(service.intent(f.op()).isEmpty());assertEquals("BROADCAST_UNKNOWN",orders.find("NEAR","TOKEN",TENANT).orElseThrow().getStatus());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void finalStorageReceiptChargesOnlyRetainedDepositAndRealFeeAndUnlocksOriginalPayment(boolean returned) {
        var f=registrationFixture();var selected=selectRegistration(f);var p=registrations().payload(f.op());
        var receipt=NearReceiptTestSupport.storage(p.unsigned(),selected,true,returned,10);
        assertTrue(registrations().settle(f.op(),receipt,123,1));
        var amount=java.math.BigInteger.TEN.add(returned?java.math.BigInteger.ZERO:p.unsigned().depositAtomic());
        var usage=new CustodyGasUsageRepository(jdbc).find(TENANT,"ADDRESS_PREPARATION",registrations().intent(f.op()).orElseThrow().id()).orElseThrow();
        assertEquals(amount,usage.actualAmount().movePointRight(24).toBigIntegerExact());
        assertEquals(0,new BigDecimal("10").subtract(new BigDecimal(amount).movePointLeft(24)).compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"NEAR","NEAR",f.gas().getAccountId()).orElseThrow().getTotalBalance()));
        var payment=draft(f.op(),"USDC");jdbc.update("update token_config set enabled=false where chain='NEAR'");
        jdbc.update("update chain_profile set enabled=false where chain='NEAR'");jdbc.update("update chain_address set enabled=false where id=?",source.getId());
        assertNotNull(service.reserve(f.op(),source,payment));
        assertTrue(registrations().settle(f.op(),receipt,123,1));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_ledger_entry where entry_type='ADDRESS_PREPARATION'",Integer.class));
    }
    @Test void finalFailedStorageRefundsParentPrincipalAndChargesOnlyTheObservedNetworkFee() {
        var f=registrationFixture();var selected=selectRegistration(f);var receipt=NearReceiptTestSupport.storage(registrations().payload(f.op()).unsigned(),selected,false,true,10);
        assertFalse(registrations().settle(f.op(),receipt,123,1));assertEquals("FAILED",orders.find("NEAR","TOKEN",TENANT).orElseThrow().getStatus());
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"NEAR","USDC",ACCOUNT).orElseThrow();
        assertEquals(0,new BigDecimal("5").compareTo(balance.getAvailableBalance()));assertEquals(0,balance.getLockedBalance().signum());
        assertTrue(service.intent(f.op()).isEmpty());assertEquals(java.math.BigInteger.TEN,transactions.find(selected.txHash()).orElseThrow().getFeeYocto());
        assertFalse(registrations().settle(f.op(),receipt,123,1));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_ledger_entry where entry_type='ADDRESS_PREPARATION'",Integer.class));
    }
    @Test void missingFullReceiptsOrHeadersCannotSettleStorageGasOrReleaseTheParent() {
        var f=registrationFixture();var selected=selectRegistration(f);var receipt=NearReceiptTestSupport.storage(registrations().payload(f.op()).unsigned(),selected,true,false,10);
        assertThrows(RuntimeException.class,()->registrations().settle(f.op(),new NearStorageReceipt.Proof(receipt.execution(),receipt.headers(),Map.of()),123,1));
        assertThrows(RuntimeException.class,()->registrations().settle(f.op(),new NearStorageReceipt.Proof(receipt.execution(),Map.of(),receipt.receipts()),123,1));
        assertEquals("PREPARED",transactions.find(selected.txHash()).orElseThrow().getStatus());
        assertEquals("SIGNING",orders.find("NEAR","TOKEN",TENANT).orElseThrow().getStatus());
        assertEquals("RESERVED",new CustodyGasUsageRepository(jdbc).find(TENANT,"ADDRESS_PREPARATION",registrations().intent(f.op()).orElseThrow().id()).orElseThrow().status());
    }
    private NearOperation op(){return op(REFERENCE);}
    private NearOperation op(String reference){return new NearOperation(TENANT,reference,Kind.WITHDRAWAL);}
    private void createOrder(String reference,String symbol){
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'NEAR',?,?,5,5) on conflict(chain,asset_symbol,account_id) do nothing",TENANT,symbol,ACCOUNT);
        orders.createForTenant(TENANT,reference,42,"NEAR",symbol,source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("NEAR",symbol,ACCOUNT,new BigDecimal("1.1"),TENANT));
        orders.updateStatus(TENANT,"NEAR",reference,"FROZEN",source.getAddress(),null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"NEAR",reference,source.getAddress()));
    }
    private NearSigningPayload draft(NearOperation op,String symbol){
        int decimals=24;String contract=null;
        if(!symbol.equals("NEAR")){var token=new TokenConfigRepository(jdbc).findDefinition("NEAR","testnet",symbol).orElseThrow();contract=token.getContractAddress();decimals=token.getDecimals();}
        return new NearSigningPayload(symbol,BigDecimal.ONE,op.kind()==Kind.COLLECTION?BigDecimal.ZERO:new BigDecimal("0.1"),decimals,
                new MpcNearTransactionCodec.Unsigned("testnet",GENESIS,source.getAddress(),7,recipient,BigDecimal.ONE.movePointRight(decimals).toBigIntegerExact(),contract,contract==null?0:30_000_000_000_000L,contract==null?null:op.reference(),100,200,GENESIS,service.publicKey(source)));
    }
    private MpcNearTransactionCodec.Signed prepared(NearOperation op,String symbol){var request=service.reserve(op,source,draft(op,symbol));service.sign(op,request.id());return service.commit(op,request.id());}
    private NearExecutionReceipt.Proof receipt(NearOperation op,MpcNearTransactionCodec.Signed signed,boolean paid,long fee){return NearReceiptTestSupport.payment(service.payload(service.intent(op).orElseThrow()).unsigned(),signed,paid,fee);}
    @Test void nonceAndIntentCommitTogetherBeforeMpcAndSelectedWireBeforeNetwork(){
        var request=service.reserve(op(),source,draft(op(),"NEAR"));String digest=MpcNearTransactionCodec.transactionHash(service.payload(request).unsigned());
        assertEquals("SIGNING",transactions.find(digest).orElseThrow().getStatus());assertNull(transactions.find(digest).orElseThrow().getRawPayload());
        assertNull(orders.find("NEAR",REFERENCE,TENANT).orElseThrow().getTxHash());
        service.sign(op(),request.id());var signed=service.commit(op(),request.id());
        assertEquals(signed.txHash(),orders.findTxHash("NEAR",REFERENCE,TENANT).orElseThrow());
        assertEquals(json.writeValueAsString(signed),transactions.find(digest).orElseThrow().getRawPayload());
        assertEquals(request.id(),service.reserve(op(),source,null).id());assertEquals(signed,service.commit(op(),request.id()));
        assertEquals(0,service.releaseUnsigned(op()));
    }
    @Test void intentInsertFailureRollsBackNonceAndChainAdmission(){
        jdbc.execute("create function public.near_test_reject_intent() returns trigger language plpgsql as $$ begin raise exception 'reject intent';end $$;create trigger near_test_reject_intent before insert on wallet_mpc_sign_request for each row execute function public.near_test_reject_intent()");
        try{assertThrows(RuntimeException.class,()->service.reserve(op(),source,draft(op(),"NEAR")));assertEquals(0,jdbc.queryForObject("select count(*) from near_transaction",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='NEAR'",Integer.class));}
        finally{jdbc.execute("drop trigger near_test_reject_intent on wallet_mpc_sign_request;drop function public.near_test_reject_intent()");}
    }
    @Test void hashBindingFailureRetainsIntentAndRollsBackSelectedWire(){
        var request=service.reserve(op(),source,draft(op(),"NEAR"));service.sign(op(),request.id());String digest=MpcNearTransactionCodec.transactionHash(service.payload(request).unsigned());
        jdbc.execute("create function public.near_test_reject_hash() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject hash';end if;return new;end $$;create trigger near_test_reject_hash before update on withdrawal_order for each row execute function public.near_test_reject_hash()");
        try{assertThrows(RuntimeException.class,()->service.commit(op(),request.id()));assertNull(transactions.find(digest).orElseThrow().getRawPayload());assertEquals("SIGNING",transactions.find(digest).orElseThrow().getStatus());assertEquals(MpcSignRequest.State.COMPLETED,service.intent(op()).orElseThrow().state());}
        finally{jdbc.execute("drop trigger near_test_reject_hash on withdrawal_order;drop function public.near_test_reject_hash()");}
        assertNotNull(service.commit(op(),request.id()));
    }
    @Test void anotherAssetCannotReserveNonceWhileTheSenderHasAnUnfinishedIntent(){
        service.reserve(op(),source,draft(op(),"NEAR"));createOrder("TOKEN","USDC");
        assertThrows(RuntimeException.class,()->service.reserve(op("TOKEN"),source,draft(op("TOKEN"),"USDC")));
        assertTrue(service.intent(op("TOKEN")).isEmpty());assertEquals(1,jdbc.queryForObject("select count(*) from near_transaction",Integer.class));
    }
    @Test void concurrentBusinessReservationsAdmitOnlyOneSenderIntent()throws Exception{
        createOrder("OTHER","NEAR");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->reserveResult(op()));var second=executor.submit(()->reserveResult(op("OTHER")));
            assertEquals(1,first.get(20,TimeUnit.SECONDS)+second.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from near_transaction",Integer.class));
    }
    private int reserveResult(NearOperation op){try{service.reserve(op,source,draft(op,"NEAR"));return 1;}catch(IllegalStateException expected){return 0;}}
    @Test void laterBusinessReservesANewNonceEvenIfTheRpcObservationIsStale(){
        var signed=prepared(op(),"NEAR");service.restoreSent(op(),signed.txHash());assertTrue(service.settle(op(),receipt(op(),signed,true,1_500_000),123,1));
        createOrder("NEXT","NEAR");var request=service.reserve(op("NEXT"),source,draft(op("NEXT"),"NEAR"));
        assertEquals(8,service.payload(request).unsigned().nonce());assertNotEquals(signed.txHash(),MpcNearTransactionCodec.transactionHash(service.payload(request).unsigned()));
    }
    @Test void exactPaymentAndActualExpenseSettleOnceAfterPersistedFinalBlockDepth(){
        var signed=prepared(op(),"NEAR");service.restoreSent(op(),signed.txHash());
        assertFalse(service.settle(op(),receipt(op(),signed,true,1_914_600),123,2));assertTrue(service.proof(op()).isPresent());
        assertTrue(service.settle(op(),null,124,2));assertFalse(service.settle(op(),null,124,2));
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"NEAR","NEAR",ACCOUNT).orElseThrow();
        assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("3.9").compareTo(balance.getTotalBalance()));
        assertEquals(0,new BigDecimal("1914600").compareTo(transactions.findConfirmedFeeAtomic("NEAR",signed.txHash()).orElseThrow()));
        service.observed(op(),signed.txHash());assertEquals("CONFIRMED",transactions.find(signed.txHash()).orElseThrow().getStatus());
    }
    @Test void committedFailureRefundsOnceAndRetainsItsActualFee(){
        var signed=prepared(op(),"NEAR");service.restoreSent(op(),signed.txHash());
        assertTrue(service.settle(op(),receipt(op(),signed,false,1_000_000),123,1));assertFalse(service.settle(op(),null,123,1));
        assertEquals("FAILED",orders.find("NEAR",REFERENCE,TENANT).orElseThrow().getStatus());
        assertEquals(0,new BigDecimal("5").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"NEAR","NEAR",ACCOUNT).orElseThrow().getTotalBalance()));
        assertEquals(0,new BigDecimal("1000000").compareTo(transactions.findConfirmedFeeAtomic("NEAR",signed.txHash()).orElseThrow()));
    }
    @Test void tokenSuccessRetainsOneYoctoButFinalTokenFailureRefundsIt(){
        createOrder("FT-PAID","USDC");var paid=op("FT-PAID");var selected=prepared(paid,"USDC");service.restoreSent(paid,selected.txHash());
        assertTrue(service.settle(paid,receipt(paid,selected,true,1_500_000),123,1));
        assertEquals(0,new BigDecimal("1500001").compareTo(transactions.findConfirmedFeeAtomic("NEAR",selected.txHash()).orElseThrow()));
        createOrder("FT-FAILED","USDC");var failed=op("FT-FAILED");var wire=prepared(failed,"USDC");service.restoreSent(failed,wire.txHash());
        assertTrue(service.settle(failed,receipt(failed,wire,false,1_500_000),123,1));
        assertEquals(0,new BigDecimal("1500000").compareTo(transactions.findConfirmedFeeAtomic("NEAR",wire.txHash()).orElseThrow()));
        assertEquals(0,new BigDecimal("3.9").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"NEAR","USDC",ACCOUNT).orElseThrow().getTotalBalance()));
    }
    @Test void changedCustomerFeeAndAbsentOriginalProofCannotSettleFrozenPrincipal(){
        var signed=prepared(op(),"NEAR");service.restoreSent(op(),signed.txHash());
        assertThrows(RuntimeException.class,()->service.settle(op(),new NearExecutionReceipt.Proof(json.createObjectNode(),Map.of()),123,1));
        jdbc.update("update withdrawal_order set fee=0.2 where tenant_id=? and order_no=?",TENANT,REFERENCE);
        assertThrows(RuntimeException.class,()->service.settle(op(),receipt(op(),signed,true,1_500_000),123,1));
        assertEquals("SENT",orders.find("NEAR",REFERENCE,TENANT).orElseThrow().getStatus());assertTrue(service.proof(op()).isEmpty());
    }
    @Test void ledgerFailureRollsBackProofAndTerminalExpense(){
        var signed=prepared(op(),"NEAR");service.restoreSent(op(),signed.txHash());var proof=receipt(op(),signed,true,1_500_000);
        jdbc.update("update ledger_balance set locked_balance=0,available_balance=5 where chain='NEAR' and account_id=?",ACCOUNT);
        assertThrows(RuntimeException.class,()->service.settle(op(),proof,123,1));assertTrue(service.proof(op()).isEmpty());assertEquals("SENT",transactions.find(signed.txHash()).orElseThrow().getStatus());
        jdbc.update("update ledger_balance set locked_balance=1.1,available_balance=3.9 where chain='NEAR' and account_id=?",ACCOUNT);
        assertTrue(service.settle(op(),proof,123,1));
    }
    @Test void existingTokenIntentSurvivesAssetDisableWithoutSelectingNewNonce(){
        createOrder("TOKEN","USDC");var op=op("TOKEN");var request=service.reserve(op,source,draft(op,"USDC"));
        jdbc.update("update token_config set enabled=false where chain='NEAR'");
        assertEquals(request.id(),service.reserve(op,source,null).id());service.sign(op,request.id());assertNotNull(service.commit(op,request.id()));
    }
    @Test void lostReplyRecoversOriginalNonceAndWireAfterNewTasksAreDisabled(){
        var rpc=mock(NearRpcClient.class);when(rpc.forNetwork("testnet")).thenReturn(rpc);when(rpc.status()).thenReturn(json.createObjectNode().put("chain_id","testnet").put("genesis_hash",GENESIS));when(rpc.genesisConfig()).thenReturn(json.createObjectNode().put("chain_id","testnet").put("transaction_validity_period",100));
        when(rpc.accessKey(anyString(),anyString())).thenReturn(json.createObjectNode().put("permission","FullAccess").put("nonce",6).put("block_height",100).put("block_hash",GENESIS));
        var block=json.createObjectNode();block.putObject("header").put("hash",GENESIS).put("height",100);when(rpc.block(anyString())).thenReturn(block);when(rpc.latestFinalBlockHeight()).thenReturn(100L);
        var runtime=mock(WalletRuntimeConfigService.class);var transfers=new NearTransactionService(mock(com.surprising.wallet.service.MpcAuthorizationService.class),rpc,service,new ChainProfileRepository(jdbc),new TokenConfigRepository(jdbc),orders,new CollectionRecordRepository(jdbc),mock(NearRegistrationService.class),runtime,permissiveBroadcastFence());
        var accepted=new java.util.concurrent.atomic.AtomicBoolean();
        when(rpc.knownTransaction(anyString(),anyString())).thenAnswer(call->{if(!accepted.get())throw new IllegalStateException("UNKNOWN_TRANSACTION");var value=json.createObjectNode();value.putObject("transaction").put("hash",call.getArgument(0,String.class));return value;});
        when(rpc.sendTransaction(anyString())).thenAnswer(call->{
            String hash=orders.findTxHash("NEAR",REFERENCE,TENANT).orElseThrow();var wire=json.readValue(transactions.find(hash).orElseThrow().getRawPayload(),MpcNearTransactionCodec.Signed.class);
            assertEquals(wire.signedTransactionBase64(),call.getArgument(0));accepted.set(true);throw new IllegalStateException("lost reply");
        });
        assertThrows(IllegalStateException.class,()->transfers.send(op(),source));service.unknown(op(),"lost reply");
        doThrow(new IllegalStateException("disabled")).when(runtime).requireTaskEnabled(anyString(),anyString(),anyString());
        assertTrue(transfers.recover(op()));verify(rpc,times(1)).sendTransaction(anyString());verify(rpc,times(1)).accessKey(anyString(),anyString());
        assertEquals("SENT",orders.find("NEAR",REFERENCE,TENANT).orElseThrow().getStatus());assertEquals(7,service.payload(service.intent(op()).orElseThrow()).unsigned().nonce());
    }
    @Test void expiredUnknownIntentCannotReplaceNonceOrReleasePrincipal(){
        var signed=prepared(op(),"NEAR");service.unknown(op(),"offline before response");
        var rpc=mock(NearRpcClient.class);when(rpc.forNetwork("testnet")).thenReturn(rpc);when(rpc.status()).thenReturn(json.createObjectNode().put("chain_id","testnet").put("genesis_hash",GENESIS));when(rpc.knownTransaction(anyString(),anyString())).thenThrow(new IllegalStateException("UNKNOWN_TRANSACTION"));when(rpc.latestFinalBlockHeight()).thenReturn(201L);
        var transfers=new NearTransactionService(mock(com.surprising.wallet.service.MpcAuthorizationService.class),rpc,service,new ChainProfileRepository(jdbc),new TokenConfigRepository(jdbc),orders,new CollectionRecordRepository(jdbc),mock(NearRegistrationService.class),mock(WalletRuntimeConfigService.class),permissiveBroadcastFence());
        assertThrows(IllegalStateException.class,()->transfers.recover(op()));verify(rpc,never()).sendTransaction(anyString());assertEquals(0,service.releaseUnsigned(op()));assertEquals(signed.txHash(),orders.findTxHash("NEAR",REFERENCE,TENANT).orElseThrow());
        assertEquals(0,new BigDecimal("1.1").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"NEAR","NEAR",ACCOUNT).orElseThrow().getLockedBalance()));
    }
    @Test void unsignedBusinessCanReturnToQueueButOwnedIntentCannotBeReleased(){
        service.unknown(op(),"worker stopped before intent");
        assertEquals(1,service.releaseUnsigned(op()));assertEquals("FROZEN",orders.find("NEAR",REFERENCE,TENANT).orElseThrow().getStatus());
        orders.claimSigningDurable(TENANT,"NEAR",REFERENCE,source.getAddress());service.reserve(op(),source,draft(op(),"NEAR"));assertEquals(0,service.releaseUnsigned(op()));
    }
    @Test void collectionRequiresAnOwnedCollectorAndRetainsActualExpense(){
        var collections=new CollectionRecordRepository(jdbc);UUID custodyId=UUID.randomUUID();
        new CustodyAddressRepository(jdbc).insert(custodyId,TENANT,source.getId(),"NEAR","testnet",source.getAddress(),null,"near-source",null,"{}","CONSOLE",42,0,1,null);
        String reference="NEAR-COLLECTION";collections.create(TENANT,custodyId,reference,"NEAR","NEAR",source.getAddress(),recipient,BigDecimal.ONE,BigDecimal.ZERO,null);
        collections.claimSigningDurable(TENANT,"NEAR",reference);var operation=new NearOperation(TENANT,reference,Kind.COLLECTION);
        assertThrows(RuntimeException.class,()->service.reserve(operation,source,draft(operation,"NEAR")));
        var collector=fixture.address(jdbc,TENANT,ChainType.NEAR,0,2,"HOT");recipient=collector.getAddress();
        jdbc.update("update collection_record set to_address=? where tenant_id=? and collection_no=?",recipient,TENANT,reference);
        var signed=prepared(operation,"NEAR");service.restoreSent(operation,signed.txHash());
        assertTrue(service.settle(operation,receipt(operation,signed,true,936_480),123,1));
        service.unknown(operation,"late error");assertEquals("CONFIRMED",collections.findStatus(TENANT,"NEAR",reference).orElseThrow());
        assertEquals(0,new BigDecimal("0.000000000000000000936480").compareTo(jdbc.queryForObject("select fee from collection_record where tenant_id=? and collection_no=?",BigDecimal.class,TENANT,reference)));
    }
    @Test void concurrentConfirmationsDebitTheFrozenPrincipalExactlyOnce()throws Exception {
        var signed=prepared(op(),"NEAR");service.restoreSent(op(),signed.txHash());var proof=receipt(op(),signed,true,1_500_000);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var results=new ArrayList<Future<Boolean>>();for(int i=0;i<12;i++)results.add(executor.submit(()->service.settle(op(),proof,123,1)));
            int changes=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))changes++;assertEquals(1,changes);
        }
        assertEquals(0,new BigDecimal("3.9").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"NEAR","NEAR",ACCOUNT).orElseThrow().getTotalBalance()));
    }
}
