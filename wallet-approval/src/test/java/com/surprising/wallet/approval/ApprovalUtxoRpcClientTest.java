package com.surprising.wallet.approval;

import com.surprising.wallet.approval.config.ApprovalUtxoRpcProperties;
import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalUtxoRpcClientTest {
    @TempDir Path directory;
    private static final String GENESIS="a".repeat(64),TIP="b".repeat(64),TXID="c".repeat(64),SCRIPT="0014"+"d".repeat(40);
    private static final Instant NOW=Instant.parse("2026-11-01T08:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private ApprovalAssetSnapshot asset() { return new ApprovalAssetSnapshot("BTC","regtest","BTC","BTC","utxo:"+GENESIS,null,8,8,new BigDecimal("0.001")); }
    private List<ApprovalUtxoRpcClient.Outpoint> points() { return List.of(new ApprovalUtxoRpcClient.Outpoint(TXID,1)); }
    private JsonNode valid(String method) {
        return switch(method) {
            case "getblockhash" -> json.valueToTree(GENESIS);
            case "getblockchaininfo" -> json.createObjectNode().put("chain","regtest").put("bestblockhash",TIP).put("initialblockdownload",false);
            case "gettxout" -> json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree("{\"bestblock\":\""+TIP+"\",\"confirmations\":6,\"value\":12345678.12345678,\"coinbase\":false,\"scriptPubKey\":{\"hex\":\""+SCRIPT+"\"}}");
            default -> throw new AssertionError("unexpected node RPC: "+method);
        };
    }
    private ApprovalUtxoRpcProperties properties(URI endpoint,Path credentials,Duration timeout) {
        return new ApprovalUtxoRpcProperties(List.of(new ApprovalUtxoRpcProperties.Profile("BTC","regtest","regtest",GENESIS,endpoint,credentials,2,0,0,0)),timeout);
    }
    private class Node implements AutoCloseable {
        final HttpServer server;final java.util.concurrent.ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor();
        final List<JsonNode> calls=Collections.synchronizedList(new ArrayList<>());
        volatile Function<String,JsonNode> answer=ApprovalUtxoRpcClientTest.this::valid;
        volatile String authorization;volatile boolean redirect,large,slow,aggregate;
        Node() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);
            server.createContext("/",exchange->{
                var request=json.readTree(exchange.getRequestBody().readAllBytes());calls.add(request);authorization=exchange.getRequestHeaders().getFirst("Authorization");
                if(redirect) { exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/leak");exchange.sendResponseHeaders(302,-1);exchange.close();return; }
                if(slow || aggregate)try { Thread.sleep(aggregate?120:400); }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                var envelope=json.createObjectNode().put("id",request.path("id").asText()).putNull("error");
                envelope.set("result",answer.apply(request.path("method").asText()));
                if(large)envelope.put("padding","x".repeat(2*1024*1024)); // Valid RPC JSON: only the byte bound should reject it.
                byte[] body=json.writeValueAsBytes(envelope);
                try {exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}catch(java.io.IOException closed){ /* timeout/limit cancels transport */ }finally { exchange.close(); }
            });server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"); }
        @Override public void close() { server.stop(0);pool.close(); }
    }
    @Test void observesExactAtomicAmountAndScriptFromOnlyReadOnlyCallsOnTheIndependentlyBoundNetwork() throws Exception {
        Path credentials=directory.resolve("rpc.credentials");Files.writeString(credentials,"readonly-user:fixture-password\n");Files.setPosixFilePermissions(credentials,PosixFilePermissions.fromString("rw-------"));
        try(var node=new Node();var client=new ApprovalUtxoRpcClient(properties(node.uri(),credentials,Duration.ofSeconds(3)),json,Clock.fixed(NOW,ZoneOffset.UTC))) {
            var observed=client.observe(asset(),points());assertEquals(1_234_567_812_345_678L,observed.outputs().getFirst().amountAtomic());
            assertEquals(SCRIPT,observed.outputs().getFirst().scriptPubKeyHex());assertEquals(TIP,observed.bestBlock());assertEquals(NOW,observed.observedAt());
            assertEquals(List.of("getblockhash","getblockchaininfo","gettxout"),node.calls.stream().map(r->r.path("method").asText()).toList());
            assertEquals("[\""+TXID+"\",1,true]",node.calls.getLast().path("params").toString());
            assertEquals("Basic "+Base64.getEncoder().encodeToString("readonly-user:fixture-password".getBytes(StandardCharsets.UTF_8)),node.authorization);
        }
    }
    @Test void rejectsDifferentGenesisNetworkSpentInputsAndInconsistentTipOrPrecision() throws Exception {
        for(String scenario:List.of("genesis","network","syncing","spent","tip","confirmations","precision")) {
            try(var node=new Node();var client=new ApprovalUtxoRpcClient(properties(node.uri(),null,Duration.ofSeconds(3)),json,Clock.systemUTC())) {
                node.answer=method->{var result=valid(method);return switch(scenario) {
                    case "genesis" -> method.equals("getblockhash")?json.valueToTree("f".repeat(64)):result;
                    case "network" -> method.equals("getblockchaininfo")?result.asObject().put("chain","main"):result;
                    case "syncing" -> method.equals("getblockchaininfo")?result.asObject().put("initialblockdownload",true):result;
                    case "spent" -> method.equals("gettxout")?json.nullNode():result;
                    case "tip" -> method.equals("gettxout")?result.asObject().put("bestblock","e".repeat(64)):result;
                    case "confirmations" -> method.equals("gettxout")?result.asObject().put("confirmations",1):result;
                    case "precision" -> method.equals("gettxout")?result.asObject().put("value",new BigDecimal("0.000000001")):result;
                    default -> throw new AssertionError();
                };};
                assertThrows(RuntimeException.class,()->client.observe(asset(),points()),scenario);
            }
        }
    }
    @Test void absentProfilesDuplicateOutpointsAndDatabaseTransactionsNeverContactANode() throws Exception {
        try(var node=new Node();var client=new ApprovalUtxoRpcClient(properties(node.uri(),null,Duration.ofSeconds(3)),json,Clock.systemUTC())) {
            assertThrows(IllegalArgumentException.class,()->client.observe(asset(),List.of(points().getFirst(),points().getFirst())));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThrows(IllegalArgumentException.class,()->client.observe(asset(),points())); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertTrue(node.calls.isEmpty());
        }
        try(var client=new ApprovalUtxoRpcClient(new ApprovalUtxoRpcProperties(List.of(),Duration.ofSeconds(3)),json,Clock.systemUTC())) {
            assertThrows(IllegalArgumentException.class,()->client.observe(asset(),points()));
        }
    }
    @Test void noRedirectsBoundedBodiesAndWholeObservationTimeout() throws Exception {
        for(String scenario:List.of("redirect","large","slow","aggregate")) {
            Duration timeout=List.of("slow","aggregate").contains(scenario)?Duration.ofMillis(250):Duration.ofSeconds(3);
            try(var node=new Node();var client=new ApprovalUtxoRpcClient(properties(node.uri(),null,timeout),json,Clock.systemUTC())) {
                node.redirect=scenario.equals("redirect");node.large=scenario.equals("large");node.slow=scenario.equals("slow");node.aggregate=scenario.equals("aggregate");
                var failure=assertTimeoutPreemptively(Duration.ofSeconds(2),()->assertThrows(RuntimeException.class,()->client.observe(asset(),points())));
                if(node.redirect)assertTrue(failure.getMessage().contains("did not accept the read-only call"));
                if(node.aggregate)assertTrue(node.calls.size()>=2 && node.calls.size()<=3);
                else assertEquals(1,node.calls.size());
            }
        }
    }
    @Test void credentialsMustBeOwnerOnlyAndCannotBeEmbeddedInUrlsOrSentToRemotePlainHttp() throws Exception {
        Path credentials=directory.resolve("unsafe.credentials");Files.writeString(credentials,"readonly-user:fixture-password");Files.setPosixFilePermissions(credentials,PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalArgumentException.class,()->new ApprovalUtxoRpcClient(properties(URI.create("http://127.0.0.1:1/"),credentials,Duration.ofSeconds(3)),json,Clock.systemUTC()));
        for(String uri:List.of("http://remote.example/","https://user:password@node.example/","https://node.example/?apiKey=value","https://node.example/#fragment"))
            assertThrows(IllegalArgumentException.class,()->properties(URI.create(uri),null,Duration.ofSeconds(3)));
    }
}
