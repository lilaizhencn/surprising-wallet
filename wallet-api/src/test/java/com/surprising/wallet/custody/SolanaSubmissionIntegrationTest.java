package com.surprising.wallet.custody;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.solana.*;
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
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.utils.Base58;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** PG18 business/intent/multiple-signature/receipt atomicity. Crypto here is explicitly a test substitute. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class SolanaSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="SOL-MPC-TEST",ACCOUNT="sol-mpc-principal";
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private SolanaSubmissionService service;
    private WithdrawalOrderRepository orders;
    private SolanaTransactionRepository transactions;
    private WalletMpcSignRequestRepository requests;
    private ChainAddressRecord source,payer;
    private String recipient;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='SOLANA'");
        jdbc.update("update chain_profile set enabled=true where chain='SOLANA' and network='devnet'");
        fixture=new MpcSigningTestSupport(jdbc);source=fixture.address(jdbc,TENANT,ChainType.SOLANA,42,1,"DEPOSIT");
        recipient=new Account().getPublicKeyBase58();
        orders=new WithdrawalOrderRepository(jdbc);transactions=new SolanaTransactionRepository(jdbc);requests=new WalletMpcSignRequestRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(SolanaTransactionRepository.class,()->transactions);
        context.registerBean(WalletMpcSignRequestRepository.class,()->requests);
        context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(CustodyGasAccountRepository.class,()->new CustodyGasAccountRepository(jdbc));context.registerBean(CustodyAddressRepository.class,()->new CustodyAddressRepository(jdbc));
        context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));context.registerBean(WithdrawalSettlementService.class);
        context.registerBean(ObjectMapper.class,()->json);context.registerBean(SolanaSubmissionService.class);context.refresh();service=context.getBean(SolanaSubmissionService.class);
        createOrder(REFERENCE,"SOL");
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private SolanaSubmissionService.Operation operation(){return operation(REFERENCE);}
    private SolanaSubmissionService.Operation operation(String reference){return new SolanaSubmissionService.Operation(TENANT,reference,SolanaSubmissionService.Kind.WITHDRAWAL);}
    private void createOrder(String reference,String symbol){
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'SOLANA',?,?,5,5) on conflict(chain,asset_symbol,account_id) do nothing",TENANT,symbol,ACCOUNT);
        orders.createForTenant(TENANT,reference,42,"SOLANA",symbol,source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("SOLANA",symbol,ACCOUNT,new BigDecimal("1.1"),TENANT));
        orders.updateStatus(TENANT,"SOLANA",reference,"FROZEN",source.getAddress(),null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"SOLANA",reference,source.getAddress()));
    }
    private UUID attach(ChainAddressRecord address,String subject){
        UUID id=UUID.randomUUID();new CustodyAddressRepository(jdbc).insert(id,TENANT,address.getId(),"SOLANA","devnet",address.getAddress(),null,subject,null,"{}","CONSOLE",Math.toIntExact(address.getUserId()),0,address.getAddressIndex(),null);return id;
    }
    private void gas(){
        payer=fixture.address(jdbc,TENANT,ChainType.SOLANA,0,2,"GAS");
        assertEquals(1,new CustodyGasAccountRepository(jdbc).insert(UUID.randomUUID(),TENANT,attach(payer,"sol-gas"),"SOLANA","devnet","SOL",BigDecimal.ZERO,null));
    }
    private SolanaSigningPayload draft(SolanaSubmissionService.Operation op,String symbol){
        boolean token=!symbol.equals("SOL");String mint=token?new TokenConfigRepository(jdbc).findDefinition("SOLANA","devnet",symbol).orElseThrow().getContractAddress():null;
        int decimals=token?new TokenConfigRepository(jdbc).findDefinition("SOLANA","devnet",symbol).orElseThrow().getDecimals():9;
        String feePayer=token?payer.getAddress():source.getAddress();
        var unsigned=MpcSolanaTransactionCodec.payment(source.getAddress(),feePayer,recipient,mint,BigDecimal.ONE.movePointRight(decimals).longValueExact(),decimals,Base58.encode(new byte[32]),SolanaSubmissionService.memo(op));
        return new SolanaSigningPayload(symbol,BigDecimal.ONE,op.kind()==SolanaSubmissionService.Kind.COLLECTION?BigDecimal.ZERO:new BigDecimal("0.1"),decimals,mint,source.getAddress(),recipient,feePayer,1000,unsigned);
    }
    private MpcSolanaTransactionCodec.Signed prepared(SolanaSubmissionService.Operation op,String symbol){var request=service.reserve(op,source,draft(op,symbol));service.sign(op,request.id());return service.commit(op,request.id());}
    private boolean settle(SolanaSubmissionService.Operation op,MpcSolanaTransactionCodec.Signed signed,boolean failed){
        var payload=service.payload(service.intent(op).orElseThrow());return service.settle(op,SolanaReceiptTestSupport.receipt(payload,signed,failed,true),SolanaReceiptTestSupport.status(failed),124,2);
    }
    @Test void exactRawAndSignatureCommitBeforeObservationAndRetryCannotChangeOriginalMessage(){
        var signed=prepared(operation(),"SOL");var request=service.intent(operation()).orElseThrow();
        assertEquals(signed.signature(),orders.find("SOLANA",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(signed.transactionBase64(),transactions.findSigned(signed.signature()).orElseThrow().getRawPayload());
        assertEquals("PREPARED",transactions.findSigned(signed.signature()).orElseThrow().getStatus());
        assertEquals(request.id(),service.reserve(operation(),source,null).id());assertEquals(signed,service.commit(operation(),request.id()));
        assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt",Integer.class));
        service.observed(operation(),signed.signature());service.restoreSent(operation(),signed.signature());assertEquals("SENT",orders.find("SOLANA",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void unknownSignatureCannotReleaseIntentOrFrozenPrincipal(){
        var request=service.reserve(operation(),source,draft(operation(),"SOL"));fixture.signingPending.set(true);
        assertThrows(MpcPendingException.class,()->service.sign(operation(),request.id()));service.unknown(operation(),"pending");
        assertEquals(0,service.releaseUnsigned(operation()));assertNull(orders.find("SOLANA",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(0,new BigDecimal("1.1").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"SOLANA","SOL",ACCOUNT).orElseThrow().getLockedBalance()));
        fixture.signingPending.set(false);service.sign(operation(),request.id());var signed=service.commit(operation(),request.id());service.restoreSent(operation(),signed.signature());
    }
    @Test void noIntentMayRearmUnderOwnedBusinessLock(){service.unknown(operation(),"before intent");assertEquals(1,service.releaseUnsigned(operation()));assertEquals("FROZEN",orders.find("SOLANA",REFERENCE,TENANT).orElseThrow().getStatus());}
    @Test void sameAmountAndBlockhashForDifferentOrdersHaveDifferentBusinessMemoAndSignature(){
        var first=prepared(operation(),"SOL");createOrder("SOL-SECOND","SOL");var second=prepared(operation("SOL-SECOND"),"SOL");assertNotEquals(first.signature(),second.signature());
    }
    @Test void foreignOwnerOrDifferentMemoRecipientPrecisionCannotReserve(){
        assertThrows(RuntimeException.class,()->service.reserve(new SolanaSubmissionService.Operation(UUID.randomUUID(),REFERENCE,SolanaSubmissionService.Kind.WITHDRAWAL),source,draft(operation(),"SOL")));
        assertThrows(RuntimeException.class,()->service.reserve(operation(),source,draft(operation("other"),"SOL")));
        var original=recipient;recipient=source.getAddress();assertThrows(RuntimeException.class,()->service.reserve(operation(),source,draft(operation(),"SOL")));recipient=original;
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
    }
    @Test void rawBindingFailureRollsBackChainRowAndRetainsSelectedSignature(){
        var request=service.reserve(operation(),source,draft(operation(),"SOL"));service.sign(operation(),request.id());
        jdbc.execute("create function public.sol_test_reject_hash() returns trigger language plpgsql as $$ begin if new.tx_hash is not null then raise exception 'reject hash';end if;return new;end $$;create trigger sol_test_reject_hash before update on withdrawal_order for each row execute function public.sol_test_reject_hash()");
        try{assertThrows(RuntimeException.class,()->service.commit(operation(),request.id()));assertEquals(0,jdbc.queryForObject("select count(*) from sol_transaction",Integer.class));assertEquals(MpcSignRequest.State.COMPLETED,service.intent(operation()).orElseThrow().state());}
        finally{jdbc.execute("drop trigger sol_test_reject_hash on withdrawal_order;drop function public.sol_test_reject_hash()");}
        assertNotNull(service.commit(operation(),request.id()));
    }
    @Test void confirmedSuccessDebitsOnceOnlyAfterRequiredConfirmations(){
        var signed=prepared(operation(),"SOL");service.restoreSent(operation(),signed.signature());var payload=service.payload(service.intent(operation()).orElseThrow());
        assertFalse(service.settle(operation(),SolanaReceiptTestSupport.receipt(payload,signed,false,false),SolanaReceiptTestSupport.status(false),123,2));
        assertTrue(settle(operation(),signed,false));assertFalse(settle(operation(),signed,false));
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"SOLANA","SOL",ACCOUNT).orElseThrow();assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("3.9").compareTo(balance.getTotalBalance()));
        assertEquals(0,BigDecimal.valueOf(5000).compareTo(transactions.findConfirmedFeeAtomic("SOLANA",signed.signature()).orElseThrow()));
    }
    @Test void confirmedFailureRefundsOnceAndPreservesActualNetworkFee(){
        var signed=prepared(operation(),"SOL");service.restoreSent(operation(),signed.signature());assertTrue(settle(operation(),signed,true));assertFalse(settle(operation(),signed,true));
        assertEquals("FAILED",orders.find("SOLANA",REFERENCE,TENANT).orElseThrow().getStatus());assertEquals(0,new BigDecimal("5").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"SOLANA","SOL",ACCOUNT).orElseThrow().getTotalBalance()));
        assertEquals(0,BigDecimal.valueOf(5000).compareTo(transactions.findConfirmedFeeAtomic("SOLANA",signed.signature()).orElseThrow()));
    }
    @Test void ledgerFailureRollsBackReceiptAndTheSameEvidenceCanResume(){
        var signed=prepared(operation(),"SOL");service.restoreSent(operation(),signed.signature());
        jdbc.update("update ledger_balance set locked_balance=0,available_balance=5 where chain='SOLANA' and asset_symbol='SOL' and account_id=?",ACCOUNT);
        assertThrows(RuntimeException.class,()->settle(operation(),signed,false));assertEquals("SENT",transactions.findSigned(signed.signature()).orElseThrow().getStatus());assertNull(transactions.findSigned(signed.signature()).orElseThrow().getSlot());
        jdbc.update("update ledger_balance set locked_balance=1.1,available_balance=3.9 where chain='SOLANA' and asset_symbol='SOL' and account_id=?",ACCOUNT);assertTrue(settle(operation(),signed,false));
    }
    @Test void changedCustomerFeeOrWrongEvidenceCannotReleasePrincipal(){
        var signed=prepared(operation(),"SOL");service.restoreSent(operation(),signed.signature());var payload=service.payload(service.intent(operation()).orElseThrow());
        var wrong=SolanaReceiptTestSupport.receipt(payload,signed,false,false).put("slot",124);
        assertThrows(RuntimeException.class,()->service.settle(operation(),wrong,SolanaReceiptTestSupport.status(false),125,1));
        jdbc.update("update withdrawal_order set fee=0.2 where tenant_id=? and order_no=?",TENANT,REFERENCE);assertThrows(RuntimeException.class,()->settle(operation(),signed,false));assertEquals("SENT",orders.find("SOLANA",REFERENCE,TENANT).orElseThrow().getStatus());
    }
    @Test void concurrentConfirmationsDebitExactlyOnce() throws Exception {
        var signed=prepared(operation(),"SOL");service.restoreSent(operation(),signed.signature());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var results=new ArrayList<Future<Boolean>>();for(int i=0;i<12;i++)results.add(pool.submit(()->settle(operation(),signed,false)));int changed=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))changed++;assertEquals(1,changed);}
    }
    @Test void tokenReservesBothSlotsInOneMessageAndTransactionIdUsesGasWitness(){
        gas();createOrder("SOL-TOKEN","USDC");var op=operation("SOL-TOKEN");var request=service.reserve(op,source,draft(op,"USDC"));
        assertEquals(source.getMpcKeyId(),request.command().keyId());assertEquals(2,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        service.sign(op,request.id());var signed=service.commit(op,request.id());var payerSlot=requests.findBusiness(TENANT,ChainType.SOLANA,MpcSignCommand.BusinessType.WITHDRAWAL,"SOL-TOKEN","fee_payer").orElseThrow();
        assertEquals(request.command().preimageHex(),payerSlot.command().preimageHex());assertEquals(Base58.encode(fixture.signing.signature(TENANT,payerSlot.id()).orElseThrow()),signed.signature());
        service.restoreSent(op,signed.signature());assertTrue(settle(op,signed,false));assertEquals(0,BigDecimal.valueOf(2_049_280).compareTo(transactions.findConfirmedFeeAtomic("SOLANA",signed.signature()).orElseThrow()));
    }
    @Test void partialTwoSignerCompletionRetainsBothSlotsAndResumesWithoutNewBlockhash(){
        gas();createOrder("SOL-TOKEN","USDC");var op=operation("SOL-TOKEN");var request=service.reserve(op,source,draft(op,"USDC"));var count=new java.util.concurrent.atomic.AtomicInteger();
        fixture.beforeSignature=()->{if(count.incrementAndGet()==2)fixture.signingPending.set(true);};assertThrows(MpcPendingException.class,()->service.sign(op,request.id()));
        service.unknown(op,"partial signing");assertEquals(0,service.releaseUnsigned(op));assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where state='COMPLETED'",Integer.class));assertThrows(RuntimeException.class,()->service.commit(op,request.id()));
        fixture.beforeSignature=()->{};fixture.signingPending.set(false);service.sign(op,request.id());assertNotNull(service.commit(op,request.id()));assertEquals(2,jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt",Integer.class));
    }
    @Test void secondSlotInsertFailureRollsBackBothIntents(){
        gas();createOrder("SOL-TOKEN","USDC");var op=operation("SOL-TOKEN");
        jdbc.execute("create function public.sol_test_reject_slot() returns trigger language plpgsql as $$ begin if new.signing_slot='transaction' then raise exception 'reject second slot';end if;return new;end $$;create trigger sol_test_reject_slot before insert on wallet_mpc_sign_request for each row execute function public.sol_test_reject_slot()");
        try{assertThrows(RuntimeException.class,()->service.reserve(op,source,draft(op,"USDC")));assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt",Integer.class));}
        finally{jdbc.execute("drop trigger sol_test_reject_slot on wallet_mpc_sign_request;drop function public.sol_test_reject_slot()");}
    }
    @Test void fixedTokenPaymentRecoversAfterTokenAndGasAreDisabled(){
        gas();createOrder("SOL-TOKEN","USDC");var op=operation("SOL-TOKEN");var request=service.reserve(op,source,draft(op,"USDC"));
        jdbc.update("update token_config set enabled=false where chain='SOLANA'");jdbc.update("update custody_gas_account set status='DISABLED' where tenant_id=? and chain='SOLANA'",TENANT);
        assertEquals(request.id(),service.reserve(op,source,null).id());service.sign(op,request.id());assertNotNull(service.commit(op,request.id()));
    }
    @Test void foreignPayerOrMintCannotReserveEvenWithValidPublicMessage(){
        gas();createOrder("SOL-TOKEN","USDC");var op=operation("SOL-TOKEN");var saved=payer;payer=fixture.address(jdbc,TENANT,ChainType.SOLANA,8,3,"DEPOSIT");
        assertThrows(RuntimeException.class,()->service.reserve(op,source,draft(op,"USDC")));payer=saved;
        var valid=draft(op,"USDC");var wrongMint=new Account().getPublicKeyBase58();var unsigned=MpcSolanaTransactionCodec.payment(source.getAddress(),payer.getAddress(),recipient,wrongMint,1_000_000,6,Base58.encode(new byte[32]),SolanaSubmissionService.memo(op));
        var wrong=new SolanaSigningPayload("USDC",BigDecimal.ONE,new BigDecimal("0.1"),6,wrongMint,source.getAddress(),recipient,payer.getAddress(),1000,unsigned);
        assertThrows(RuntimeException.class,()->service.reserve(op,source,wrong));assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
    }
    @Test void collectionRequiresOwnedJointCollectorAndCannotRegressTerminalState(){
        var collections=new CollectionRecordRepository(jdbc);String reference="SOL-COLLECTION";
        collections.create(TENANT,attach(source,"sol-source"),reference,"SOLANA","SOL",source.getAddress(),recipient,BigDecimal.ONE,BigDecimal.ZERO,null);collections.claimSigningDurable(TENANT,"SOLANA",reference);
        var op=new SolanaSubmissionService.Operation(TENANT,reference,SolanaSubmissionService.Kind.COLLECTION);assertThrows(RuntimeException.class,()->service.reserve(op,source,draft(op,"SOL")));
        var collector=fixture.address(jdbc,TENANT,ChainType.SOLANA,0,7,"HOT");recipient=collector.getAddress();jdbc.update("update collection_record set to_address=? where tenant_id=? and collection_no=?",recipient,TENANT,reference);
        var signed=prepared(op,"SOL");service.restoreSent(op,signed.signature());service.unknown(op,"late close");assertEquals("SENT",collections.findStatus(TENANT,"SOLANA",reference).orElseThrow());
        assertTrue(settle(op,signed,false));assertFalse(settle(op,signed,false));service.unknown(op,"late close");assertEquals("CONFIRMED",collections.findStatus(TENANT,"SOLANA",reference).orElseThrow());
    }
}
