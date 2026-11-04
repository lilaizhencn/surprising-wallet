package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.hypercore.MpcHyperCoreActionCodec;

import com.surprising.wallet.chain.hypercore.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.MpcSigningTestSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PG18 business/nonce/raw/receipt boundaries. Native crypto acceptance uses the separate probe. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class HyperCoreSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="HYPERCORE-MPC-TEST",ACCOUNT="hypercore-mpc-principal";
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private AnnotationConfigApplicationContext depositContext;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private HyperCoreSubmissionService service;
    private HyperCoreTransactionService transfers;
    private HyperCoreApiClient api;
    private WithdrawalOrderRepository orders;
    private HyperCoreActionRepository actions;
    private ChainAddressRecord source;
    private String recipient;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true) static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='HYPERCORE'");
        jdbc.update("update chain_profile set enabled=true where chain='HYPERCORE' and network='testnet'");
        new HyperCoreTokenMetadataRepository(jdbc).upsert("testnet","USDC",0,"0xeb62eee3685fc4c43992febcd9e75443",8,8,true,null,null,Timestamp.from(Instant.now()));
        fixture=new MpcSigningTestSupport(jdbc);source=address(TENANT,42,1,"DEPOSIT");
        depositContext=com.surprising.wallet.testutil.DepositTestSupport.context(jdbc,com.surprising.wallet.testutil.CustodyTestSupport.events(jdbc),json);
        recipient="0x"+org.web3j.crypto.Keys.getAddress(org.web3j.crypto.Sign.publicFromPoint(new org.bitcoinj.crypto.ECKey().decompress().getPubKey()));
        orders=new WithdrawalOrderRepository(jdbc);actions=new HyperCoreActionRepository(jdbc);api=mock(HyperCoreApiClient.class);
        when(api.postInfo(any())).thenAnswer(call->call.getArgument(0)!=null && "userRole".equals(((ObjectNode)call.getArgument(0)).path("type").asText())?json.createObjectNode().put("role","user"):json.createArrayNode());
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(HyperCoreActionRepository.class,()->actions);
        context.registerBean(HyperCoreTokenMetadataRepository.class,()->new HyperCoreTokenMetadataRepository(jdbc));
        context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
        context.registerBean(WalletMpcSignRequestRepository.class,()->new WalletMpcSignRequestRepository(jdbc));
        context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(CustodyHotWalletWithdrawalReservationRepository.class,()->new CustodyHotWalletWithdrawalReservationRepository(jdbc));
        context.registerBean(HotWalletReservationLifecycleService.class);context.registerBean(WithdrawalSettlementService.class);
        context.registerBean(ObjectMapper.class,()->json);context.registerBean(HyperCoreSubmissionService.class);
        context.registerBean(HyperCoreLedgerCursorRepository.class,()->new HyperCoreLedgerCursorRepository(jdbc));
        context.registerBean(DepositService.class,()->depositContext.getBean(DepositService.class));context.registerBean(HyperCoreDepositService.class);
        context.registerBean(HyperCoreApiClient.class,()->api);context.registerBean(WalletRuntimeConfigService.class,()->com.surprising.wallet.testutil.RuntimeTestSupport.enabled());
        var broadcastFence=mock(HotWalletBroadcastFence.class);
        when(broadcastFence.withPayoutBroadcastPermit(any(),anyString(),any(),any()))
                .thenAnswer(call->((HotWalletBroadcastFence.Broadcast<?>)call.getArgument(3)).send());
        when(broadcastFence.withAccountCollectionBroadcastPermit(any(),anyString(),anyString(),anyString(),anyString(),any(),any()))
                .thenAnswer(call->((HotWalletBroadcastFence.Broadcast<?>)call.getArgument(6)).send());
        context.registerBean(HotWalletBroadcastFence.class,()->broadcastFence);
        context.registerBean(HyperCoreTransactionService.class);context.refresh();service=context.getBean(HyperCoreSubmissionService.class);transfers=context.getBean(HyperCoreTransactionService.class);
        createOrder(REFERENCE,"USDC");
    }
    @AfterEach void close(){if(context!=null)context.close();if(depositContext!=null)depositContext.close();}
    private HyperCoreSubmissionService.Operation operation(){return new HyperCoreSubmissionService.Operation(TENANT,REFERENCE,HyperCoreSubmissionService.Kind.WITHDRAWAL);}
    private void createOrder(String reference,String symbol) {
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'HYPERCORE',?,?,5,5) on conflict(chain,asset_symbol,account_id) do nothing",TENANT,symbol,ACCOUNT);
        orders.createForTenant(TENANT,reference,42,"HYPERCORE",symbol,source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("HYPERCORE",symbol,ACCOUNT,new BigDecimal("1.1"),TENANT));
        orders.updateStatus(TENANT,"HYPERCORE",reference,"FROZEN",source.getAddress(),null,null);assertEquals(1,orders.claimSigningDurable(TENANT,"HYPERCORE",reference,source.getAddress()));
    }
    private MpcSignRequest reserve(){return service.reserve(operation(),source,recipient);}
    private HyperCoreSubmissionService.Prepared prepared(){var request=reserve();service.sign(operation(),request.id());return service.commit(operation(),request.id());}
    private ObjectNode accepted(){return json.createObjectNode().put("status","ok").set("response",json.createObjectNode().put("type","default"));}
    private ObjectNode receipt(String id) {
        var stored=actions.find(id).orElseThrow();var action=json.readTree(stored.raw()).path("action");
        return json.createObjectNode().put("time",stored.nonce()+1).put("hash","0x"+"ab".repeat(32)).set("delta",json.createObjectNode()
                .put("type","spotTransfer").put("nonce",stored.nonce()).put("user",stored.sender()).put("destination",stored.recipient())
                .put("token",action.path("token").asText().split(":",2)[0]).put("amount",stored.amount().toPlainString()).put("fee","0.0").put("nativeTokenFee","0.0").put("feeToken",""));
    }
    private BigDecimal total(){return new LedgerBalanceRepository(jdbc).find(TENANT,"HYPERCORE","USDC",ACCOUNT).orElseThrow().getTotalBalance();}
    private ChainAddressRecord address(UUID tenant,long user,long index,String role) {
        int namespace=new CustodyTenantTableRepository(jdbc).findById(tenant).orElseThrow().derivationNamespace();
        var key=fixture.signing.awaitKey(tenant,ChainType.HYPERCORE,user,namespace,index,fixture.nodes);
        var profile=new ChainProfileRepository(jdbc).findAccountByChain("HYPERCORE").orElseThrow();
        String address=com.surprising.wallet.chain.MpcPublicAddressCodec.encode(profile,HexFormat.of().parseHex(key.publicKeyHex()));
        return new ChainAddressRepository(jdbc).publishMpc(ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(key.id())
                .chain("HYPERCORE").assetSymbol("USDC").userId(user).biz(namespace).addressIndex(index).address(address).ownerAddress(address)
                .accountId(address).walletRole(role).derivationPath("mpc:"+key.id()).enabled(true).build());
    }
    private BigDecimal booked(ChainAddressRecord address) {return new LedgerBalanceRepository(jdbc).find(address.getTenantId(),"HYPERCORE","USDC",address.getAccountId()).map(value->value.getTotalBalance()).orElse(BigDecimal.ZERO);}
    private ObjectNode incoming(String hash,String amount,long time) {
        return json.createObjectNode().put("time",time).put("hash","0x"+hash.repeat(32)).set("delta",json.createObjectNode()
                .put("type","send").put("nonce",time-1).put("user",recipient).put("destination",source.getAddress())
                .put("sourceDex","spot").put("destinationDex","spot").put("token","USDC").put("amount",amount).put("fee","1.0").put("nativeTokenFee","0.0"));
    }
    @Test void realIncomingHashesCreditEqualPaymentsOnceAtAnInclusiveTimestampBoundary() {
        attachSourceCustody();var scanner=context.getBean(HyperCoreDepositService.class);long cursor=scanner.position(source,"testnet","USDC");
        var records=json.createArrayNode().add(incoming("ab","20",700)).add(incoming("cd","20",700));
        assertTrue(scanner.recordPage(source,"testnet","USDC",cursor,records));assertEquals(0,new BigDecimal("40").compareTo(booked(source)));
        assertTrue(scanner.recordPage(source,"testnet","USDC",700,records));assertEquals(0,new BigDecimal("40").compareTo(booked(source)));
        assertEquals(2,jdbc.queryForObject("select count(*) from custody_deposit",Integer.class));assertEquals(700,scanner.position(source,"testnet","USDC"));
        assertFalse(scanner.recordPage(source,"testnet","USDC",cursor,records));
    }
    @Test void depositProjectionFailureRollsBackCreditAndLedgerCursorTogether() {
        var scanner=context.getBean(HyperCoreDepositService.class);long cursor=scanner.position(source,"testnet","USDC");
        var records=json.createArrayNode().add(incoming("ab","20",700));
        assertThrows(IllegalStateException.class,()->scanner.recordPage(source,"testnet","USDC",cursor,records));
        assertEquals(cursor,scanner.position(source,"testnet","USDC"));assertEquals(0,booked(source).signum());
        assertEquals(0,jdbc.queryForObject("select count(*) from deposit_record",Integer.class));
        attachSourceCustody();assertTrue(scanner.recordPage(source,"testnet","USDC",cursor,records));assertEquals(0,new BigDecimal("20").compareTo(booked(source)));
    }
    @Test void outgoingTransferAndPerpetualPocketCannotBeCreditedAsSpotDeposits() {
        attachSourceCustody();var scanner=context.getBean(HyperCoreDepositService.class);long cursor=scanner.position(source,"testnet","USDC");
        var outgoing=incoming("ab","20",700);((ObjectNode)outgoing.path("delta")).put("user",source.getAddress()).put("destination",recipient);
        var perp=incoming("cd","20",701);((ObjectNode)perp.path("delta")).put("destinationDex","");
        assertTrue(scanner.recordPage(source,"testnet","USDC",cursor,json.createArrayNode().add(outgoing).add(perp)));
        assertEquals(0,booked(source).signum());assertEquals(701,scanner.position(source,"testnet","USDC"));
    }
    @Test void unknownOwnedIncomingTransferBlocksTheWholePageRatherThanInventingADeposit() {
        attachSourceCustody();var sender=address(TENANT,43,2,"DEPOSIT");
        var scanner=context.getBean(HyperCoreDepositService.class);long cursor=scanner.position(source,"testnet","USDC");
        var unknown=incoming("cd","1",701);((ObjectNode)unknown.path("delta")).put("user",sender.getAddress()).put("type","spotTransfer");
        assertThrows(IllegalStateException.class,()->scanner.recordPage(source,"testnet","USDC",cursor,json.createArrayNode().add(incoming("ab","20",700)).add(unknown)));
        assertEquals(cursor,scanner.position(source,"testnet","USDC"));assertEquals(0,booked(source).signum());
    }
    @Test void actualOwnedCollectionReceiptDoesNotCreateAnotherCustomerCredit() {
        var target=address(TENANT,43,2,"DEPOSIT");
        com.surprising.wallet.testutil.DepositTestSupport.attachCustodyAddress(jdbc,TENANT,"HYPERCORE","USDC",target.getAddress());
        String reference="HC-SCAN-COLLECTION";var collections=new CollectionRecordRepository(jdbc);
        collections.create(TENANT,attachSourceCustody(),reference,"HYPERCORE","USDC",source.getAddress(),target.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null);
        when(api.postExchange(any())).thenReturn(accepted());transfers.collect(TENANT,reference,source);
        String id=collections.findTxHash(TENANT,"HYPERCORE",reference).orElseThrow();var scanner=context.getBean(HyperCoreDepositService.class);
        long cursor=scanner.position(target,"testnet","USDC");assertTrue(scanner.recordPage(target,"testnet","USDC",cursor,json.createArrayNode().add(receipt(id))));
        assertEquals(0,booked(target).signum());assertEquals(0,jdbc.queryForObject("select count(*) from custody_deposit",Integer.class));
    }
    @Test void withdrawalToAnotherOwnedCustomerCreditsThatCustomerExactlyOnce() {
        var target=address(TENANT,43,2,"DEPOSIT");
        com.surprising.wallet.testutil.DepositTestSupport.attachCustodyAddress(jdbc,TENANT,"HYPERCORE","USDC",target.getAddress());
        recipient=target.getAddress();jdbc.update("update withdrawal_order set to_address=? where tenant_id=? and order_no=?",recipient,TENANT,REFERENCE);
        var prepared=prepared();var scanner=context.getBean(HyperCoreDepositService.class);long cursor=scanner.position(target,"testnet","USDC");
        var event=receipt(prepared.actionId());assertTrue(scanner.recordPage(target,"testnet","USDC",cursor,json.createArrayNode().add(event)));
        assertTrue(scanner.recordPage(target,"testnet","USDC",event.path("time").asLong(),json.createArrayNode().add(event)));
        assertEquals(0,BigDecimal.ONE.compareTo(booked(target)));assertEquals(1,jdbc.queryForObject("select count(*) from custody_deposit",Integer.class));
    }
    @Test void ambiguousAssetNamesCannotAdmitOrCreditATransfer() {
        attachSourceCustody();new HyperCoreTokenMetadataRepository(jdbc).upsert("testnet","USDC",10,"0x"+"aa".repeat(16),8,8,false,null,null,Timestamp.from(Instant.now()));
        assertThrows(IllegalStateException.class,this::reserve);var scanner=context.getBean(HyperCoreDepositService.class);long cursor=scanner.position(source,"testnet","USDC");
        assertThrows(IllegalStateException.class,()->scanner.recordPage(source,"testnet","USDC",cursor,json.createArrayNode().add(incoming("ab","20",700))));
        assertEquals(cursor,scanner.position(source,"testnet","USDC"));
    }
    @Test void tokensHaveIndependentCursorsOnTheSameNativeAddressAndClosedTokensStillCreditObservedTransfers() {
        attachSourceCustody();var scanner=context.getBean(HyperCoreDepositService.class);
        jdbc.update("delete from hypercore_token_metadata where network='testnet' and name='HYPE'");
        new HyperCoreTokenMetadataRepository(jdbc).upsert("testnet","HYPE",1105,"0x7317beb7cceed72ef0b346074cc8e7ab",2,8,false,null,null,Timestamp.from(Instant.now()));
        long usdc=scanner.position(source,"testnet","USDC"),hype=scanner.position(source,"testnet","HYPE");
        var usdcRecord=incoming("ab","20",700);
        assertTrue(scanner.recordPage(source,"testnet","USDC",usdc,json.createArrayNode().add(usdcRecord)));
        jdbc.update("update token_config set enabled=false,collect_enabled=false where chain='HYPERCORE' and network='testnet' and symbol='HYPE'");
        jdbc.update("update chain_address set enabled=false where id=?",source.getId());
        var token=incoming("cd","0.02",650);((ObjectNode)token.path("delta")).put("token","HYPE");
        assertTrue(scanner.recordPage(source,"testnet","HYPE",hype,json.createArrayNode().add(token)));
        assertTrue(scanner.recordPage(source,"testnet","HYPE",650,json.createArrayNode().add(token)));
        assertEquals(700,scanner.position(source,"testnet","USDC"));assertEquals(650,scanner.position(source,"testnet","HYPE"));
        assertEquals(0,new BigDecimal("0.02").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"HYPERCORE","HYPE",source.getAccountId()).orElseThrow().getTotalBalance()));
    }
    @Test void fullLedgerTimestampPageCannotAdvancePastAnUnresolvedBoundary() {
        var scanner=context.getBean(HyperCoreDepositService.class);long cursor=scanner.position(source,"testnet","USDC");
        var first=json.createArrayNode();for(int i=1;i<=500;i++)first.add(json.createObjectNode().put("time",i).set("delta",json.createObjectNode().put("type","unrelated")));
        assertTrue(scanner.recordPage(source,"testnet","USDC",cursor,first));
        var boundary=json.createArrayNode();for(int i=0;i<500;i++)boundary.add(json.createObjectNode().put("time",500).set("delta",json.createObjectNode().put("type","unrelated")));
        assertThrows(IllegalStateException.class,()->scanner.recordPage(source,"testnet","USDC",500,boundary));
        assertEquals(500,scanner.position(source,"testnet","USDC"));
    }
    @Test void truncatedLedgerQueryCannotTriggerAnotherSubmissionWhenTheOriginalReceiptIsMissing() {
        var prepared=prepared();var full=json.createArrayNode();for(int i=0;i<500;i++)full.add(json.createObjectNode().put("time",i).set("delta",json.createObjectNode().put("nonce",1)));
        when(api.postInfo(any())).thenReturn(full);
        assertThrows(IllegalStateException.class,()->transfers.recover(operation()));verify(api,never()).postExchange(any());
        assertNull(actions.find(prepared.actionId()).orElseThrow().receipt());assertFalse(service.settle(operation(),prepared.actionId()));
    }
    @Test void selectedBodyAndBusinessIdCommitBeforeAnyNetworkAndRetryKeepsOriginalNonce(){
        var prepared=prepared();var request=service.intent(operation()).orElseThrow();
        assertEquals(prepared.actionId(),orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(prepared.body(),actions.find(prepared.actionId()).orElseThrow().raw());assertEquals("PREPARED",actions.find(prepared.actionId()).orElseThrow().status());
        assertEquals(request.id(),reserve().id());assertEquals(prepared,service.commit(operation(),request.id()));
        assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt",Integer.class));verifyNoInteractions(api);
        assertEquals("spotSend",json.readTree(prepared.body()).path("action").path("type").asText());
        assertEquals("USDC:0xeb62eee3685fc4c43992febcd9e75443",json.readTree(prepared.body()).path("action").path("token").asText());
    }
    @Test void nativePendingRetainsOriginalIntentNonceAndFrozenPrincipal(){
        var request=reserve();fixture.signingPending.set(true);assertThrows(MpcPendingException.class,()->service.sign(operation(),request.id()));
        service.unknown(operation(),"pending");assertEquals(0,service.releaseUnsigned(operation()));assertNull(orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(0,BigDecimal.valueOf(5).compareTo(total()));fixture.signingPending.set(false);service.sign(operation(),request.id());assertNotNull(service.commit(operation(),request.id()));
    }
    @Test void onlyNoIntentAndNoActionIdCanRearm(){service.unknown(operation(),"before admission");assertEquals(1,service.releaseUnsigned(operation()));assertEquals("FROZEN",orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getStatus());}
    @Test void equalPaymentsReserveDifferentNoncesAndIds(){
        var first=prepared();createOrder("HC-SECOND","USDC");var op=new HyperCoreSubmissionService.Operation(TENANT,"HC-SECOND",HyperCoreSubmissionService.Kind.WITHDRAWAL);
        var request=service.reserve(op,source,recipient);service.sign(op,request.id());var second=service.commit(op,request.id());assertNotEquals(first.actionId(),second.actionId());
        assertNotEquals(json.readTree(first.body()).path("nonce").asLong(),json.readTree(second.body()).path("nonce").asLong());
    }
    @Test void foreignBusinessAndChangedOwnerCannotReserveAnyIntent(){
        assertThrows(RuntimeException.class,()->service.reserve(new HyperCoreSubmissionService.Operation(UUID.randomUUID(),REFERENCE,HyperCoreSubmissionService.Kind.WITHDRAWAL),source,recipient));
        var changed=address(TENANT,43,2,"DEPOSIT");assertThrows(IllegalStateException.class,()->service.reserve(operation(),changed,recipient));
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
    }
    @Test void rawBindingFailureRollsBackRawWhileKeepingCompletedSignature(){
        var request=reserve();service.sign(operation(),request.id());
        jdbc.execute("create function public.hc_test_reject_hash() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject hash';end if;return new;end $$;create trigger hc_test_reject_hash before update on withdrawal_order for each row execute function public.hc_test_reject_hash()");
        try{assertThrows(RuntimeException.class,()->service.commit(operation(),request.id()));assertEquals(0,jdbc.queryForObject("select count(*) from hypercore_action_record",Integer.class));assertEquals(MpcSignRequest.State.COMPLETED,service.intent(operation()).orElseThrow().state());}
        finally{jdbc.execute("drop trigger hc_test_reject_hash on withdrawal_order;drop function public.hc_test_reject_hash()");}
        assertNotNull(service.commit(operation(),request.id()));
    }
    @Test void onlySuccessfulOwnedExecutionResponseCanSettleAndLateUnknownCannotRegressIt(){
        var prepared=prepared();assertFalse(service.settle(operation(),prepared.actionId()));
        assertThrows(IllegalStateException.class,()->service.accepted(operation(),prepared.actionId(),json.createObjectNode().put("status","err")));
        var nested=accepted();((ObjectNode)nested.path("response")).put("error","execution failed");assertThrows(IllegalStateException.class,()->service.accepted(operation(),prepared.actionId(),nested));
        service.accepted(operation(),prepared.actionId(),accepted());service.restoreSent(operation(),prepared.actionId());service.unknown(operation(),"late error");
        assertFalse(service.settle(operation(),prepared.actionId()));
        service.proven(operation(),prepared.actionId(),receipt(prepared.actionId()));
        assertTrue(service.settle(operation(),prepared.actionId()));assertFalse(service.settle(operation(),prepared.actionId()));assertEquals(0,new BigDecimal("3.9").compareTo(total()));
        assertEquals("ACCEPTED",actions.find(prepared.actionId()).orElseThrow().status());assertEquals("CONFIRMED",orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void requestTransportReadsAlreadyCommittedRawHashAndSignatureOutsideTransaction(){
        when(api.postExchange(any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            String id=orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getTxHash();assertNotNull(id);
            assertEquals(call.getArgument(0).toString(),actions.find(id).orElseThrow().raw());assertEquals(MpcSignRequest.State.COMPLETED,service.intent(operation()).orElseThrow().state());return accepted();
        });
        String id=transfers.send(operation(),source);assertEquals(1,orders.markSent(TENANT,"HYPERCORE",REFERENCE,source.getAddress(),id));
        when(api.postInfo(any())).thenReturn(json.createArrayNode().add(receipt(id)));
        assertTrue(transfers.confirmWithdrawal(TENANT,REFERENCE,id));assertFalse(transfers.confirmWithdrawal(TENANT,REFERENCE,id));
    }
    @Test void lostReplyThenDuplicateNonceErrorKeepsExactBodyUnknownAndPrincipalFrozen(){
        var observed=new ArrayList<String>();
        when(api.postExchange(any())).thenAnswer(call->{observed.add(call.getArgument(0).toString());if(observed.size()==1)throw new IllegalStateException("lost server reply");return json.createObjectNode().put("status","err").put("response","Nonce already used");});
        assertThrows(IllegalStateException.class,()->transfers.send(operation(),source));assertThrows(IllegalStateException.class,()->transfers.recover(operation()));
        assertEquals(2,observed.size());assertEquals(observed.get(0),observed.get(1));assertEquals(0,service.releaseUnsigned(operation()));
        String id=orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getTxHash();assertEquals("UNKNOWN",actions.find(id).orElseThrow().status());assertFalse(service.settle(operation(),id));
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"HYPERCORE","USDC",ACCOUNT).orElseThrow();assertEquals(0,new BigDecimal("1.1").compareTo(balance.getLockedBalance()));
    }
    @Test void existingAcceptedResponseRecoversWithoutAnotherNetworkSubmission(){
        var prepared=prepared();service.accepted(operation(),prepared.actionId(),accepted());service.unknown(operation(),"lost business publication");
        assertTrue(transfers.recover(operation()));verifyNoInteractions(api);assertEquals("SENT",orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void lostReplyIsRecoveredOnlyFromOriginalLedgerNonceWithoutResubmission() {
        var submitted=new java.util.concurrent.atomic.AtomicBoolean();
        when(api.postInfo(any())).thenAnswer(call->{
            if("userRole".equals(((ObjectNode)call.getArgument(0)).path("type").asText()))return json.createObjectNode().put("role","user");
            var result=json.createArrayNode();if(submitted.get())result.add(receipt(orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getTxHash()));return result;
        });
        when(api.postExchange(any())).thenAnswer(call->{submitted.set(true);throw new IllegalStateException("all execution replies lost");});
        String id=transfers.send(operation(),source);service.restoreSent(operation(),id);
        var stored=actions.find(id).orElseThrow();assertNull(stored.response());assertNotNull(stored.receipt());
        assertEquals(TENANT,stored.tenantId());assertEquals(service.intent(operation()).orElseThrow().id(),stored.requestId());
        assertTrue(transfers.confirmWithdrawal(TENANT,REFERENCE,id));assertFalse(transfers.confirmWithdrawal(TENANT,REFERENCE,id));
        assertTrue(transfers.recover(operation()));verify(api,times(1)).postExchange(any());assertEquals(0,new BigDecimal("3.9").compareTo(total()));
    }
    @Test void sameAmountAtAnotherNonceCannotRecoverOrSettleTheOriginalPayment() {
        var prepared=prepared();var unrelated=receipt(prepared.actionId());((ObjectNode)unrelated.path("delta")).put("nonce",actions.find(prepared.actionId()).orElseThrow().nonce()+1);
        when(api.postInfo(any())).thenReturn(json.createArrayNode().add(unrelated));
        when(api.postExchange(any())).thenThrow(new IllegalStateException("no original execution"));
        assertThrows(IllegalStateException.class,()->transfers.recover(operation()));assertNull(actions.find(prepared.actionId()).orElseThrow().receipt());
        assertFalse(service.settle(operation(),prepared.actionId()));assertEquals(0,BigDecimal.valueOf(5).compareTo(total()));
    }
    @Test void receiptCannotChangeRecipientAmountAssetFeeOrPublishedHash() {
        var prepared=prepared();var original=receipt(prepared.actionId());
        for(var field:Map.of("destination","0x"+"ff".repeat(20),"amount","0.9","token","HYPE","fee","1","nativeTokenFee","0.1").entrySet()) {
            var changed=original.deepCopy();((ObjectNode)changed.path("delta")).put(field.getKey(),field.getValue());
            assertThrows(IllegalArgumentException.class,()->service.proven(operation(),prepared.actionId(),changed));
        }
        assertNull(actions.find(prepared.actionId()).orElseThrow().receipt());service.proven(operation(),prepared.actionId(),original);
        var changed=original.deepCopy().put("hash","0x"+"cd".repeat(32));
        assertThrows(IllegalStateException.class,()->service.proven(operation(),prepared.actionId(),changed));
    }
    @Test void spotAssetRequiresExactMetadataTokenIdAndConfiguredPrecision(){
        createOrder("HC-HYPE","HYPE");var op=new HyperCoreSubmissionService.Operation(TENANT,"HC-HYPE",HyperCoreSubmissionService.Kind.WITHDRAWAL);
        var repo=new HyperCoreTokenMetadataRepository(jdbc);
        jdbc.update("delete from hypercore_token_metadata where network='testnet' and name='HYPE'");
        repo.upsert("testnet","HYPE",1000,"0x"+"11".repeat(16),8,8,true,null,null,Timestamp.from(Instant.now()));assertThrows(IllegalStateException.class,()->service.reserve(op,source,recipient));
        String id=new TokenConfigRepository(jdbc).findDefinition("HYPERCORE","testnet","HYPE").orElseThrow().getContractAddress();
        repo.upsert("testnet","HYPE",1000,id,8,9,true,null,null,Timestamp.from(Instant.now()));assertThrows(IllegalStateException.class,()->service.reserve(op,source,recipient));
        repo.upsert("testnet","HYPE",1000,id,8,8,true,null,null,Timestamp.from(Instant.now()));var request=service.reserve(op,source,recipient);
        assertEquals("HYPE:"+id.toLowerCase(Locale.ROOT),MpcHyperCoreActionCodec.restore(service.payload(request).unsigned()).path("token").asText());
    }
    @Test void unactivatedDestinationCannotAdmitAnIntentOrSpendAnUnreservedActivationFee() {
        when(api.postInfo(any())).thenReturn(json.createObjectNode().put("role","missing"));
        assertThrows(IllegalStateException.class,()->transfers.send(operation(),source));
        verify(api,never()).postExchange(any());assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='HYPERCORE'",Integer.class));
    }
    @Test void receiverCannotChangeAfterItsActivationWasChecked() {
        when(api.postInfo(any())).thenAnswer(call->{jdbc.update("update withdrawal_order set to_address=? where tenant_id=? and order_no=?","0x"+"cd".repeat(20),TENANT,REFERENCE);return json.createObjectNode().put("role","user");});
        assertThrows(IllegalStateException.class,()->transfers.send(operation(),source));verify(api,never()).postExchange(any());
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
    }
    private UUID attachSourceCustody() {
        UUID id=UUID.randomUUID();new CustodyAddressRepository(jdbc).insert(id,TENANT,source.getId(),"HYPERCORE","testnet",source.getAddress(),null,"hypercore-source",null,"{}","CONSOLE",42,0,1,null);return id;
    }
    @Test void collectionUsesOwnedJointCollectorAndSettlesOnlyItsAcceptedBody() {
        var target=address(TENANT,0,2,"HOT");
        String reference="HC-COLLECTION";var collections=new CollectionRecordRepository(jdbc);
        assertEquals(1,collections.create(TENANT,attachSourceCustody(),reference,"HYPERCORE","USDC",source.getAddress(),target.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null));
        when(api.postExchange(any())).thenReturn(accepted());transfers.collect(TENANT,reference,source);
        String id=collections.findTxHash(TENANT,"HYPERCORE",reference).orElseThrow();
        assertEquals("SENT",collections.findStatus(TENANT,"HYPERCORE",reference).orElseThrow());
        service.proven(new HyperCoreSubmissionService.Operation(TENANT,reference,HyperCoreSubmissionService.Kind.COLLECTION),id,receipt(id));
        assertTrue(transfers.confirmCollection(TENANT,reference,id));assertFalse(transfers.confirmCollection(TENANT,reference,id));
        service.unknown(new HyperCoreSubmissionService.Operation(TENANT,reference,HyperCoreSubmissionService.Kind.COLLECTION),"late error");
        assertEquals("CONFIRMED",collections.findStatus(TENANT,"HYPERCORE",reference).orElseThrow());assertEquals("ACCEPTED",actions.find(id).orElseThrow().status());
        assertEquals(target.getAddress(),json.readTree(actions.find(id).orElseThrow().raw()).path("action").path("destination").asText());
    }
    @Test void foreignTenantCollectorCannotAdmitAnyMpcOrNetworkAction() {
        UUID other=UUID.randomUUID();new CustodyTenantTableRepository(jdbc).insert(other,"hc-foreign","Foreign HyperCore test tenant");
        var target=address(other,0,2,"HOT");
        String reference="HC-FOREIGN-COLLECTOR";var collections=new CollectionRecordRepository(jdbc);
        collections.create(TENANT,attachSourceCustody(),reference,"HYPERCORE","USDC",source.getAddress(),target.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null);
        assertThrows(RuntimeException.class,()->transfers.collect(TENANT,reference,source));verify(api,never()).postExchange(any());
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='HYPERCORE'",Integer.class));
    }
    @Test void ledgerFailureRollsBackBusinessSettlementAndKeepsAcceptedReceiptForRetry() {
        var prepared=prepared();service.accepted(operation(),prepared.actionId(),accepted());service.restoreSent(operation(),prepared.actionId());
        service.proven(operation(),prepared.actionId(),receipt(prepared.actionId()));
        jdbc.execute("create function public.hc_test_reject_ledger() returns trigger language plpgsql as $$ begin raise exception 'reject ledger';end $$;create trigger hc_test_reject_ledger before update on ledger_balance for each row execute function public.hc_test_reject_ledger()");
        try {assertThrows(RuntimeException.class,()->service.settle(operation(),prepared.actionId()));assertEquals("SENT",orders.find("HYPERCORE",REFERENCE,TENANT).orElseThrow().getStatus());assertEquals(0,BigDecimal.valueOf(5).compareTo(total()));assertEquals("ACCEPTED",actions.find(prepared.actionId()).orElseThrow().status());}
        finally {jdbc.execute("drop trigger hc_test_reject_ledger on ledger_balance;drop function public.hc_test_reject_ledger()");}
        assertTrue(service.settle(operation(),prepared.actionId()));assertEquals(0,new BigDecimal("3.9").compareTo(total()));
    }
    @Test void concurrentReservationsPublishOnlyOneNonceAndIntent() throws Exception {
        try(var executor=Executors.newFixedThreadPool(6)){var futures=new ArrayList<Future<UUID>>();for(int i=0;i<12;i++)futures.add(executor.submit(()->reserve().id()));var ids=new HashSet<UUID>();for(var future:futures)ids.add(future.get(15,TimeUnit.SECONDS));assertEquals(1,ids.size());}
        assertEquals(1,jdbc.queryForObject("select count(*) from account_sequence where chain='HYPERCORE'",Integer.class));
    }
}
