package com.surprising.wallet.custody;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.surprising.wallet.chain.tron.MpcTronTransactionCodec;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.MpcSigningTestSupport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import org.tron.TronWalletApi;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.*;

/** PG18 ownership/intent/receipt transactions; native crypto is an explicit test-only substitute. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class TronSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REFERENCE="TRON-MPC-TEST",ACCOUNT="tron-mpc-principal";
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport fixture;
    private TronSubmissionService service;
    private WithdrawalOrderRepository orders;
    private TronTransactionRepository transactions;
    private WalletMpcSignRequestRepository requests;
    private ChainAddressRecord source;
    private String recipient;
    private long timestamp;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }

    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='TRON'");
        jdbc.update("update chain_profile set enabled=true where chain='TRON' and network='nile'");
        fixture=new MpcSigningTestSupport(jdbc);
        source=fixture.address(jdbc,TENANT,ChainType.TRON,42,1,"DEPOSIT");
        recipient=TronWalletApi.getAddress(new org.bitcoinj.crypto.ECKey().getPubKey());timestamp=System.currentTimeMillis();
        orders=new WithdrawalOrderRepository(jdbc);transactions=new TronTransactionRepository(jdbc);requests=new WalletMpcSignRequestRepository(jdbc);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->fixture.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);
        context.registerBean(TronTransactionRepository.class,()->transactions);
        context.registerBean(WalletMpcSignRequestRepository.class,()->requests);
        context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));
        context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
        context.registerBean(MpcSigningService.class,()->fixture.signing);context.registerBean(MpcNodes.class,()->fixture.nodes);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(WithdrawalSettlementService.class);
        context.registerBean(ObjectMapper.class,()->json);context.registerBean(TronSubmissionService.class);context.refresh();
        service=context.getBean(TronSubmissionService.class);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'TRON','TRX',?,5,5)",TENANT,ACCOUNT);
        createOrder(REFERENCE,"TRX");
    }
    @AfterEach void close() {if(context!=null)context.close();}
    private TronSubmissionService.Operation operation() {return new TronSubmissionService.Operation(TENANT,REFERENCE,TronSubmissionService.Kind.WITHDRAWAL);}
    private void createOrder(String reference,String symbol) {
        orders.createForTenant(TENANT,reference,42,"TRON",symbol,source.getAddress(),ACCOUNT,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
        if(symbol.equals("TRX")) assertTrue(new LedgerBalanceRepository(jdbc).freeze("TRON","TRX",ACCOUNT,new BigDecimal("1.1"),TENANT));
        orders.updateStatus(TENANT,"TRON",reference,"FROZEN",source.getAddress(),null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"TRON",reference,source.getAddress()));
    }
    private TronSigningPayload draft(TronSubmissionService.Operation operation,String symbol,int decimals) {
        BigInteger atomic=BigInteger.TEN.pow(decimals);
        var action=Chain.Transaction.Contract.newBuilder();String contract=null;long fee=0;
        if(symbol.equals("TRX")) action.setType(Chain.Transaction.Contract.ContractType.TransferContract).setParameter(Any.pack(
                Contract.TransferContract.newBuilder().setOwnerAddress(ApiWrapper.parseAddress(source.getAddress()))
                        .setToAddress(ApiWrapper.parseAddress(recipient)).setAmount(atomic.longValueExact()).build()));
        else {
            contract=new TokenConfigRepository(jdbc).findDefinition("TRON","nile",symbol).orElseThrow().getContractAddress();fee=30_000_000;
            String data="a9059cbb"+"0".repeat(24)+com.surprising.wallet.chain.tron.TronAddressCodec.base58ToHex(recipient).substring(2)+Numeric.toHexStringNoPrefixZeroPadded(atomic,64);
            action.setType(Chain.Transaction.Contract.ContractType.TriggerSmartContract).setParameter(Any.pack(Contract.TriggerSmartContract.newBuilder()
                    .setOwnerAddress(ApiWrapper.parseAddress(source.getAddress())).setContractAddress(ApiWrapper.parseAddress(contract))
                    .setData(ByteString.copyFrom(HexFormat.of().parseHex(data))).build()));
        }
        var tx=Chain.Transaction.newBuilder().setRawData(Chain.Transaction.raw.newBuilder().setTimestamp(timestamp).setExpiration(timestamp+60_000)
                .setRefBlockBytes(ByteString.copyFrom(new byte[2])).setRefBlockHash(ByteString.copyFrom(new byte[8]))
                .setData(ByteString.copyFrom(TronSubmissionService.memo(operation))).setFeeLimit(fee).addContract(action)).build();
        return new TronSigningPayload(symbol,BigDecimal.ONE,operation.kind()==TronSubmissionService.Kind.COLLECTION ? BigDecimal.ZERO:new BigDecimal("0.1"),decimals,MpcTronTransactionCodec.prepare(tx,source.getAddress(),recipient,atomic,contract,fee));
    }
    private MpcSignRequest reserve() {return service.reserve(operation(),source,draft(operation(),"TRX",6));}
    private MpcTronTransactionCodec.Signed prepared() {var request=reserve();service.sign(operation(),request.id());return service.commit(operation(),request.id());}

    @Test void durableResultAndExactRawBindBeforeObservationAndRetryCannotChangeAnything() {
        var signed=prepared();var id=service.intent(operation()).orElseThrow().id();
        assertEquals(signed.txId(),orders.find("TRON",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(signed.transactionHex(),transactions.findSigned(signed.txId()).orElseThrow().getRawPayload());
        assertEquals("PREPARED",transactions.findSigned(signed.txId()).orElseThrow().getStatus());
        assertEquals(id,service.reserve(operation(),source,null).id());
        assertEquals(signed,service.commit(operation(),id));
        assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt",Integer.class));
        service.observed(operation(),signed.txId());service.restoreSent(operation(),signed.txId());
        assertEquals("SENT",orders.find("TRON",REFERENCE,TENANT).orElseThrow().getStatus());
        assertEquals("SENT",transactions.findSigned(signed.txId()).orElseThrow().getStatus());
    }

    @Test void nativeUnknownHasNoHashButCannotReleaseItsInputIntentOrFrozenPrincipal() {
        var request=reserve();fixture.signingPending.set(true);
        assertThrows(MpcPendingException.class,()->service.sign(operation(),request.id()));
        service.unknown(operation(),"pending");assertEquals(0,service.releaseUnsigned(operation()));
        assertNull(orders.find("TRON",REFERENCE,TENANT).orElseThrow().getTxHash());
        assertEquals(0,new BigDecimal("1.1").compareTo(new LedgerBalanceRepository(jdbc).find(TENANT,"TRON","TRX",ACCOUNT).orElseThrow().getLockedBalance()));
        fixture.signingPending.set(false);service.sign(operation(),request.id());
        var signed=service.commit(operation(),request.id());service.restoreSent(operation(),signed.txId());
        assertEquals(request.id(),service.intent(operation()).orElseThrow().id());
    }

    @Test void noIntentAndNoHashMayRearmOnlyUnderTheOwnedBusinessLock() {
        service.unknown(operation(),"pre-signing failure");assertEquals(1,service.releaseUnsigned(operation()));
        assertEquals("FROZEN",orders.find("TRON",REFERENCE,TENANT).orElseThrow().getStatus());
        assertTrue(new LedgerBalanceRepository(jdbc).find(TENANT,"TRON","TRX",ACCOUNT).orElseThrow().getLockedBalance().signum()>0);
    }

    @Test void differentOrdersAtTheSameTimeAndAmountHaveDifferentStableMemoAndTransactionHashes() {
        var first=prepared();createOrder("TRON-MPC-SECOND","TRX");
        var operation=new TronSubmissionService.Operation(TENANT,"TRON-MPC-SECOND",TronSubmissionService.Kind.WITHDRAWAL);
        var request=service.reserve(operation,source,draft(operation,"TRX",6));service.sign(operation,request.id());var second=service.commit(operation,request.id());
        assertNotEquals(first.txId(),second.txId());assertEquals(first,service.commit(operation(),service.intent(operation()).orElseThrow().id()));
    }

    @Test void wrongMemoPrecisionAndForeignOwnerCannotReserveAnyMpcRequest() {
        var foreign=new TronSubmissionService.Operation(UUID.randomUUID(),REFERENCE,TronSubmissionService.Kind.WITHDRAWAL);
        assertThrows(RuntimeException.class,()->service.reserve(foreign,source,draft(operation(),"TRX",6)));
        var other=new TronSubmissionService.Operation(TENANT,"another-business",TronSubmissionService.Kind.WITHDRAWAL);
        assertThrows(IllegalStateException.class,()->service.reserve(operation(),source,draft(other,"TRX",6)));
        assertThrows(IllegalStateException.class,()->service.reserve(operation(),source,draft(operation(),"TRX",7)));
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
    }

    @Test void failedHashBindingRollsBackRawWhileKeepingTheOriginalCompletedSignature() {
        var request=reserve();service.sign(operation(),request.id());
        jdbc.execute("create function wallet_test_reject_tron_hash() returns trigger language plpgsql as $$ begin raise exception 'injected hash error'; end $$");
        jdbc.execute("create trigger wallet_test_reject_tron_hash before update of tx_hash on withdrawal_order for each row execute function wallet_test_reject_tron_hash()");
        try {assertThrows(RuntimeException.class,()->service.commit(operation(),request.id()));}
        finally {jdbc.execute("drop trigger wallet_test_reject_tron_hash on withdrawal_order");jdbc.execute("drop function wallet_test_reject_tron_hash()");}
        assertEquals(0,jdbc.queryForObject("select count(*) from tron_tx",Integer.class));
        assertEquals(MpcSignRequest.State.COMPLETED,requests.find(TENANT,request.id()).orElseThrow().state());
        assertNotNull(service.commit(operation(),request.id()));
    }

    @Test void concurrentReservationsSelectOnePermanentIntentAndNativeAttempt() throws Exception {
        var payload=draft(operation(),"TRX",6);var ids=new HashSet<UUID>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<MpcSignRequest>>();
            for(int i=0;i<12;i++)futures.add(pool.submit(()->service.reserve(operation(),source,payload)));
            for(var future:futures)ids.add(future.get(20,TimeUnit.SECONDS).id());
        }
        assertEquals(1,ids.size());assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt",Integer.class));
    }

    @Test void successfulReceiptSettlesOriginalPrincipalExactlyOnceAndPreservesRaw() {
        var signed=prepared();service.restoreSent(operation(),signed.txId());
        var receipt=receipt(signed,false);
        assertTrue(service.settle(operation(),receipt));assertFalse(service.settle(operation(),receipt));
        assertEquals("CONFIRMED",orders.find("TRON",REFERENCE,TENANT).orElseThrow().getStatus());
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"TRON","TRX",ACCOUNT).orElseThrow();
        assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("3.9").compareTo(balance.getTotalBalance()));
        assertEquals(signed.transactionHex(),transactions.findSigned(signed.txId()).orElseThrow().getRawPayload());
    }

    @Test void failedReceiptReleasesPrincipalOnceAndRetainsActualFailedExecutionFee() {
        var signed=prepared();service.restoreSent(operation(),signed.txId());
        var receipt=receipt(signed,true);
        assertTrue(service.settle(operation(),receipt));assertFalse(service.settle(operation(),receipt));
        assertEquals("FAILED",orders.find("TRON",REFERENCE,TENANT).orElseThrow().getStatus());
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"TRON","TRX",ACCOUNT).orElseThrow();
        assertEquals(0,balance.getLockedBalance().signum());assertEquals(0,new BigDecimal("5").compareTo(balance.getTotalBalance()));
        assertEquals(0,new BigDecimal("0.2").compareTo(transactions.findConfirmedFee("TRON",signed.txId()).orElseThrow()));
        assertEquals(signed.transactionHex(),transactions.findSigned(signed.txId()).orElseThrow().getRawPayload());
    }

    @Test void wrongHashOrChangedCustomerFeeCannotSettleAnyFrozenPrincipal() {
        var signed=prepared();service.restoreSent(operation(),signed.txId());
        var wrong=receipt(signed,false).toBuilder().setId(ByteString.copyFrom(new byte[32])).build();
        assertThrows(IllegalArgumentException.class,()->service.settle(operation(),wrong));
        jdbc.update("update withdrawal_order set fee=0.2 where tenant_id=? and order_no=?",TENANT,REFERENCE);
        assertThrows(IllegalStateException.class,()->service.settle(operation(),receipt(signed,false)));
        assertEquals("SENT",orders.find("TRON",REFERENCE,TENANT).orElseThrow().getStatus());
        assertEquals("SENT",transactions.findSigned(signed.txId()).orElseThrow().getStatus());
        assertEquals(0,transactions.findSigned(signed.txId()).orElseThrow().getFee().signum());
        assertTrue(new LedgerBalanceRepository(jdbc).find(TENANT,"TRON","TRX",ACCOUNT).orElseThrow().getLockedBalance().signum()>0);
    }

    @Test void ledgerFailureRollsBackReceiptFactAndCanResumeTheSameReceipt() {
        var signed=prepared();service.restoreSent(operation(),signed.txId());
        jdbc.update("update ledger_balance set locked_balance=0,available_balance=5 where tenant_id=? and account_id=?",TENANT,ACCOUNT);
        assertThrows(IllegalStateException.class,()->service.settle(operation(),receipt(signed,false)));
        assertEquals("SENT",orders.find("TRON",REFERENCE,TENANT).orElseThrow().getStatus());
        assertEquals("SENT",transactions.findSigned(signed.txId()).orElseThrow().getStatus());
        assertEquals(0,transactions.findSigned(signed.txId()).orElseThrow().getFee().signum());
        jdbc.update("update ledger_balance set locked_balance=1.1,available_balance=3.9 where tenant_id=? and account_id=?",TENANT,ACCOUNT);
        assertTrue(service.settle(operation(),receipt(signed,false)));
    }

    @Test void collectionMustUseAnOwnedJointKeyAndLateUnknownCannotRegressSentOrConfirmed() {
        var target=fixture.address(jdbc,TENANT,ChainType.TRON,0,2,"HOT");
        UUID custody=UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,derivation_subject,derivation_child)
                values (?,?,?,'TRON','nile',?,'collection-source','API',42,1)
                """,custody,TENANT,source.getId(),source.getAddress());
        var collections=new CollectionRecordRepository(jdbc);
        assertEquals(1,collections.create(TENANT,custody,"TRON-COLLECT","TRON","TRX",source.getAddress(),recipient,BigDecimal.ONE,BigDecimal.ZERO,null));
        assertEquals(1,collections.claimSigningDurable(TENANT,"TRON","TRON-COLLECT"));
        var operation=new TronSubmissionService.Operation(TENANT,"TRON-COLLECT",TronSubmissionService.Kind.COLLECTION);
        assertThrows(NoSuchElementException.class,()->service.reserve(operation,source,draft(operation,"TRX",6)));
        jdbc.update("update collection_record set to_address=? where tenant_id=? and collection_no='TRON-COLLECT'",target.getAddress(),TENANT);
        recipient=target.getAddress();
        var request=service.reserve(operation,source,draft(operation,"TRX",6));service.sign(operation,request.id());
        var signed=service.commit(operation,request.id());service.restoreSent(operation,signed.txId());
        service.unknown(operation,"late client close error");assertEquals("SENT",collections.findStatus(TENANT,"TRON","TRON-COLLECT").orElseThrow());
        assertTrue(service.settle(operation,receipt(signed,false)));assertFalse(service.settle(operation,receipt(signed,false)));
        service.unknown(operation,"late error");assertEquals("CONFIRMED",collections.findStatus(TENANT,"TRON","TRON-COLLECT").orElseThrow());
        assertEquals(0,new BigDecimal("0.2").compareTo(jdbc.queryForObject("select fee from collection_record where tenant_id=? and collection_no='TRON-COLLECT'",BigDecimal.class,TENANT)));
    }

    @Test void concurrentReceiptConfirmationsDebitOnlyOnce() throws Exception {
        var signed=prepared();service.restoreSent(operation(),signed.txId());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<12;i++)futures.add(pool.submit(()->service.settle(operation(),receipt(signed,false))));
            int changed=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS))changed++;
            assertEquals(1,changed);
        }
        var balance=new LedgerBalanceRepository(jdbc).find(TENANT,"TRON","TRX",ACCOUNT).orElseThrow();
        assertEquals(0,new BigDecimal("3.9").compareTo(balance.getTotalBalance()));assertEquals(0,balance.getLockedBalance().signum());
    }

    private org.tron.trident.proto.Response.TransactionInfo receipt(MpcTronTransactionCodec.Signed signed,boolean failed) {
        return org.tron.trident.proto.Response.TransactionInfo.newBuilder().setId(ByteString.copyFrom(HexFormat.of().parseHex(signed.txId())))
                .setBlockNumber(123).setFee(200_000).setResultValue(failed ? 1:0).build();
    }

    @Test void tokenContractAndPrecisionComeFromEnabledConfigurationAndSurviveLaterDisable() {
        createOrder("TRON-TOKEN","USDT");
        var operation=new TronSubmissionService.Operation(TENANT,"TRON-TOKEN",TronSubmissionService.Kind.WITHDRAWAL);
        int decimals=new TokenConfigRepository(jdbc).findDefinition("TRON","nile","USDT").orElseThrow().getDecimals();
        var request=service.reserve(operation,source,draft(operation,"USDT",decimals));
        jdbc.update("update token_config set enabled=false where chain='TRON'");
        service.sign(operation,request.id());assertNotNull(service.commit(operation,request.id()));
    }
}
