package com.surprising.wallet.custody;

import com.surprising.wallet.common.signing.MoneroSigningPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.monero.MoneroSignedPayment;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.MoneroSubmissionService.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PG18 money/ownership tests. Public signature selections are explicit substitutes, not native SIGN acceptance. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class MoneroSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String GENESIS="11".repeat(32),HASH="22".repeat(32),REFERENCE="XMR-NATIVE-TEST",ACCOUNT="monero-principal";
    private static final BigInteger CAP=BigInteger.valueOf(3_000_000_000L),FEE=BigInteger.valueOf(1_000_000_000L);
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private MpcSigningService signing;
    private ChainAddressRecord source,target;
    private MoneroSubmissionService service;
    private MoneroTransactionRepository transactions;
    private WithdrawalOrderRepository orders;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=(network='regtest'),withdraw_confirmations=3 where chain='XMR'");
        jdbc.update("update chain_asset set active=true,decimals=12 where chain='XMR' and symbol='XMR'");
        fixture=new MpcSigningTestSupport(jdbc);signing=fixture.signing;source=publish(42,1);target=publish(43,1);
        var heights=new ChainScanHeightRepository(jdbc);heights.initializeNative("XMR",MoneroScanAnchorService.SCANNER,GENESIS,100);
        orders=new WithdrawalOrderRepository(jdbc);transactions=new MoneroTransactionRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(CustodyHotWalletWithdrawalReservationRepository.class,()->new CustodyHotWalletWithdrawalReservationRepository(jdbc));
        context.registerBean(HotWalletReservationLifecycleService.class);
        context.registerBean(WithdrawalSettlementService.class);context.refresh();
        service=new MoneroSubmissionService(orders,new CollectionRecordRepository(jdbc),transactions,new WalletMpcSignRequestRepository(jdbc),
                new WalletMpcKeyEpochRepository(jdbc,json),signing,fixture.nodes,new ChainProfileRepository(jdbc),new ChainAddressRepository(jdbc),
                heights,new ChainAssetRepository(jdbc),context.getBean(WithdrawalSettlementService.class),CustodyTestSupport.gasAccounting(jdbc),json,fixture.manager,
                new HotWalletBroadcastFence(new CustodyTenantChainTableRepository(jdbc),new CustodyHotWalletRepository(jdbc),
                        new CustodyAddressRepository(jdbc),new ChainAddressRepository(jdbc),new CollectionRecordRepository(jdbc),
                        new CustodyHotWalletInventoryObservationRepository(jdbc),fixture.manager));
        createOrder(REFERENCE);
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private Operation op(){return new Operation(TENANT,REFERENCE,Kind.WITHDRAWAL);}
    private ChainAddressRecord publish(long user,long index) {
        int biz=new CustodyTenantTableRepository(jdbc).findById(TENANT).orElseThrow().derivationNamespace();
        var key=fixture.signing.awaitMoneroKey(TENANT,user,biz,index,"regtest",GENESIS,fixture.nodes);
        String address=MoneroPublicAddressCodec.encode(HexFormat.of().parseHex(key.publicKeyHex()),"regtest",true);
        var row=new ChainAddressRepository(jdbc).publishMpc(ChainAddressRecord.builder().tenantId(TENANT).mpcKeyId(key.id()).chain("XMR").assetSymbol("XMR")
                .userId(user).biz(biz).addressIndex(index).address(address).ownerAddress(address).accountId(address).walletRole("DEPOSIT").derivationPath("mpc:"+key.id()).enabled(true).build());
        DepositTestSupport.attachCustodyAddress(jdbc,TENANT,"XMR","XMR",address);return row;
    }
    private void createOrder(String reference) {
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'XMR','XMR',?,5,5) on conflict(chain,asset_symbol,account_id) do nothing",TENANT,ACCOUNT);
        orders.createForTenant(TENANT,reference,42,"XMR","XMR",source.getAddress(),ACCOUNT,target.getAddress(),BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("XMR","XMR",ACCOUNT,new BigDecimal("1.1"),TENANT));
        orders.updateStatus(TENANT,"XMR",reference,"FROZEN",source.getAddress(),null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"XMR",reference,source.getAddress()));
    }
    private MoneroSigningPayload draft(){return new MoneroSigningPayload("regtest",GENESIS,source.getAddress(),target.getAddress(),BigDecimal.ONE,new BigDecimal("0.1"),CAP,3);}
    private MoneroSignedPayment selection(MpcSignRequest request,String hash) {
        var selected=new MoneroSignedPayment(request.currentAttemptId(),"33".repeat(32),fixture.nodes.online(),hash,FEE,"44".repeat(32));
        doReturn(selected).when(signing).selectedMonero(TENANT,request.id());return selected;
    }
    private MpcSignRequest prepared() {
        var request=service.reserve(op(),source,draft());selection(request,HASH);service.commit(op(),request.id());return request;
    }
    private BigDecimal balance(String column,String account) {
        return jdbc.queryForObject("select "+column+" from ledger_balance where tenant_id=? and chain='XMR' and asset_symbol='XMR' and account_id=?",BigDecimal.class,TENANT,account);
    }
    private void equalsAmount(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    private MpcSigningService.MoneroSnapshot proof(MpcSignRequest payment,int depth,boolean inPool,List<String> parties) throws Exception {
        var p=service.payload(payment);long height=99+depth;
        var policy=json.createObjectNode().put("kind","SNAPSHOT").put("network",p.network()).put("genesisHash",p.genesisHash())
                .put("sender",p.sender()).put("fromHeight",height).put("height",height).put("blockHash","55".repeat(32)).put("transactionHash",transactions.find(TENANT,payment.id()).orElseThrow().txHash());
        var request=new MpcClient.Request(UUID.randomUUID(),TENANT,payment.command().keyId(),1,MpcClient.Operation.MONERO_OBSERVE,
                MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,parties,HexFormat.of().formatHex(json.writeValueAsBytes(policy)));
        Map<String,Object> canonical=json.readValue(json.writeValueAsBytes(request),json.getTypeFactory().constructMapType(TreeMap.class,String.class,Object.class));
        String intent=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
        var facts=json.createObjectNode().put("kind","SNAPSHOT").put("address",p.sender()).put("network",p.network()).put("genesisHash",p.genesisHash())
                .put("anchorHeight",height).put("anchorHash","55".repeat(32)).put("viewHeight",height).put("viewHash","55".repeat(32))
                .put("accountIndex",1).put("balanceAtomic","1000000000000").put("unlockedBalanceAtomic","1000000000000");
        facts.putArray("outputs");facts.putObject("transaction").put("transactionHash",policy.path("transactionHash").asText()).put("known",true).put("inPool",inPool)
                .put("doubleSpendSeen",false).put("coinbase",false).put("unlockTime",0).put("blockHeight",100).put("blockHash","66".repeat(32)).put("confirmations",depth);
        return new MpcSigningService.MoneroSnapshot(request,new MpcClient.Result(MpcClient.State.COMPLETED,request.sessionId(),TENANT,request.keyId(),1,intent,
                fixture.signing.requireKey(TENANT,request.keyId()).publicKeyHex(),null,p.sender(),facts));
    }
    private MpcSigningService.MoneroSnapshot proof(MpcSignRequest payment,int depth) throws Exception{return proof(payment,depth,false,fixture.nodes.online());}

    @Test void admissionIsAtomicAndAnAcceptedUnknownIntentCannotBeReleasedOrReplaced() {
        var request=service.reserve(op(),source,draft());
        assertNull(transactions.find(TENANT,request.id()).orElseThrow().txHash());
        assertEquals(request.id(),service.reserve(op(),source,null).id());
        assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt where request_id=?",Integer.class,request.id()));
        service.unknown(op(),"native signing response unknown");assertEquals(0,service.releaseUnsigned(op()));
        equalsAmount("1.1",balance("locked_balance",ACCOUNT));verify(signing,never()).awaitSignature(any(),any(),any());
    }
    @Test void failedAdmissionRollsBackIntentAndAllowsOnlyAnUnsignedRetry() {
        jdbc.execute("create function monero_test_reject() returns trigger language plpgsql as $$ begin raise exception 'reject admission';end $$;create trigger monero_test_reject before insert on monero_transaction for each row execute function monero_test_reject()");
        try{assertThrows(RuntimeException.class,()->service.reserve(op(),source,draft()));assertTrue(service.intent(op()).isEmpty());}
        finally{jdbc.execute("drop trigger monero_test_reject on monero_transaction;drop function monero_test_reject()");}
        service.unknown(op(),"unadmitted");assertEquals(1,service.releaseUnsigned(op()));equalsAmount("1.1",balance("locked_balance",ACCOUNT));
    }
    @Test void businessHashBindingFailureRollsBackSelectionAndRetryUsesTheOriginalAttempt() {
        var request=service.reserve(op(),source,draft());var selected=selection(request,HASH);
        jdbc.execute("create function monero_test_reject() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject binding';end if;return new;end $$;create trigger monero_test_reject before update on withdrawal_order for each row execute function monero_test_reject()");
        try{assertThrows(RuntimeException.class,()->service.commit(op(),request.id()));assertNull(service.stored(op()).orElseThrow().txHash());}
        finally{jdbc.execute("drop trigger monero_test_reject on withdrawal_order;drop function monero_test_reject()");}
        assertEquals(selected,service.commit(op(),request.id()));assertEquals(selected,service.commit(op(),request.id()));
        assertEquals(HASH,orders.findTxHash("XMR",REFERENCE,TENANT).orElseThrow());
        assertEquals(request.currentAttemptId(),service.stored(op()).orElseThrow().selectedAttemptId());
    }
    @Test void relayRequiresCommittedOriginalSelectionAndNeverRunsInsideATransaction() {
        var request=service.reserve(op(),source,draft());selection(request,HASH);
        assertThrows(IllegalStateException.class,()->service.relay(op()));verify(signing,never()).relayMonero(any(),any(),anyString(),any(),any());
        service.commit(op(),request.id());
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(HASH,orders.findTxHash("XMR",REFERENCE,TENANT).orElseThrow());throw new MpcPendingException(request.id(),"RELAY_UNKNOWN");
        }).when(signing).relayMonero(eq(TENANT),eq(request.id()),eq(HASH),eq(fixture.nodes),any());
        assertThrows(MpcPendingException.class,()->service.relay(op()));service.unknown(op(),"lost reply");assertEquals(0,service.releaseUnsigned(op()));
        jdbc.update("update chain_profile set enabled=false,withdraw_confirmations=99 where chain='XMR'");
        jdbc.update("update chain_address set enabled=false where chain='XMR'");service.restoreSent(op(),HASH);
        assertEquals("SENT",orders.find("XMR",REFERENCE,TENANT).orElseThrow().getStatus());assertEquals(3,service.payload(service.intent(op()).orElseThrow()).requiredConfirmations());
    }
    @Test void insufficientDepthAndPoolFactsCannotSettleFrozenPrincipal() throws Exception {
        var request=prepared();assertFalse(service.settle(op(),proof(request,2)));assertFalse(service.settle(op(),proof(request,3,true,fixture.nodes.online())));
        assertEquals("PREPARED",service.stored(op()).orElseThrow().status());equalsAmount("1.1",balance("locked_balance",ACCOUNT));
    }
    @Test void canonicalReceiptAndPrincipalSettleOnceUsingOriginalPolicyWhileDisabled() throws Exception {
        var request=prepared();service.unknown(op(),"lost reply");jdbc.update("update chain_profile set enabled=false,withdraw_confirmations=99 where chain='XMR'");
        assertTrue(service.settle(op(),proof(request,3)));assertFalse(service.settle(op(),proof(request,3)));
        assertEquals("CONFIRMED",orders.find("XMR",REFERENCE,TENANT).orElseThrow().getStatus());equalsAmount("0",balance("locked_balance",ACCOUNT));equalsAmount("3.9",balance("total_balance",ACCOUNT));
        assertNotNull(service.stored(op()).orElseThrow().executionReceipt());assertTrue(service.pending().isEmpty());
    }
    @Test void failedPrincipalSettlementRollsBackCanonicalReceiptAndBalanceTogether() throws Exception {
        var request=prepared();var proof=proof(request,3);
        jdbc.execute("create function monero_test_reject() returns trigger language plpgsql as $$ begin if new.status='CONFIRMED' then raise exception 'reject confirmation';end if;return new;end $$;create trigger monero_test_reject before update on withdrawal_order for each row execute function monero_test_reject()");
        try{assertThrows(RuntimeException.class,()->service.settle(op(),proof));assertNull(service.stored(op()).orElseThrow().executionReceipt());equalsAmount("1.1",balance("locked_balance",ACCOUNT));}
        finally{jdbc.execute("drop trigger monero_test_reject on withdrawal_order;drop function monero_test_reject()");}
        assertTrue(service.settle(op(),proof));equalsAmount("3.9",balance("total_balance",ACCOUNT));
    }
    @Test void unregisteredObservationHoldersCannotReleasePrincipal() throws Exception {
        var request=prepared();var proof=proof(request,3,false,List.of(fixture.nodes.online().getFirst(),"ff".repeat(32)));
        assertThrows(IllegalStateException.class,()->service.settle(op(),proof));equalsAmount("1.1",balance("locked_balance",ACCOUNT));assertFalse(service.terminal(op()));
    }
    @Test void anotherPendingPaymentCannotUseTheSameSource() {
        service.reserve(op(),source,draft());createOrder("XMR-SECOND");var second=new Operation(TENANT,"XMR-SECOND",Kind.WITHDRAWAL);
        assertThrows(IllegalStateException.class,()->service.reserve(second,source,draft()));assertTrue(service.intent(second).isEmpty());
    }
    @Test void admissionRejectsChangedBusinessOrWeakerConfirmationPolicy() {
        var p=draft();assertThrows(IllegalStateException.class,()->service.reserve(op(),source,new MoneroSigningPayload(p.network(),p.genesisHash(),p.sender(),p.recipient(),new BigDecimal("1.1"),p.customerFee(),CAP,3)));
        assertThrows(IllegalStateException.class,()->service.reserve(op(),source,new MoneroSigningPayload(p.network(),p.genesisHash(),p.sender(),p.recipient(),p.amount(),p.customerFee(),CAP,1)));
        assertTrue(service.intent(op()).isEmpty());
        assertThrows(RuntimeException.class,()->service.reserve(new Operation(UUID.randomUUID(),REFERENCE,Kind.WITHDRAWAL),source,p));
    }
    @Test void databaseFreezesOriginalSelectionAndOwnership() {
        var request=prepared();
        assertThrows(RuntimeException.class,()->jdbc.update("update monero_transaction set artifact_hash=? where request_id=?","99".repeat(32),request.id()));
        assertThrows(RuntimeException.class,()->jdbc.update("update monero_transaction set fee_atomic=0 where request_id=?",request.id()));
        assertThrows(RuntimeException.class,()->jdbc.update("update monero_transaction set request_id=? where request_id=?",UUID.randomUUID(),request.id()));
        assertEquals(HASH,service.stored(op()).orElseThrow().txHash());
    }
    @Test void collectionReceiptsSuppressInternalCustomerCreditAndRecordActualFee() throws Exception {
        var request=prepared();assertTrue(service.customerTransfer(TENANT,HASH,source.getAddress(),target.getAddress()));assertTrue(service.settle(op(),proof(request,3)));
        var collections=new CollectionRecordRepository(jdbc);var custodyId=jdbc.queryForObject("select id from custody_address where chain_address_id=?",UUID.class,source.getId());
        collections.create(TENANT,custodyId,"XMR-COLLECTION","XMR","XMR",source.getAddress(),target.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null);
        assertEquals(1,collections.claimSigningDurable(TENANT,"XMR","XMR-COLLECTION"));var op=new Operation(TENANT,"XMR-COLLECTION",Kind.COLLECTION);
        var draft=new MoneroSigningPayload("regtest",GENESIS,source.getAddress(),target.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,CAP,3);
        var collection=service.reserve(op,source,draft);selection(collection,"77".repeat(32));service.commit(op,collection.id());
        assertFalse(service.customerTransfer(TENANT,"77".repeat(32),source.getAddress(),target.getAddress()));assertTrue(service.settle(op,proof(collection,3)));
        equalsAmount("0.001",collections.lockForSubmission(TENANT,"XMR","XMR-COLLECTION").orElseThrow().getFee());
        assertThrows(IllegalStateException.class,()->service.customerTransfer(TENANT,"88".repeat(32),source.getAddress(),target.getAddress()));
    }
    @Test void gasFundingReservesOnceAndExpensesOnlyActualFeeKeepingInternalPrincipal() throws Exception {
        var gas=publish(44,1);var custodyId=jdbc.queryForObject("select id from custody_address where chain_address_id=?",UUID.class,gas.getId());
        jdbc.update("insert into custody_gas_account(id,tenant_id,custody_address_id,chain,network,native_symbol,low_balance_threshold,status) values (?,?,?,'XMR','regtest','XMR',0,'ACTIVE')",UUID.randomUUID(),TENANT,custodyId);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'XMR','XMR',?,5,5)",TENANT,gas.getAccountId());
        var op=new Operation(TENANT,"xmr-gas:"+"ab".repeat(32),Kind.FUNDING);
        var draft=new MoneroSigningPayload("regtest",GENESIS,gas.getAddress(),source.getAddress(),new BigDecimal("0.003"),BigDecimal.ZERO,CAP,3);
        var request=service.reserve(op,gas,draft);assertEquals(request.id(),service.reserve(op,gas,null).id());equalsAmount("0.006",balance("locked_balance",gas.getAccountId()));
        selection(request,"99".repeat(32));service.commit(op,request.id());assertFalse(service.customerTransfer(TENANT,"99".repeat(32),gas.getAddress(),source.getAddress()));
        assertTrue(service.settle(op,proof(request,3)));assertFalse(service.settle(op,proof(request,3)));equalsAmount("0",balance("locked_balance",gas.getAccountId()));
        equalsAmount("4.999",balance("total_balance",gas.getAccountId()));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_ledger_entry where reference_type='ADDRESS_PREPARATION'",Integer.class));
    }

    private List<MpcClient> unknownClients(MpcClient.State state) {
        var clients=new ArrayList<MpcClient>();
        for(String party:fixture.nodes.online()) {
            var client=mock(MpcClient.class);
            when(client.node()).thenReturn(new MpcClient.Node(party,java.net.URI.create("https://localhost:1")));
            when(client.result(any())).thenAnswer(call->{var r=call.getArgument(0,MpcClient.Request.class);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if(r.algorithm()==MpcClient.Algorithm.MONERO_NATIVE_2_OF_3)
                    assertNotEquals(MpcSignAttempt.State.PENDING,new WalletMpcSignAttemptRepository(jdbc,json).find(TENANT,r.sessionId()).orElseThrow().state(),"native dispatch fence was not committed before RPC");
                return new MpcClient.Result(state,r.sessionId(),r.tenantId(),r.keyId(),r.epoch(),null,null,null,null,null);});
            when(client.submit(any())).thenAnswer(call->{var r=call.getArgument(0,MpcClient.Request.class);return new MpcClient.Result(MpcClient.State.RUNNING,r.sessionId(),r.tenantId(),r.keyId(),r.epoch(),null,null,null,null,null);});
            clients.add(client);
        }
        return clients;
    }
    @ParameterizedTest @EnumSource(value=MpcClient.State.class,names={"INTERRUPTED","INDETERMINATE"})
    void originalMoneroAttemptWithoutIndependentAdmissionCannotReachAnyHolder(MpcClient.State state) {
        var request=service.reserve(op(),source,draft());assertFalse(signing.hasStartedMoneroSignature(TENANT,request.id()));
        var attempts=new WalletMpcSignAttemptRepository(jdbc,json);
        var original=attempts.find(TENANT,request.currentAttemptId()).orElseThrow().request();var clients=unknownClients(state);
        assertThrows(MpcPendingException.class,()->signing.advanceSignature(TENANT,request.id(),clients));
        for(var client:clients)verifyNoInteractions(client);
        assertFalse(signing.hasStartedMoneroSignature(TENANT,request.id()));assertEquals(1,attempts.history(TENANT,request.id()).size());
        assertEquals(original,attempts.find(TENANT,request.currentAttemptId()).orElseThrow().request());
        assertEquals(0,service.releaseUnsigned(op()));equalsAmount("1.1",balance("locked_balance",ACCOUNT));
    }
    @ParameterizedTest @EnumSource(value=MpcClient.State.class,names={"INTERRUPTED","INDETERMINATE"})
    void automaticAttemptWithoutIndependentOperatingProfileCannotReachAnyHolder(MpcClient.State state) {
        var owner=fixture.address(jdbc,TENANT,ChainType.ETH,46,1,"DEPOSIT");var key=signing.requireKey(TENANT,owner.getMpcKeyId());
        var request=new org.springframework.transaction.support.TransactionTemplate(fixture.manager).execute(status->signing.reserveSignature(new MpcSignCommand(
                TENANT,key.id(),key.activeEpoch(),ChainType.ETH,MpcSignCommand.BusinessType.DEV_FAUCET,"unknown-ecdsa","transaction",MpcSignCommand.Purpose.TRANSACTION,"TEST_JSON","00","11".repeat(32)),fixture.nodes.online()));
        var clients=unknownClients(state);assertThrows(IllegalStateException.class,()->signing.advanceSignature(TENANT,request.id(),clients));
        for(var client:clients)verifyNoInteractions(client);
        assertEquals(1,new WalletMpcSignAttemptRepository(jdbc,json).history(TENANT,request.id()).size());
    }
    @Test void anObservedNativeAttemptNeverPlansAnotherGasSupplementBeforeRecoveringItsMaterials() {
        var request=service.reserve(op(),source,draft());new WalletMpcSignAttemptRepository(jdbc,json).observe(TENANT,request.currentAttemptId(),MpcSignAttempt.State.INDETERMINATE,Map.of());
        var daemon=mock(com.surprising.wallet.chain.monero.MoneroDaemonClient.class);var profiles=new ChainProfileRepository(jdbc);
        var workflow=new com.surprising.wallet.chain.monero.MoneroTransactionService(daemon,service,signing,fixture.nodes,profiles,new ChainAddressRepository(jdbc),
                new CollectionRecordRepository(jdbc),CustodyTestSupport.gas(jdbc),new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc)),mock(com.surprising.wallet.service.MpcAuthorizationService.class));
        doThrow(new MpcPendingException(request.id(),"INDETERMINATE")).when(signing).awaitSignature(TENANT,request.id(),fixture.nodes);
        assertThrows(MpcPendingException.class,()->workflow.recover(op()));verifyNoInteractions(daemon);
        assertEquals(1,jdbc.queryForObject("select count(*) from monero_transaction where tenant_id=?",Integer.class,TENANT));equalsAmount("1.1",balance("locked_balance",ACCOUNT));
    }
}
