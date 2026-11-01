package com.surprising.wallet.custody;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.chain.evm.*;
import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PG18 business phases with a test-only signing substitute; actual MPC is verified by MpcSigningNativeProbe. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class EvmSubmissionIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String RECIPIENT="0x"+"34".repeat(20);
    private final Credentials key=newSigner(); // Generated in memory for this isolated test only.
    private final String sender=key.getAddress().toLowerCase(Locale.ROOT);
    private final String orderNo="durable-evm-"+UUID.randomUUID();
    private final AtomicInteger signatures=new AtomicInteger();
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private EvmSubmissionService submissions;
    private WithdrawalOrderRepository orders;
    private EvmTransactionRepository transactions;
    private EvmNonceRepository nonces;
    private CollectionRecordRepository collections;
    private CustodyAssetRecoveryRepository recoveries;
    private WithdrawalSettlementService settlements;
    private HttpServer server;
    private static final Object LOST_REPLY = new Object();
    private MpcSigningService signing;
    private MpcNodes mpcNodes;
    private WalletMpcSignRequestRepository requests;
    private ChainAddressRecord source;
    private EvmHttpServiceFactory http;

    private static Credentials newSigner() {
        try { return Credentials.create(org.web3j.crypto.Keys.createEcKeyPair()); }
        catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("cannot create isolated EVM test signer", failure);
        }
    }

    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource(); CustodyIntegrationDatabase.reset(ds);
        jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=(network='sepolia'),withdraw_enabled=true where chain='ETH'");
        orders=new WithdrawalOrderRepository(jdbc); transactions=new EvmTransactionRepository(jdbc);
        nonces=new EvmNonceRepository(jdbc); collections=new CollectionRecordRepository(jdbc);
        recoveries=new CustodyAssetRecoveryRepository(jdbc);
        var mapper = new ObjectMapper();
        var manager = new DataSourceTransactionManager(ds);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        requests = new WalletMpcSignRequestRepository(jdbc);
        var attempts = new WalletMpcSignAttemptRepository(jdbc, mapper);
        var keys = new WalletMpcKeyRepository(jdbc);
        var epochs = new WalletMpcKeyEpochRepository(jdbc, mapper);
        int namespace = new CustodyTenantTableRepository(jdbc).findById(TENANT).orElseThrow().derivationNamespace();
        var parties = com.surprising.wallet.testutil.MpcSigningTestSupport.PARTIES;
        var metadata = keys.reserve(UUID.randomUUID(), TENANT, "EVM", 42, namespace, 0, "ECDSA_SECP256K1");
        epochs.create(new MpcClient.Request(UUID.randomUUID(), TENANT, metadata.id(), 1,
                MpcClient.Operation.CREATE_KEY, MpcClient.Algorithm.ECDSA_SECP256K1, parties, ""));
        var fixture = org.bitcoinj.crypto.ECKey.fromPrivate(key.getEcKeyPair().getPrivateKey());
        String publicKey = HexFormat.of().formatHex(fixture.getPubKey());
        keys.activate(TENANT, metadata.id(), 1, publicKey);
        epochs.observations(TENANT, metadata.id(), 1, MpcKeyEpoch.State.ACTIVE, Map.of());
        source = ChainAddressRecord.builder().tenantId(TENANT).mpcKeyId(metadata.id()).chain("ETH").assetSymbol("ETH")
                .address(sender).ownerAddress(sender).accountId(sender).userId(42L).biz(namespace).addressIndex(0L).build();
        byte[] master = new byte[32]; new java.security.SecureRandom().nextBytes(master);
        var security = new CustodySecurityProperties(); security.setSecretMasterKey(HexFormat.of().formatHex(master));
        Arrays.fill(master, (byte)0);
        var crypto = new CustodyCryptoService(security);
        signing = spy(new MpcSigningService(new CustodyTenantTableRepository(jdbc), keys, epochs, requests, attempts, crypto, mapper, manager));
        mpcNodes = mock(MpcNodes.class);
        when(mpcNodes.online()).thenReturn(parties.subList(0,2));
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(), "MPC may not sign inside a business transaction");
            UUID id = call.getArgument(1);
            return transaction.execute(status -> {
                var request = requests.lock(TENANT, id).orElseThrow();
                if (request.state() != MpcSignRequest.State.COMPLETED) {
                    signatures.incrementAndGet();
                    byte[] signature = fixture.sign(org.bitcoinj.base.Sha256Hash.wrap(HexFormat.of().parseHex(request.command().preimageHex()))).encodeToDER();
                    var bound = Map.of("tenantId", TENANT, "requestId", id, "attemptId", request.currentAttemptId(), "keyId", metadata.id(),
                            "epoch", 1, "publicKeyHex", publicKey, "preimageHex", request.command().preimageHex(), "signatureHex", HexFormat.of().formatHex(signature),
                            "authenticatedParties", List.of(attempts.find(TENANT, request.currentAttemptId()).orElseThrow().request().online().getFirst()));
                    attempts.complete(TENANT, request.currentAttemptId(), crypto.encrypt(mapper.writeValueAsString(bound)), Map.of());
                    requests.recordState(TENANT, id, request.currentAttemptId(), MpcSignRequest.State.COMPLETED);
                }
                return signing.signature(TENANT,id).orElseThrow();
            });
        }).when(signing).awaitSignature(eq(TENANT),any(),same(mpcNodes));
        context=new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->manager);
        context.registerBean(WithdrawalOrderRepository.class,()->orders);
        context.registerBean(CollectionRecordRepository.class,()->collections);
        context.registerBean(EvmTransactionRepository.class,()->transactions);
        context.registerBean(EvmNonceRepository.class,()->nonces);
        context.registerBean(CustodyAssetRecoveryRepository.class,()->recoveries);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(WalletMpcSignRequestRepository.class,()->requests);
        context.registerBean(MpcSigningService.class,()->signing);
        context.registerBean(MpcNodes.class,()->mpcNodes);
        context.registerBean(ObjectMapper.class,()->mapper);
        context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(EvmSubmissionService.class); context.registerBean(WithdrawalSettlementService.class);
        context.refresh(); submissions=context.getBean(EvmSubmissionService.class); settlements=context.getBean(WithdrawalSettlementService.class);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH','principal',5,0,5)",TENANT);
        orders.createForTenant(TENANT,orderNo,42L,"ETH","ETH",sender,"principal",RECIPIENT,BigDecimal.ONE,BigDecimal.ZERO);
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("ETH","ETH","principal",BigDecimal.ONE,TENANT));
        orders.updateStatus(TENANT,"ETH",orderNo,"FROZEN",sender,null,null);
        assertEquals(1,orders.claimSigningDurable(TENANT,"ETH",orderNo,sender));
        http=new EvmHttpServiceFactory();
    }
    @AfterEach void close() { if(server!=null)server.stop(0); if(http!=null)http.close(); if(context!=null)context.close(); }
    private EvmSubmissionService.Operation operation() { return new EvmSubmissionService.Operation(TENANT,orderNo,EvmSubmissionService.Kind.WITHDRAWAL); }
    private MpcSignRequest reserve() {
        return submissions.reserve(operation(), "ETH", source, BigInteger.ZERO, nonce -> EvmSigningPayload.of(
                RawTransaction.createEtherTransaction(nonce, BigInteger.valueOf(1_000_000_000L), BigInteger.valueOf(21_000), RECIPIENT, BigInteger.TEN.pow(18)),
                11155111L, "ETH", null, BigDecimal.ONE, 18, 18));
    }
    private EvmTransactionRecord prepare() {
        var request = reserve();
        submissions.sign(TENANT, request.id());
        return submissions.commit(operation(), "ETH", request.id());
    }

    @Test void committedBytesAndOwnedHashAreReplayedWithoutAllocatingAnotherNonce() throws Exception {
        var first=prepare();
        assertEquals(first.getTxHash(),orders.find("ETH",orderNo,TENANT).orElseThrow().getTxHash());
        assertTrue(transactions.findSigned("ETH",first.getTxHash()).isPresent());
        var duplicate=submissions.reserve(operation(),"ETH",source,BigInteger.valueOf(99),nonce->{fail("replay must not build another intent");return null;});
        submissions.sign(TENANT,duplicate.id());
        var second=submissions.commit(operation(),"ETH",duplicate.id());
        assertEquals(first.getRawPayload(),second.getRawPayload()); assertEquals(1,signatures.get());
        assertEquals(0,BigDecimal.ONE.compareTo(jdbc.queryForObject("select reserved_nonce from evm_nonce where chain='ETH' and address=?",BigDecimal.class,sender)));
    }

    @Test void concurrentPreparationsCommitOnlyOneSignatureAndNonce() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<EvmTransactionRecord> task=()->{start.await();return prepare();};
            var one=pool.submit(task);var two=pool.submit(task);start.countDown();
            assertEquals(one.get(10,TimeUnit.SECONDS).getTxHash(),two.get(10,TimeUnit.SECONDS).getTxHash());
            assertEquals(1,signatures.get()); assertEquals(1,jdbc.queryForObject("select count(*) from evm_tx",Integer.class));
        } finally {pool.shutdownNow();}
    }

    @Test void failureBindingPreservesTheFixedNonceAndSignedMpcFactForRecovery() throws Exception {
        jdbc.execute("create function reject_evm_prepare() returns trigger language plpgsql as $$ begin raise exception 'injected prepare failure'; end $$");
        jdbc.execute("create trigger reject_evm_prepare before update of tx_hash on withdrawal_order for each row execute function reject_evm_prepare()");
        try { assertThrows(RuntimeException.class,this::prepare); }
        finally {jdbc.execute("drop trigger reject_evm_prepare on withdrawal_order");jdbc.execute("drop function reject_evm_prepare()");}
        assertEquals(0,jdbc.queryForObject("select count(*) from evm_tx",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from evm_nonce",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where state='COMPLETED'",Integer.class));
        assertNull(orders.find("ETH",orderNo,TENANT).orElseThrow().getTxHash());
        assertEquals(0L,prepare().getNonce().longValue());
    }

    @Test void acceptedIntentWithoutHashCannotBeReleasedOrAllocatedAgain() {
        var request=reserve();
        assertEquals(0,signatures.get());
        assertEquals(0,submissions.releaseUnsigned(operation(),"ETH"));
        assertEquals("SIGNING",orders.find("ETH",orderNo,TENANT).orElseThrow().getStatus());
        assertNull(orders.find("ETH",orderNo,TENANT).orElseThrow().getTxHash());
        doThrow(new MpcPendingException(request.id(),"INDETERMINATE"))
                .when(signing).awaitSignature(eq(TENANT),eq(request.id()),same(mpcNodes));
        assertThrows(MpcPendingException.class,()->submissions.sign(TENANT,request.id()));
        assertEquals(0,submissions.releaseUnsigned(operation(),"ETH"));
        assertEquals(request.id(),submissions.reserve(operation(),"ETH",source,BigInteger.valueOf(99),nonce->{
            fail("an accepted intent must retain its original nonce and envelope");return null;
        }).id());
        assertEquals(1,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        assertEquals(0,signatures.get());
    }

    @Test void lateSignatureCanBindAnUnknownBroadcastOwner() {
        var request=reserve();
        assertEquals(1,orders.markBroadcastUnknown(TENANT,"ETH",orderNo,sender,"native response was lost"));
        submissions.sign(TENANT,request.id());
        var prepared=submissions.commit(operation(),"ETH",request.id());
        assertEquals(prepared.getTxHash(),orders.find("ETH",orderNo,TENANT).orElseThrow().getTxHash());
        assertEquals(1,orders.recoverPreparedSent(TENANT,"ETH",orderNo,prepared.getTxHash()));
    }

    @Test void invalidEnvelopeRollsBackBothNonceAndIntentBeforeNativeAcceptance() {
        for (int variation=0;variation<4;variation++) {
            final int invalid=variation;
            assertThrows(IllegalStateException.class,()->submissions.reserve(operation(),"ETH",source,BigInteger.ZERO,nonce->
                    EvmSigningPayload.of(RawTransaction.createEtherTransaction(invalid==0 ? nonce.add(BigInteger.ONE):nonce,
                            BigInteger.valueOf(1_000_000_000L),BigInteger.valueOf(21000),invalid==1 ? sender:RECIPIENT,
                            invalid==2 ? BigInteger.ONE:BigInteger.TEN.pow(18)),
                            invalid==3 ? 1:11155111L,"ETH",null,BigDecimal.ONE,18,18)));
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from evm_nonce",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        assertEquals(0,signatures.get());
    }

    @Test void knownBroadcastWithSpentBalanceRecoversBeforeAnyNewFeeOrBalanceCheck() throws Exception {
        var prepared=prepare();
        startRpc(request->{
            assertEquals("eth_getTransactionByHash",request.get("method"));
            return Map.of("hash",prepared.getTxHash());
        });
        assertTrue(service().recover(operation(),"ETH"));
        assertEquals("SENT",orders.find("ETH",orderNo,TENANT).orElseThrow().getStatus());
        assertEquals("SENT",transactions.findSigned("ETH",prepared.getTxHash()).orElseThrow().getStatus());
        assertEquals(1,signatures.get());
    }

    @Test void signedTransactionIsCommittedBeforeItsBytesReachAnL2FeeOracle() throws Exception {
        jdbc.update("update chain_profile set fee_model='op-stack' where chain='ETH' and enabled=true");
        var prepared=prepare();
        var calls=new AtomicInteger();
        var committed=new java.util.concurrent.atomic.AtomicBoolean(true);
        var signedBytesReachedOracle=new java.util.concurrent.atomic.AtomicBoolean();
        startRpc(request->switch(String.valueOf(request.get("method"))) {
            case "eth_getTransactionByHash" -> null;
            case "eth_call" -> {
                calls.incrementAndGet();
                var owner=orders.find("ETH",orderNo,TENANT).orElseThrow();
                committed.compareAndSet(true,prepared.getTxHash().equals(owner.getTxHash())
                        && transactions.findSigned("ETH",owner.getTxHash()).isPresent());
                var parameters=(List<?>)request.get("params");
                String calldata=String.valueOf(((Map<?,?>)parameters.getFirst()).get("data"));
                if(calldata.contains(Numeric.cleanHexPrefix(prepared.getRawPayload()))) signedBytesReachedOracle.set(true);
                yield "0x"+Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(7),64);
            }
            case "eth_getBalance" -> "0x56bc75e2d63100000";
            case "eth_sendRawTransaction" -> prepared.getTxHash();
            default -> throw new IllegalArgumentException("unexpected fee recovery RPC: "+request.get("method"));
        });
        assertTrue(service().recover(operation(),"ETH"));
        assertEquals(2,calls.get());
        assertTrue(committed.get());
        assertTrue(signedBytesReachedOracle.get());
        assertEquals(0,new BigDecimal("0.000021000000000014").compareTo(transactions.findSigned("ETH",prepared.getTxHash()).orElseThrow().getFee()));
    }

    @Test void recoveryErrorsRetainAcceptedIntentUntilTheOriginalSignatureIsBound() {
        var operation=recovery();
        UUID id=UUID.fromString(operation.reference());
        var request=submissions.reserve(operation,"ETH",source,BigInteger.ZERO,nonce->EvmSigningPayload.of(
                RawTransaction.createEtherTransaction(nonce,BigInteger.valueOf(1_000_000_000L),BigInteger.valueOf(21000),RECIPIENT,BigInteger.TEN.pow(18)),
                11155111L,"ETH",null,BigDecimal.ONE,18,18));
        submissions.recoveryError(operation,"ETH","result response unavailable");
        assertEquals("EXECUTING",recoveries.require(id).status());
        assertEquals("result response unavailable",recoveries.require(id).failureReason());
        jdbc.update("update custody_asset_recovery set updated_at=now()-interval '1 hour' where id=?",id);
        assertEquals(0,submissions.releaseUnsigned(operation,"ETH"));
        recoveries.recoverExecutions();
        assertEquals("EXECUTING",recoveries.require(id).status());
        submissions.sign(TENANT,request.id());
        var prepared=submissions.commit(operation,"ETH",request.id());
        submissions.recoveryError(operation,"ETH","broadcast response lost");
        assertEquals("BROADCAST",recoveries.require(id).status());
        assertEquals(prepared.getTxHash(),recoveries.require(id).recoveryTxHash());
        submissions.recoveryError(operation,"ETH","late duplicate failure");
        assertEquals("BROADCAST",recoveries.require(id).status());
    }

    @Test void recoveryBeforeAnyAcceptedIntentMayReturnToApproval() {
        var operation=recovery();
        UUID id=UUID.fromString(operation.reference());
        submissions.recoveryError(operation,"ETH","unsigned estimate rejected");
        assertEquals("APPROVED",recoveries.require(id).status());
        assertTrue(recoveries.claimExecution(id));
        jdbc.update("update custody_asset_recovery set updated_at=now()-interval '1 hour' where id=?",id);
        assertEquals(1,submissions.releaseUnsigned(operation,"ETH"));
        assertEquals("APPROVED",recoveries.require(id).status());
    }

    private EvmSubmissionService.Operation recovery() {
        UUID id=UUID.randomUUID();
        recoveries.insert(id,TENANT,"ETH","ETH","ETH",null,"0x"+id.toString().replace("-","").repeat(2),
                0L,null,sender,BigDecimal.ONE,UUID.randomUUID());
        jdbc.update("update custody_asset_recovery set status='EXECUTING',durable_submission=true,recovery_address=?,verified_amount=1 where id=?",RECIPIENT,id);
        return new EvmSubmissionService.Operation(TENANT,id.toString(),EvmSubmissionService.Kind.RECOVERY);
    }

    @Test void lostBroadcastReplyOnlyRebroadcastsTheCommittedBytes() throws Exception {
        var prepared=prepare(); var calls=new AtomicInteger(); Set<String> unique=ConcurrentHashMap.newKeySet();
        startRpc(request->{
            String method=String.valueOf(request.get("method"));
            if(method.equals("eth_sendRawTransaction")) {
                String raw=String.valueOf(((List<?>)request.get("params")).getFirst());unique.add(raw);
                if(calls.incrementAndGet()==1) return LOST_REPLY; // Node accepted, HTTP reply deliberately lost.
                return prepared.getTxHash();
            }
            if(method.equals("eth_getTransactionByHash")) return calls.get() == 0 ? null : Map.of("hash",prepared.getTxHash());
            if(method.equals("eth_getBalance")) return "0x56bc75e2d63100000";
            throw new AssertionError("Recovery must not requote or reserve a nonce: "+method);
        });
        var service=service();
        try {service.recover(operation(),"ETH");} catch(RuntimeException expectedLostReply) { }
        if(!"SENT".equals(orders.find("ETH",orderNo,TENANT).orElseThrow().getStatus())) assertTrue(service().recover(operation(),"ETH"));
        assertEquals(1,unique.size());assertEquals(prepared.getRawPayload(),unique.iterator().next());
        assertEquals(1,signatures.get()); assertEquals("SENT",orders.find("ETH",orderNo,TENANT).orElseThrow().getStatus());
        assertEquals(0,BigDecimal.ONE.compareTo(jdbc.queryForObject("select reserved_nonce from evm_nonce where chain='ETH' and address=?",BigDecimal.class,sender)));
    }

    @Test void confirmedFailureReleasesPrincipalOnceAndRecordsActualGas() throws Exception {
        var prepared=prepare();orders.recoverPreparedSent(TENANT,"ETH",orderNo,prepared.getTxHash());
        receiptRpc(prepared.getTxHash(),false,true);
        assertTrue(service().confirmWithdrawal(TENANT,"ETH",orderNo));
        assertFalse(service().confirmWithdrawal(TENANT,"ETH",orderNo));
        assertEquals("FAILED",orders.find("ETH",orderNo,TENANT).orElseThrow().getStatus());
        assertTrue(jdbc.queryForObject("select funds_released from withdrawal_order where order_no=?",Boolean.class,orderNo));
        assertEquals(0,new BigDecimal("5").compareTo(jdbc.queryForObject("select available_balance from ledger_balance where account_id='principal'",BigDecimal.class)));
        assertEquals(0,jdbc.queryForObject("select locked_balance from ledger_balance where account_id='principal'",BigDecimal.class).signum());
        assertEquals(0,new BigDecimal("0.000021").compareTo(transactions.findConfirmedFee("ETH",prepared.getTxHash()).orElseThrow()));
        assertEquals(0,orders.updateStatus(TENANT,"ETH",orderNo,"FROZEN",sender,null,null));
    }

    @Test void orphanedFailureReceiptCannotReleasePrincipalOrCloseAnOrder() throws Exception {
        var prepared=prepare();orders.recoverPreparedSent(TENANT,"ETH",orderNo,prepared.getTxHash());
        receiptRpc(prepared.getTxHash(),false,false);
        assertFalse(service().confirmWithdrawal(TENANT,"ETH",orderNo));
        assertEquals("SENT",orders.find("ETH",orderNo,TENANT).orElseThrow().getStatus());
        assertEquals(0,BigDecimal.ONE.compareTo(jdbc.queryForObject("select locked_balance from ledger_balance where account_id='principal'",BigDecimal.class)));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="sw.test.evm.localRpc",matches="http://127\\.0\\.0\\.1:[0-9]+")
    void actualLocalChainPaysOnceAfterAcceptedBroadcastLosesItsReply() throws Exception {
        var url=java.net.URI.create(System.getProperty("sw.test.evm.localRpc"));
        assertEquals("127.0.0.1",url.getHost());
        assertEquals("0xaa36a7",localRpc(url,"eth_chainId",List.of()).get("result"));
        assertTrue(String.valueOf(localRpc(url,"web3_clientVersion",List.of()).get("result")).toLowerCase(Locale.ROOT).contains("hardhat"));
        localRpc(url,"hardhat_reset",List.of());
        localRpc(url,"hardhat_setBalance",List.of(sender,"0x29a2241af62c0000"));
        BigInteger before=new BigInteger(String.valueOf(localRpc(url,"eth_getBalance",List.of(RECIPIENT,"latest")).get("result")).substring(2),16);
        var prepared=prepare();
        var broadcasts=new AtomicInteger();Set<String> rawTransactions=ConcurrentHashMap.newKeySet();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var mapper=new ObjectMapper();var client=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        server.createContext("/",exchange->{
            byte[] requestBody=exchange.getRequestBody().readAllBytes();
            var request=mapper.readValue(requestBody,Map.class);
            boolean broadcast="eth_sendRawTransaction".equals(request.get("method"));
            if(broadcast)rawTransactions.add(String.valueOf(((List<?>)request.get("params")).getFirst()));
            try {
                var response=client.send(java.net.http.HttpRequest.newBuilder(url).header("Content-Type","application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(requestBody)).build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if(broadcast && broadcasts.incrementAndGet()==1) {exchange.close();return;}
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
            } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new java.io.IOException(error);}
            finally {exchange.close();}
        });server.start();
        try {service().recover(operation(),"ETH");} catch(RuntimeException lostReply) { }
        service().recover(operation(),"ETH");
        localRpc(url,"hardhat_mine",List.of("0x40"));
        assertTrue(service().confirmWithdrawal(TENANT,"ETH",orderNo));
        assertFalse(service().confirmWithdrawal(TENANT,"ETH",orderNo));
        BigInteger after=new BigInteger(String.valueOf(localRpc(url,"eth_getBalance",List.of(RECIPIENT,"latest")).get("result")).substring(2),16);
        assertEquals(BigInteger.TEN.pow(18),after.subtract(before));
        assertEquals(1,rawTransactions.size(),"lost replies must retain the original signed bytes");
        // HTTP may retry an abruptly closed POST; the chain must still consume only the original nonce.
        assertTrue(broadcasts.get()>=1);assertEquals(1,signatures.get());
        assertEquals("0x1",localRpc(url,"eth_getTransactionCount",List.of(sender,"latest")).get("result"));
        assertEquals("CONFIRMED",orders.find("ETH",orderNo,TENANT).orElseThrow().getStatus());
        assertEquals(1,jdbc.queryForObject("select count(*) from evm_tx",Integer.class));
        assertEquals(0,new BigDecimal("4").compareTo(jdbc.queryForObject("select available_balance from ledger_balance where account_id='principal'",BigDecimal.class)));
        assertEquals(0,jdbc.queryForObject("select locked_balance from ledger_balance where account_id='principal'",BigDecimal.class).signum());
        assertTrue(transactions.findConfirmedFee("ETH",prepared.getTxHash()).orElseThrow().signum()>0);
    }

    private Map<?,?> localRpc(java.net.URI url,String method,List<?> params) throws Exception {
        var mapper=new ObjectMapper();
        var response=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build().send(java.net.http.HttpRequest.newBuilder(url)
                .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                        mapper.writeValueAsBytes(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params)))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,response.statusCode());var decoded=mapper.readValue(response.body(),Map.class);
        assertFalse(decoded.containsKey("error"),()->String.valueOf(decoded.get("error")));
        return decoded;
    }

    private EvmAccountTransactionService service() {
        var nodes=mock(ChainRpcNodeService.class);
        var node=ChainRpcNode.builder().rpcUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
        when(nodes.withFailover(eq("ETH"),anyString(),any())).thenAnswer(call->((Function<ChainRpcNode,Object>)call.getArgument(2)).apply(node));
        when(nodes.authHeaders(node)).thenReturn(Map.of("Authorization","Bearer test-only-rpc"));
        return new EvmAccountTransactionService(orders,collections,transactions,new ChainProfileRepository(jdbc),new ChainAssetRepository(jdbc),
                nodes,new EvmTransactionBuilder(),http,settlements,submissions,recoveries);
    }
    private void receiptRpc(String hash,boolean success,boolean canonical) throws Exception {
        String blockHash="0x"+"aa".repeat(32);
        startRpc(request->switch(String.valueOf(request.get("method"))) {
            case "eth_getTransactionReceipt" -> Map.of("transactionHash",hash,"from",sender,"to",RECIPIENT,"blockNumber","0x10",
                    "blockHash",blockHash,"gasUsed","0x5208","effectiveGasPrice","0x3b9aca00","status",success ? "0x1":"0x0");
            case "eth_getBlockByNumber" -> Map.of("number","0x10","hash",canonical ? blockHash : "0x"+"bb".repeat(32),"transactions",List.of());
            case "eth_blockNumber" -> "0x100";
            default -> throw new AssertionError("Unexpected receipt RPC");
        });
    }
    private void startRpc(Function<Map<String,Object>,Object> response) throws Exception {
        var mapper=new ObjectMapper();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            if(!"Bearer test-only-rpc".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {exchange.sendResponseHeaders(401,-1);exchange.close();return;}
            var request=(Map<String,Object>)mapper.readValue(exchange.getRequestBody().readAllBytes(),Map.class);
            Object result=response.apply(request);
            if(result==LOST_REPLY) {exchange.close();return;}
            var responseBody = new LinkedHashMap<String,Object>();
            responseBody.put("jsonrpc", "2.0"); responseBody.put("id", request.get("id")); responseBody.put("result", result);
            byte[] body=mapper.writeValueAsBytes(responseBody);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });server.start();
    }
}
