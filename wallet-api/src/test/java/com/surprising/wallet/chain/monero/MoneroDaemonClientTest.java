package com.surprising.wallet.chain.monero;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.service.ChainRpcNodeService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoneroDaemonClientTest {
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private String nettype="fakechain";
    private boolean orphan;
    private final List<String> methods=new CopyOnWriteArrayList<>();
    @AfterEach void close(){if(server!=null)server.stop(0);}
    private MoneroDaemonClient client() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/json_rpc",exchange->{
            byte[] body=exchange.getRequestBody().readAllBytes();String authorization=exchange.getRequestHeaders().getFirst("Authorization");
            if(authorization==null) {
                exchange.getResponseHeaders().add("WWW-Authenticate","Digest realm=\"monero-test\",nonce=\"fixed-test-nonce\",algorithm=MD5-sess,qop=\"auth\"");
                exchange.sendResponseHeaders(401,0);exchange.close();return;
            }
            var values=new java.util.HashMap<String,String>();var matcher=Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,]+))").matcher(authorization);
            while(matcher.find())values.put(matcher.group(1),matcher.group(2)!=null?matcher.group(2):matcher.group(3).trim());
            assertEquals("MD5-sess",values.get("algorithm"));assertEquals("/json_rpc",values.get("uri"));assertEquals("auth",values.get("qop"));
            String ha1=md5(md5("daemon-user:monero-test:daemon-secret")+":fixed-test-nonce:"+values.get("cnonce"));
            assertEquals(md5(ha1+":fixed-test-nonce:"+values.get("nc")+":"+values.get("cnonce")+":auth:"+md5("POST:/json_rpc")),values.get("response"));
            var request=json.readTree(body);String method=request.path("method").asText();methods.add(method);
            var result=json.createObjectNode().put("status","OK").put("untrusted",false);
            if(method.equals("get_info"))result.put("nettype",nettype).put("offline",true).put("height",110);
            else {
                assertEquals("get_block_header_by_height",method);long height=request.path("params").path("height").asLong();
                result.set("block_header",json.createObjectNode().put("height",height).put("orphan_status",orphan)
                        .put("hash",(height==0?"11":"22").repeat(32)).put("prev_hash","00".repeat(32)));
            }
            var response=json.createObjectNode().put("jsonrpc","2.0");response.set("id",request.path("id"));response.set("result",result);
            byte[] bytes=json.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var repository=mock(ChainRpcNodeRepository.class);
        when(repository.listRuntimeNodes("XMR","regtest","daemon-test","daemon")).thenReturn(List.of(ChainRpcNode.builder()
                .rpcUrl("http://127.0.0.1:"+server.getAddress().getPort()).authType("DIGEST").username("daemon-user").password("daemon-secret").build()));
        return new MoneroDaemonClient(new ChainRpcNodeService(repository,"daemon-test",1),json);
    }
    @Test void readsActualPublicShapeThroughPathBoundMd5SessAuthentication() throws Exception {
        var client=client();var network=client.network("regtest");assertEquals("11".repeat(32),network.genesisHash());assertEquals(110,network.height());
        assertEquals(100,client.header("regtest",100).height());
        assertEquals(List.of("get_info","get_block_header_by_height","get_block_header_by_height"),methods);
    }
    @Test void wrongNetworkAndOrphanHeadersCannotBecomePublicAnchors() throws Exception {
        var client=client();nettype="mainnet";assertThrows(IllegalStateException.class,()->client.network("regtest"));
        orphan=true;assertThrows(IllegalStateException.class,()->client.header("regtest",100));
    }
    @Test void noDaemonCallMayHoldTheAddressDatabaseTransaction() throws Exception {
        var client=client();TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(IllegalStateException.class,()->client.network("regtest"));}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertTrue(methods.isEmpty());
    }
    private static String md5(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
