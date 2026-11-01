package com.surprising.wallet.custody;

import com.google.common.primitives.UnsignedInteger;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.xrp.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.MpcSigningTestSupport;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.xrpl.xrpl4j.codec.binary.XrplBinaryCodec;
import org.xrpl.xrpl4j.crypto.keys.PublicKey;
import org.xrpl.xrpl4j.model.transactions.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class XrpSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="XRP-MPC-TEST",ACCOUNT="xrp-mpc-principal";
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private XrpSubmissionService service;
    private WithdrawalOrderRepository orders;
    private XrpTransactionRepository transactions;
    private ChainAddressRecord source;
    private String recipient;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='XRP'");
        jdbc.update("update chain_profile set enabled=true where chain='XRP' and network='testnet'");
        fixture=new MpcSigningTestSupport(jdbc);source=fixture.address(jdbc,TENANT,ChainType.XRP,42,1,"DEPOSIT");
        recipient=PublicKey.fromBase16EncodedPublicKey(HexFormat.of().formatHex(new org.bitcoinj.crypto.ECKey().getPubKey())).deriveAddress().value();
        orders=new WithdrawalOrderRepository(jdbc);transactions=new XrpTransactionRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(XrpTransactionRepository.class,()->transactions);
        context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
        context.registerBean(WalletMpcSignRequestRepository.class,()->new WalletMpcSignRequestRepository(jdbc));
        context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));context.registerBean(WithdrawalSettlementService.class);
        context.registerBean(ObjectMapper.class,()->json);context.registerBean(CustodyGasAccountingService.class,()->com.surprising.wallet.testutil.CustodyTestSupport.gasAccounting(jdbc));context.registerBean(XrpSubmissionService.class);context.refresh();service=context.getBean(XrpSubmissionService.class);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'XRP','XRP',?,5,5)",TENANT,ACCOUNT);
        createOrder(REFERENCE);
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private XrpSubmissionService.Operation operation(){return new XrpSubmissionService.Operation(TENANT,REFERENCE,XrpSubmissionService.Kind.WITHDRAWAL);}
    private void createOrder(String reference){
        orders.createForTenant(TENANT,reference,42,"XRP","XRP",source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("XRP","XRP",ACCOUNT,new BigDecimal("1.1"),TENANT));
        orders.updateStatus(TENANT,"XRP",reference,"FROZEN",source.getAddress(),null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"XRP",reference,source.getAddress()));
    }
    private Payment draft(){return Payment.builder().account(Address.of(source.getAddress())).destination(Address.of(recipient))
            .amount(XrpCurrencyAmount.ofDrops(1000000)).fee(XrpCurrencyAmount.ofDrops(12)).sequence(UnsignedInteger.valueOf(17))
            .lastLedgerSequence(UnsignedInteger.valueOf(100)).build();}
    private MpcSignRequest reserve(){return service.reserve(operation(),source,"XRP",BigDecimal.ONE,draft());}
    private MpcXrpTransactionCodec.Signed prepared(){var request=reserve();service.sign(operation(),request.id());return service.commit(operation(),request.id());}
    private ObjectNode receipt(MpcXrpTransactionCodec.Signed signed,String code) throws Exception {
        var result=json.createObjectNode().put("hash",signed.txId()).put("validated",true).put("ledger_index",30);
        result.set("tx_json",json.readTree(XrplBinaryCodec.getInstance().decode(signed.transactionHex())));
        result.set("meta",json.createObjectNode().put("TransactionResult",code).put("delivered_amount","1000000"));return result;
    }
    @Test void sequenceIntentAndSelectedRawSurviveRetriesAndCannotProduceAnotherPayment(){
        var signed=prepared();var request=service.intent(operation()).orElseThrow();
        assertEquals(signed.txId(),orders.find("XRP",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(signed.transactionHex(),transactions.findSigned(signed.txId()).orElseThrow().getRawPayload());
        assertEquals("PREPARED",transactions.findSigned(signed.txId()).orElseThrow().getStatus());
        assertEquals(request.id(),service.reserve(operation(),source,"XRP",BigDecimal.TEN,null).id());
        assertEquals(signed,service.commit(operation(),request.id()));assertEquals(18L,jdbc.queryForObject("select next_sequence from account_sequence where chain='XRP' and address=?",Long.class,source.getAddress()));
        service.observed(operation(),signed.txId());service.restoreSent(operation(),signed.txId());assertEquals("SENT",orders.find("XRP",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void pendingSignatureRetainsSequenceIntentAndFrozenPrincipal(){
        var request=reserve();fixture.signingPending.set(true);assertThrows(MpcPendingException.class,()->service.sign(operation(),request.id()));
        orders.markBroadcastUnknown(TENANT,"XRP",REFERENCE,source.getAddress(),"pending");assertEquals(0,service.releaseUnsigned(operation()));
        assertNull(orders.find("XRP",REFERENCE,TENANT).orElseThrow().getTxHash());assertEquals(new BigDecimal("1.1").stripTrailingZeros(),new LedgerBalanceRepository(jdbc).find(TENANT,"XRP","XRP",ACCOUNT).orElseThrow().getLockedBalance().stripTrailingZeros());
        fixture.signingPending.set(false);service.sign(operation(),request.id());var signed=service.commit(operation(),request.id());service.restoreSent(operation(),signed.txId());
    }
    @Test void noIntentMayRearmOnlyUnderOwnedBusinessLock(){
        orders.markBroadcastUnknown(TENANT,"XRP",REFERENCE,source.getAddress(),"before intent");assertEquals(1,service.releaseUnsigned(operation()));
        assertEquals("FROZEN",orders.find("XRP",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void sameObservedSequenceForDifferentOrdersStillAllocatesDifferentDurableSequences(){
        var first=prepared();createOrder("XRP-SECOND");var other=new XrpSubmissionService.Operation(TENANT,"XRP-SECOND",XrpSubmissionService.Kind.WITHDRAWAL);
        var request=service.reserve(other,source,"XRP",BigDecimal.ONE,draft());service.sign(other,request.id());var second=service.commit(other,request.id());
        assertNotEquals(first.txId(),second.txId());assertEquals(18,MpcXrpTransactionCodec.restore(service.payload(request).unsigned()).sequence().longValue());
    }
    @Test void foreignOwnerChangedRecipientAndAssetCannotReserve(){
        assertThrows(RuntimeException.class,()->service.reserve(new XrpSubmissionService.Operation(UUID.randomUUID(),REFERENCE,XrpSubmissionService.Kind.WITHDRAWAL),source,"XRP",BigDecimal.ONE,draft()));
        assertThrows(RuntimeException.class,()->service.reserve(operation(),source,"XRP",BigDecimal.ONE,Payment.builder().from(draft()).destination(Address.of(source.getAddress())).build()));
        assertThrows(RuntimeException.class,()->service.reserve(operation(),source,"USDT",BigDecimal.ONE,draft()));
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence where chain='XRP'",Integer.class));
    }
    @Test void rawHashBindingFailureRollsBackRawButPreservesSelectedSignature(){
        var request=reserve();service.sign(operation(),request.id());
        jdbc.execute("create function public.xrp_test_reject_hash() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject hash'; end if;return new;end $$;create trigger xrp_test_reject_hash before update on withdrawal_order for each row execute function public.xrp_test_reject_hash()");
        try{assertThrows(RuntimeException.class,()->service.commit(operation(),request.id()));assertEquals(0,jdbc.queryForObject("select count(*) from xrp_transaction",Integer.class));
            assertEquals(MpcSignRequest.State.COMPLETED,service.intent(operation()).orElseThrow().state());}
        finally{jdbc.execute("drop trigger xrp_test_reject_hash on withdrawal_order;drop function public.xrp_test_reject_hash()");}
        assertNotNull(service.commit(operation(),request.id()));
    }
    @Test void validatedSuccessSettlesExactlyOnceAndPreservesSignedBinary() throws Exception {
        var signed=prepared();service.restoreSent(operation(),signed.txId());var receipt=receipt(signed,"tesSUCCESS");
        assertTrue(service.settle(operation(),receipt,1,30));assertFalse(service.settle(operation(),receipt,1,30));
        assertEquals("CONFIRMED",orders.find("XRP",REFERENCE,TENANT).orElseThrow().getStatus());
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"XRP","XRP",ACCOUNT).orElseThrow();assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("3.9").compareTo(balance.getTotalBalance()));
        assertEquals(signed.transactionHex(),transactions.findSigned(signed.txId()).orElseThrow().getRawPayload());
    }
    @Test void validatedFailureReleasesOnceButRetainsActualFee() throws Exception {
        var signed=prepared();service.restoreSent(operation(),signed.txId());var receipt=receipt(signed,"tecPATH_DRY");
        assertTrue(service.settle(operation(),receipt,1,30));assertFalse(service.settle(operation(),receipt,1,30));
        assertEquals("FAILED",orders.find("XRP",REFERENCE,TENANT).orElseThrow().getStatus());assertEquals(0,new BigDecimal("5").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"XRP","XRP",ACCOUNT).orElseThrow().getTotalBalance()));
        assertEquals(0,BigDecimal.valueOf(12).compareTo(transactions.findConfirmedFeeAtomic("XRP",signed.txId()).orElseThrow()));
    }
    @Test void pendingOrWrongDeliveryCannotSettleAndLedgerFailureRollsBackChainFact() throws Exception {
        var signed=prepared();service.restoreSent(operation(),signed.txId());var receipt=receipt(signed,"tesSUCCESS");receipt.put("validated",false);
        assertFalse(service.settle(operation(),receipt,1,30));receipt.put("validated",true);((ObjectNode)receipt.path("meta")).put("delivered_amount","2");
        assertThrows(RuntimeException.class,()->service.settle(operation(),receipt,1,30));((ObjectNode)receipt.path("meta")).put("delivered_amount","1000000");
        jdbc.execute("create function public.xrp_test_reject_ledger() returns trigger language plpgsql as $$ begin raise exception 'reject ledger';end $$;create trigger xrp_test_reject_ledger before update on ledger_balance for each row execute function public.xrp_test_reject_ledger()");
        try{assertThrows(RuntimeException.class,()->service.settle(operation(),receipt,1,30));assertEquals("PREPARED",transactions.findSigned(signed.txId()).orElseThrow().getStatus());assertEquals("SENT",orders.find("XRP",REFERENCE,TENANT).orElseThrow().getStatus());}
        finally{jdbc.execute("drop trigger xrp_test_reject_ledger on ledger_balance;drop function public.xrp_test_reject_ledger()");}
    }
    private ChainAddressRecord preparationGas(BigDecimal amount) {
        var gas=fixture.address(jdbc,TENANT,ChainType.XRP,0,2,"HOT");
        UUID custody=UUID.randomUUID();
        new CustodyAddressRepository(jdbc).insert(custody,TENANT,gas.getId(),"XRP","testnet",gas.getAddress(),null,"mpc-gas",null,"{}","CONSOLE",0,0,2,null);
        new CustodyGasAccountRepository(jdbc).insert(UUID.randomUUID(),TENANT,custody,"XRP","testnet","XRP",BigDecimal.ZERO,null);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'XRP','XRP',?,?,?)",TENANT,gas.getAccountId(),amount,amount);
        jdbc.update("insert into token_config(chain,network,symbol,standard,contract_address,decimals,enabled) values ('XRP','testnet','USDT','IOU',?,6,true) on conflict(chain,network,symbol) do update set contract_address=excluded.contract_address,decimals=6,enabled=true",recipient+":USD");
        return gas;
    }
    private XrpSubmissionService.Operation preparation() {
        return new XrpSubmissionService.Operation(TENANT,XrpSubmissionService.preparationReference(source,"USDT",false),XrpSubmissionService.Kind.FUNDING);
    }
    private MpcSignRequest reservePreparation(ChainAddressRecord gas) {
        return service.reserve(preparation(),gas,"USDT",BigDecimal.ONE,Payment.builder().from(draft()).account(Address.of(gas.getAddress())).destination(Address.of(source.getAddress())).build());
    }
    @Test void activationIntentGasFreezeActualSpendAndLedgerEntryAreAtomicAndIdempotent() throws Exception {
        var gas=preparationGas(BigDecimal.TEN);var request=reservePreparation(gas);
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"XRP","XRP",gas.getAccountId()).orElseThrow();
        assertEquals(0,new BigDecimal("1.000012").compareTo(balance.getLockedBalance()));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_gas_usage where operation_type='ADDRESS_PREPARATION' and status='RESERVED'",Integer.class));
        service.sign(preparation(),request.id());var signed=service.commit(preparation(),request.id());var receipt=receipt(signed,"tesSUCCESS");
        assertTrue(service.settle(preparation(),receipt,1,30));assertTrue(service.settle(preparation(),receipt,1,30));
        balance=new LedgerBalanceRepository(jdbc).find(TENANT,"XRP","XRP",gas.getAccountId()).orElseThrow();
        assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("8.999988").compareTo(balance.getTotalBalance()));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_ledger_entry where entry_type='ADDRESS_PREPARATION'",Integer.class));
        assertEquals("XRP_ACTIVATION",transactions.findSigned(signed.txId()).orElseThrow().getAssetSymbol());
    }
    @Test void failedActivationChargesOnlyActualFeeAndRetainsItsSelectedRequest() throws Exception {
        var gas=preparationGas(BigDecimal.TEN);var request=reservePreparation(gas);service.sign(preparation(),request.id());var signed=service.commit(preparation(),request.id());
        assertFalse(service.settle(preparation(),receipt(signed,"tecNO_DST_INSUF_XRP"),1,30));
        assertFalse(service.settle(preparation(),receipt(signed,"tecNO_DST_INSUF_XRP"),1,30));
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"XRP","XRP",gas.getAccountId()).orElseThrow();
        assertEquals(0,new BigDecimal("9.999988").compareTo(balance.getTotalBalance()));assertEquals(0,balance.getLockedBalance().signum());
        assertEquals(request.id(),service.intent(preparation()).orElseThrow().id());
    }
    @Test void insufficientPreparationGasRollsBackSequenceAndIntentTogether() {
        var gas=preparationGas(BigDecimal.ZERO);assertThrows(IllegalStateException.class,()->reservePreparation(gas));
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from account_sequence",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from custody_gas_usage",Integer.class));
    }
    @Test void acceptedPreparationPendingCannotChangeItsAmountOrFreezeGasAgain() {
        var gas=preparationGas(BigDecimal.TEN);var request=reservePreparation(gas);fixture.signingPending.set(true);
        assertThrows(MpcPendingException.class,()->service.sign(preparation(),request.id()));
        assertEquals(request.id(),service.reserve(preparation(),gas,"USDT",BigDecimal.TEN,null).id());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_gas_usage",Integer.class));
        assertEquals(0,new BigDecimal("1.000012").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"XRP","XRP",gas.getAccountId()).orElseThrow().getLockedBalance()));
    }
    @Test void concurrentReservationsSelectOneSequenceAndOneIntent() throws Exception {
        try(var executor=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<UUID>>();for(int i=0;i<12;i++)futures.add(executor.submit(()->reserve().id()));
            var ids=new HashSet<UUID>();for(var future:futures)ids.add(future.get(15,TimeUnit.SECONDS));assertEquals(1,ids.size());
        }
        assertEquals(18L,jdbc.queryForObject("select next_sequence from account_sequence where chain='XRP'",Long.class));
    }
}
