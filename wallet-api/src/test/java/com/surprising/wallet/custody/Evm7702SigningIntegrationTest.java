package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.evm7702.MpcEvm7702Codec;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutRequest;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutItem;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutSigner;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutCodec;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702CollectionRequest;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702OperationSigner;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702ContractCodec;
import com.surprising.wallet.common.signing.Evm7702PayoutPayload;
import com.surprising.wallet.common.signing.Evm7702CollectionPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.evm.*;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.coordinator.Evm7702CollectionCoordinator;
import com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.testutil.MpcSigningTestSupport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.utils.Numeric;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Real PG18 boundaries with explicit local crypto substitutes. Actual MPC business has a separate native probe. */
class Evm7702SigningIntegrationTest {
    private static final BigInteger CHAIN=BigInteger.valueOf(31337), PRIORITY=BigInteger.valueOf(1_000_000_000), PRICE=PRIORITY.multiply(BigInteger.TWO);
    private static final String NATIVE="0x"+"00".repeat(20), TARGET="0x"+"ab".repeat(20), DELEGATE="0x"+"cd".repeat(20), HASH="0x"+"11".repeat(32);
    private static final BigDecimal FEE=new BigDecimal("0.002");
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate jdbc;
    private MpcSigningTestSupport local;
    private TransactionTemplate transaction;
    private Evm7702SigningService service;
    private CustodyCryptoService crypto;
    private UUID tenant,operator;
    private ChainAddressRecord authority,relayer,gas;

