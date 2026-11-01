package com.surprising.wallet.chain.monero;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Public daemon facts only. No wallet-rpc, view keys, spend keys or signing operations. */
@Component
public class MoneroDaemonClient {
    private final ChainRpcNodeService nodes;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();

    public MoneroDaemonClient(ChainRpcNodeService nodes,ObjectMapper json) {this.nodes=nodes;this.json=json;}
    public record Network(String name,String genesisHash,long height) { }
    public record Header(long height,String hash,String parentHash) {
        public Header {
            require(height>=0 && hash!=null && hash.matches("[0-9a-f]{64}")
                    && parentHash!=null && parentHash.matches("[0-9a-f]{64}"),"invalid Monero canonical header");
        }
    }
    public Network network(String network) {
        require(Set.of("mainnet","testnet","stagenet","regtest").contains(network),"unsupported Monero network");
        var info=call(network,"get_info",json.createObjectNode());
        require(info.path("nettype").asText().equals(network.equals("regtest")?"fakechain":network)
                && (!network.equals("regtest") || info.path("offline").asBoolean()),"Monero daemon network differs; regtest requires offline fakechain");
        require(info.path("height").isIntegralNumber() && info.path("height").canConvertToLong()
                && info.path("height").asLong()>0,"Monero daemon height is absent");
        return new Network(network,header(network,0).hash(),info.path("height").asLong());
    }
    public Header header(String network,long height) {
        require(height>=0,"negative Monero header height");
        var block=call(network,"get_block_header_by_height",json.createObjectNode().put("height",height)).path("block_header");
        require(block.path("height").isIntegralNumber() && block.path("height").canConvertToLong()
                && block.path("height").asLong()==height && block.path("orphan_status").isBoolean()
                && !block.path("orphan_status").asBoolean(),"Monero daemon returned an absent or orphaned header");
        return new Header(height,block.path("hash").asText(),block.path("prev_hash").asText());
    }
    private JsonNode call(String network,String method,JsonNode parameters) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"Monero daemon RPC must run outside a database transaction");
        var body=json.createObjectNode().put("jsonrpc","2.0").put("id","wallet-monero-public").put("method",method).set("params",parameters);
        return nodes.withFailover("XMR",network,"daemon",node->execute(node,body));
    }
    private JsonNode execute(ChainRpcNode node,JsonNode body) {
        String base=node.getRpcUrl().replaceAll("/+$","");
        URI endpoint=URI.create(base.endsWith("/json_rpc")?base:base+"/json_rpc");
        require(endpoint.getUserInfo()==null && endpoint.getQuery()==null && endpoint.getFragment()==null,"Monero daemon credentials must use node authentication fields");
        try {
            var response=send(node,endpoint,body,null);
            if(response.statusCode()==401 && node.getUsername()!=null && node.getPassword()!=null) {
                String challenge=response.headers().allValues("WWW-Authenticate").stream()
                        .filter(value->value.toLowerCase(Locale.ROOT).startsWith("digest ")).findFirst().orElseThrow();
                response=send(node,endpoint,body,MoneroDigestAuth.digestAuthorization("POST",endpoint,node.getUsername(),node.getPassword(),challenge));
            }
            require(response.statusCode()==200,"Monero daemon HTTP request failed");
            var decoded=json.readTree(response.body());
            require(!decoded.hasNonNull("error") && decoded.path("id").asText().equals("wallet-monero-public")
                    && decoded.path("result").isObject(),"Monero daemon returned no matching public result");
            var result=decoded.path("result");
            require(result.path("status").asText().equals("OK") && !result.path("untrusted").asBoolean(),"Monero daemon public result is not trusted and ready");
            return result;
        } catch(IOException error){throw new IllegalStateException("Monero daemon response unavailable",error);}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("Monero daemon call interrupted",error);}
    }
    private HttpResponse<byte[]> send(ChainRpcNode node,URI endpoint,JsonNode body,String authorization) throws IOException,InterruptedException {
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        if(authorization!=null)request.header("Authorization",authorization);
        else if(!"DIGEST".equalsIgnoreCase(node.getAuthType()))nodes.applyAuthHeaders(request,node);
        return http.send(request.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),1024*1024));
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
