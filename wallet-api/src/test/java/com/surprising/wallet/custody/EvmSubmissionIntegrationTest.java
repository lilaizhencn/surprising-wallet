package com.surprising.wallet.custody;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.chain.evm.*;
import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.common.chain.ChainRpcNode;
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

/** Real PostgreSQL transactions and real EVM signatures; the isolated RPC simulates lost replies and canonical receipts. */
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
        context=new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(ds));
        context.registerBean(WithdrawalOrderRepository.class,()->orders);
        context.registerBean(CollectionRecordRepository.class,()->collections);
        context.registerBean(EvmTransactionRepository.class,()->transactions);
        context.registerBean(EvmNonceRepository.class,()->nonces);
        context.registerBean(CustodyAssetRecoveryRepository.class,()->recoveries);
        context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(EvmSubmissionService.class); context.registerBean(WithdrawalSettlementService.class);
        context.refresh(); submissions=context.getBean(EvmSubmissionService.class); settlements=context.getBean(WithdrawalSettlementService.class);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH','principal',5,0,5)",TENANT);
        orders.createForTenant(TENANT,orderNo,42L,"ETH","ETH",sender,"principal",RECIPIENT,BigDecimal.ONE,BigDecimal.ZERO);
        assertTrue(new LedgerBalanceRepository(jdbc).freeze("ETH","ETH","principal",BigDecimal.ONE,TENANT));
        orders.updateStatus(TENANT,"ETH",orderNo,"FROZEN",sender,null,null);
        assertEquals(1,orders.claimSigningEvm(TENANT,"ETH",orderNo,sender));
        http=new EvmHttpServiceFactory();
    }
    @AfterEach void close() { if(server!=null)server.stop(0); if(http!=null)http.close(); if(context!=null)context.close(); }
    private EvmSubmissionService.Operation operation() { return new EvmSubmissionService.Operation(TENANT,orderNo,EvmSubmissionService.Kind.WITHDRAWAL); }
    private EvmTransactionRecord sign(BigInteger nonce) {
        signatures.incrementAndGet();
        var raw=RawTransaction.createEtherTransaction(nonce,BigInteger.valueOf(1_000_000_000L),BigInteger.valueOf(21_000),RECIPIENT,BigInteger.TEN.pow(18));
        String bytes=Numeric.toHexString(TransactionEncoder.signMessage(raw,11155111L,key));
        return EvmTransactionRecord.builder().chain("ETH").txHash(Hash.sha3(bytes)).fromAddress(sender).toAddress(RECIPIENT)
                .assetSymbol("ETH").amount(BigDecimal.ONE).fee(new BigDecimal("0.000021"))
                .nonce(nonce.longValueExact()).confirmations(0).status("PREPARED").rawPayload(bytes).build();
    }
    private EvmTransactionRecord prepare() throws Exception { return submissions.prepare(operation(),"ETH",sender,BigInteger.ZERO,this::sign); }

    @Test void committedBytesAndOwnedHashAreReplayedWithoutAllocatingAnotherNonce() throws Exception {
        var first=prepare();
        assertEquals(first.getTxHash(),orders.find("ETH",orderNo,TENANT).orElseThrow().getTxHash());
        assertTrue(transactions.findSigned("ETH",first.getTxHash()).isPresent());
        var second=submissions.prepare(operation(),"ETH",sender,BigInteger.valueOf(99),nonce->{fail("replay must not sign again");return null;});
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

    @Test void failureBindingTheBusinessHashRollsBackNonceAndSignedBytes() throws Exception {
        jdbc.execute("create function reject_evm_prepare() returns trigger language plpgsql as $$ begin raise exception 'injected prepare failure'; end $$");
        jdbc.execute("create trigger reject_evm_prepare before update of tx_hash on withdrawal_order for each row execute function reject_evm_prepare()");
        try { assertThrows(RuntimeException.class,this::prepare); }
        finally {jdbc.execute("drop trigger reject_evm_prepare on withdrawal_order");jdbc.execute("drop function reject_evm_prepare()");}
        assertEquals(0,jdbc.queryForObject("select count(*) from evm_tx",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from evm_nonce",Integer.class));
        assertNull(orders.find("ETH",orderNo,TENANT).orElseThrow().getTxHash());
        assertEquals(0L,prepare().getNonce().longValue());
    }

    @Test void lostBroadcastReplyOnlyRebroadcastsTheCommittedBytes() throws Exception {
        var prepared=prepare(); var calls=new AtomicInteger(); Set<String> unique=ConcurrentHashMap.newKeySet();
        startRpc(request->{
            String method=String.valueOf(request.get("method"));
            if(method.equals("eth_sendRawTransaction")) {
                String raw=String.valueOf(((List<?>)request.get("params")).getFirst());unique.add(raw);
                if(calls.incrementAndGet()==1) return null; // Node accepted, HTTP reply deliberately lost.
                return prepared.getTxHash();
            }
            if(method.equals("eth_getTransactionByHash")) return Map.of("hash",prepared.getTxHash());
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
        assertEquals(1,rawTransactions.size());assertTrue(broadcasts.get()>=2);assertEquals(1,signatures.get());
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
                nodes,mock(AccountSecp256k1KeyService.class),new EvmTransactionBuilder(),http,settlements,submissions,recoveries);
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
            if(result==null) {exchange.close();return;}
            byte[] body=mapper.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });server.start();
    }
}