    @BeforeEach void setup() throws Exception {
        var source=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(source);jdbc=new JdbcTemplate(source);
        tenant=UUID.randomUUID();operator=UUID.randomUUID();
        var tenants=new CustodyTenantTableRepository(jdbc);tenants.insert(tenant,"batch-owner-"+tenant,"Batch owner");tenants.insert(operator,"batch-operator-"+operator,"Configured operator");
        local=new MpcSigningTestSupport(jdbc);transaction=new TransactionTemplate(local.manager);
        authority=local.address(jdbc,tenant,ChainType.ETH,101,0,"HOT");
        relayer=local.address(jdbc,operator,ChainType.ETH,102,0,"EIP7702_RELAYER");
        gas=local.address(jdbc,tenant,ChainType.ETH,103,0,"SYSTEM_GAS");
        var security=new CustodySecurityProperties();security.setSecretMasterKey("11".repeat(32));crypto=new CustodyCryptoService(security);
        service=newService();
    }
    private Evm7702SigningService newService() {
        return new Evm7702SigningService(new WalletMpcSignRequestRepository(jdbc),new ChainAddressRepository(jdbc),new EvmNonceRepository(jdbc),
                new EvmCollectionBatchRepository(jdbc),new EvmWithdrawalBatchRepository(jdbc),local.signing,local.nodes,json,new CustodyGasUsageRepository(jdbc));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void allInnerIntentsNonceAndDispatchFenceShareOneCommit(boolean payout) {
        var fixture=fixture(payout);
        assertThrows(IllegalStateException.class,()->reserve(fixture));
        transaction.executeWithoutResult(status->{ reserve(fixture);status.setRollbackOnly(); });
        assertTrue(service.plan(fixture.operation()).isEmpty());assertEquals("LOCKED",status(fixture));
        assertEquals(0,jdbc.queryForObject("select count(*) from evm_nonce",Integer.class));
        transaction.executeWithoutResult(status->reserve(fixture));
        assertEquals("SIGNING",status(fixture));assertEquals(2,countIntents(fixture));
        local.beforeSignature=()->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("SIGNING",status(fixture));assertEquals(2,countIntents(fixture));
        };
        signInners(fixture);
        assertThrows(IllegalStateException.class,()->transaction.execute(status->service.signature(fixture.operation(),operationInput(fixture))));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void originalPlanAndSignaturesSurviveDisabledAdmissionAndRecreatedCoordinator(boolean payout) {
        var fixture=fixture(payout);var original=transaction.execute(status->reserve(fixture));
        jdbc.update("update custody_tenant set status='SUSPENDED' where id=?",tenant);
        jdbc.update("update chain_profile set enabled=false where chain='ETH'");
        jdbc.update("update chain_address set enabled=false where tenant_id=?",tenant);
        service=newService();assertEquals(json.readTree(json.writeValueAsString(original)),json.readTree(json.writeValueAsString(service.plan(fixture.operation()).orElseThrow())));
        var repeated=transaction.execute(status->service.reserve(fixture.operation(),31337,relayer,BigInteger.valueOf(99),fixture.payload()));
        assertEquals(original.relayerNonce(),repeated.relayerNonce());assertEquals(2,countIntents(fixture));
        signInners(fixture);
        var forged=new Evm7702SigningService.Operation(UUID.randomUUID(),fixture.operation().batchId(),"ETH",fixture.operation().type());
        assertTrue(service.plan(forged).isEmpty());assertTrue(service.outer(forged).isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void outerBytesAndOperatorOwnershipAreBoundBeforeNativeDispatch(boolean payout) throws Exception {
        var fixture=fixture(payout);transaction.executeWithoutResult(status->reserve(fixture));signInners(fixture);
        var wire=wire(fixture);
        var changed=MpcEvm7702Codec.batch(31337,wire.raw().getNonce(),PRIORITY,PRICE,wire.raw().getGasLimit(),DELEGATE,wire.data(),wire.authorizations());
        assertThrows(IllegalStateException.class,()->transaction.execute(status->service.reserveOuter(fixture.operation(),changed,300_000,FEE)));
        assertTrue(service.outer(fixture.operation()).isEmpty());
        createGasAccount(BigDecimal.ONE);
        transaction.executeWithoutResult(status->{ service.reserveOuter(fixture.operation(),wire.raw(),300_000,FEE); });
        assertThrows(Exception.class,()->service.signOuter(fixture.operation()));
        var request=service.outer(fixture.operation()).orElseThrow();assertEquals(operator,request.command().tenantId());
        assertTrue(request.command().businessReference().contains(tenant.toString()));assertTrue(request.command().businessReference().contains(fixture.operation().batchId().toString()));
        assertThrows(DataAccessException.class,()->jdbc.update("update wallet_mpc_sign_request set payload_hex='00' where id=?",request.id()));
        transaction.executeWithoutResult(status->CustodyTestSupport.gasAccounting(jdbc).reserve(tenant,payout?"WITHDRAWAL_BATCH":"COLLECTION_BATCH",fixture.operation().batchId(),FEE));
        local.beforeSignature=()->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("RESERVED",new CustodyGasUsageRepository(jdbc).find(tenant,payout?"WITHDRAWAL_BATCH":"COLLECTION_BATCH",fixture.operation().batchId()).orElseThrow().status());
        };
        service.signOuter(fixture.operation());var selected=service.selected(fixture.operation());
        assertEquals(relayer.getAddress().toLowerCase(java.util.Locale.ROOT),((SignedRawTransaction)TransactionDecoder.decode(selected.rawTransaction())).getFrom().toLowerCase(java.util.Locale.ROOT));
        assertEquals(Hash.sha3(selected.rawTransaction()),selected.transactionHash());assertEquals(4,selected.transactionType());
        assertEquals(selected,service.selected(fixture.operation()));
        service.verifySelectedRaw(fixture.operation(),selected.rawTransaction(),selected.transactionHash());
        String changedRaw=selected.rawTransaction()+"00";
        assertThrows(IllegalStateException.class,()->service.verifySelectedRaw(fixture.operation(),changedRaw,Hash.sha3(changedRaw)));
        assertThrows(IllegalStateException.class,()->service.verifySelectedRaw(fixture.operation(),selected.rawTransaction(),HASH));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownInnerCannotReleaseAndFailedGasReservationCannotAdmitOuter(boolean payout) {
        var fixture=fixture(payout);transaction.executeWithoutResult(status->reserve(fixture));local.signingPending.set(true);
        assertThrows(MpcPendingException.class,()->service.signature(fixture.operation(),operationInput(fixture)));
        if(payout) {
            var coordinator=CustodyTestSupport.evmWithdrawals(jdbc,service,crypto);
            assertThrows(IllegalStateException.class,()->transaction.execute(status->{coordinator.releaseUnbroadcastBatch((Evm7702WithdrawalCoordinator.Batch)fixture.batch(),"UNKNOWN","original admitted");return null;}));
        } else {
            var coordinator=CustodyTestSupport.evmCollections(jdbc,service,crypto);
            assertThrows(IllegalStateException.class,()->transaction.execute(status->{coordinator.releaseUnbroadcastBatch((Evm7702CollectionCoordinator.Batch)fixture.batch(),"UNKNOWN","original admitted");return null;}));
        }
        assertEquals("SIGNING",status(fixture));assertEquals(2,countIntents(fixture));
        local.signingPending.set(false);signInners(fixture);var wire=wire(fixture);createGasAccount(new BigDecimal("0.00001"));
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{
            if(payout)CustodyTestSupport.evmWithdrawals(jdbc,service,crypto).reserveOuter((Evm7702WithdrawalCoordinator.Batch)fixture.batch(),wire.raw(),300_000,FEE);
            else CustodyTestSupport.evmCollections(jdbc,service,crypto).reserveOuter((Evm7702CollectionCoordinator.Batch)fixture.batch(),wire.raw(),300_000,FEE);
            return null;
        }));
        assertTrue(service.outer(fixture.operation()).isEmpty());assertEquals("SIGNING",status(fixture));assertEquals(2,countIntents(fixture));
    }

    private record Fixture(Evm7702SigningService.Operation operation,Object batch,Object payload) { }
    private record Wire(RawTransaction raw,String data,List<AuthorizationTuple> authorizations) { }
    private Fixture fixture(boolean payout) {
        UUID id=UUID.randomUUID();String hash="0x"+id.toString().replace("-","").repeat(2);Instant deadline=Instant.parse("2030-01-01T00:00:00Z");
        var config=new Evm7702ConfigRepository.Configuration(UUID.randomUUID(),"ETH","devtest",CHAIN,1,DELEGATE,HASH,TARGET,HASH,DELEGATE,HASH,
                relayer.getAddress(),relayer.getId(),"ACTIVE",10,2_000_000,new BigDecimal("0.8"),new BigDecimal("1.2"),120,1,true,true,1,10);
        var authorization=new MpcEvm7702Codec.Authorization(CHAIN,authority.getAddress(),DELEGATE,BigInteger.ZERO);
        BigInteger amount=BigInteger.TEN.pow(18);Object batch,payload;
        if(!payout) {
            batch=new Evm7702CollectionCoordinator.Batch(id,tenant,"ETH","devtest","ETH",NATIVE,18,TARGET,hash,deadline,config,relayer,
                    List.of(new Evm7702CollectionCoordinator.ClaimedItem(1,"claim",tenant,UUID.randomUUID(),authority.getAddress(),TARGET,BigDecimal.ONE,amount,authority)));
            var request=new Evm7702CollectionRequest(Numeric.hexStringToByteArray(hash),BigInteger.ZERO,authority.getAddress(),TARGET,NATIVE,TARGET,amount,BigInteger.ZERO,BigInteger.valueOf(deadline.getEpochSecond()),BigInteger.valueOf(180_000));
            payload=new Evm7702CollectionPayload(CHAIN,BigInteger.ZERO,PRIORITY,PRICE,BigInteger.valueOf(30_000_000),List.of(new Evm7702CollectionPayload.Item(authority,authorization,request)));
            new EvmCollectionBatchRepository(jdbc).insert(id,tenant,"ETH","devtest","ETH",NATIVE,18,TARGET,relayer.getAddress(),1,hash,1,json.writeValueAsString(batch));
        } else {
            byte[] withdrawalId=Numeric.hexStringToByteArray(hash);
            batch=new Evm7702WithdrawalCoordinator.Batch(id,tenant,"ETH","devtest","ETH",NATIVE,18,authority.getAddress(),hash,deadline,authority,config,relayer,
                    List.of(new Evm7702WithdrawalCoordinator.ClaimedItem(1,UUID.randomUUID(),UUID.randomUUID(),"claim",TARGET,BigDecimal.ONE,BigDecimal.ZERO,amount,"principal",withdrawalId)));
            var request=new Evm7702PayoutRequest(Numeric.hexStringToByteArray(hash),authority.getAddress(),relayer.getAddress(),List.of(new Evm7702PayoutItem(withdrawalId,BigInteger.ZERO,NATIVE,TARGET,amount,BigInteger.valueOf(120_000))),BigInteger.ZERO,BigInteger.valueOf(deadline.getEpochSecond()));
            payload=new Evm7702PayoutPayload(CHAIN,BigInteger.ZERO,PRIORITY,PRICE,BigInteger.valueOf(30_000_000),authority,authorization,request);
            new EvmWithdrawalBatchRepository(jdbc).insert(id,tenant,"ETH","devtest","ETH",NATIVE,18,authority.getAddress(),relayer.getAddress(),1,hash,1,json.writeValueAsString(batch));
        }
        return new Fixture(new Evm7702SigningService.Operation(tenant,id,"ETH",payout?MpcSignCommand.BusinessType.WITHDRAWAL:MpcSignCommand.BusinessType.COLLECTION),batch,payload);
    }
    private Evm7702SigningService.Plan reserve(Fixture fixture) { return service.reserve(fixture.operation(),31337,relayer,BigInteger.ZERO,fixture.payload()); }
    private Evm7702SigningService.Input operationInput(Fixture fixture) {
        return fixture.payload() instanceof Evm7702CollectionPayload payload?service.input(authority,"operation-0",MpcSignCommand.Purpose.EIP7702_OPERATION,payload.items().getFirst().request(),new Evm7702OperationSigner().digest(CHAIN,payload.items().getFirst().request()))
                :service.input(authority,"operation-0",MpcSignCommand.Purpose.EIP7702_OPERATION,((Evm7702PayoutPayload)fixture.payload()).request(),new Evm7702PayoutSigner().digest(CHAIN,((Evm7702PayoutPayload)fixture.payload()).request()));
    }
    private MpcEvm7702Codec.Authorization authorization(Fixture fixture) {
        return fixture.payload() instanceof Evm7702CollectionPayload payload?payload.items().getFirst().authorization():((Evm7702PayoutPayload)fixture.payload()).authorization();
    }
    private void signInners(Fixture fixture) {
        var authorization=authorization(fixture);
        service.signature(fixture.operation(),service.input(authority,"authorization-0",MpcSignCommand.Purpose.EIP7702_AUTHORIZATION,authorization,MpcEvm7702Codec.digest(authorization)));
        service.signature(fixture.operation(),operationInput(fixture));
    }
    private Wire wire(Fixture fixture) {
        byte[] publicKey=service.publicKey(authority,"ETH");var auth=authorization(fixture);
        var tuples=List.of(MpcEvm7702Codec.authorization(auth,publicKey,service.signature(fixture.operation(),service.input(authority,"authorization-0",MpcSignCommand.Purpose.EIP7702_AUTHORIZATION,auth,MpcEvm7702Codec.digest(auth)))));
        byte[] signature=service.signature(fixture.operation(),operationInput(fixture));String data,to;
        if(fixture.payload() instanceof Evm7702CollectionPayload payload) {
            var request=payload.items().getFirst().request();data=new Evm7702ContractCodec().encodeCollectBatch(List.of(request),List.of(MpcEvm7702Codec.collectionSignature(CHAIN,request,publicKey,signature)));to=TARGET;
        } else { var request=((Evm7702PayoutPayload)fixture.payload()).request();data=new Evm7702PayoutCodec().encode(request,MpcEvm7702Codec.payoutSignature(CHAIN,request,publicKey,signature));to=authority.getAddress(); }
        return new Wire(MpcEvm7702Codec.batch(31337,service.plan(fixture.operation()).orElseThrow().relayerNonce(),PRIORITY,PRICE,BigInteger.valueOf(360_000),to,data,tuples),data,tuples);
    }
    private int countIntents(Fixture fixture) { return jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where business_reference=?",Integer.class,fixture.operation().reference()); }
    private String status(Fixture fixture) { return jdbc.queryForObject("select status from "+(fixture.operation().type()==MpcSignCommand.BusinessType.COLLECTION?"evm_collection_batch":"evm_withdrawal_batch")+" where id=?",String.class,fixture.operation().batchId()); }
    private void createGasAccount(BigDecimal balance) {
        UUID address=UUID.randomUUID();
        jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,derivation_subject,derivation_child) values (?,?,?,'ETH','devtest',?,'batch-gas','API',103,0)",address,tenant,gas.getId(),gas.getAddress());
        jdbc.update("insert into custody_gas_account(id,tenant_id,custody_address_id,chain,network,native_symbol,low_balance_threshold) values (?,?,?,'ETH','devtest','ETH',0.1)",UUID.randomUUID(),tenant,address);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH',?,?,0,?)",tenant,gas.getAccountId(),balance,balance);
    }
}
