package com.surprising.wallet.approval.gateway;

import com.surprising.wallet.approval.config.ApprovalUtxoRpcProperties;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;

/** Read-only independent consensus facts, outside database transactions. Never signs, unlocks or broadcasts. */
@Component
public class ApprovalUtxoRpcClient implements AutoCloseable {
    private final Map<String,Endpoint> endpoints=new HashMap<>();
    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration timeout;
    public ApprovalUtxoRpcClient(ApprovalUtxoRpcProperties properties,ObjectMapper json,Clock clock) throws java.io.IOException {
        this.json=json;this.clock=clock;this.timeout=properties.timeout();
        for(var profile:properties.profiles())endpoints.put(profile.chain()+":"+profile.network(),new Endpoint(profile,credentials(profile.basicAuthFile())));
        http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public Observation observe(ApprovalAssetSnapshot asset,List<Outpoint> outpoints) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"independent chain observation requires no database transaction");
        require(!outpoints.isEmpty() && outpoints.size()<=1000 && outpoints.stream().distinct().count()==outpoints.size(),"invalid independent UTXO outpoint set");
        var endpoint=endpoints.get(asset.chain()+":"+asset.network());
        require(endpoint!=null && asset.networkIdentity().equals("utxo:"+endpoint.profile.genesisHash()) && asset.tokenIdentifier()==null
                && asset.symbol().equals(asset.chain()) && asset.nativeSymbol().equals(asset.chain()) && asset.decimals()==8 && asset.nativeDecimals()==8,
                "independent UTXO RPC/network profile is missing or differs from the reviewed asset");
        long deadline=System.nanoTime()+timeout.toNanos();
        var genesis=rpc(endpoint,"getblockhash",List.of(0),deadline);
        require(genesis.isString() && genesis.asText().equals(endpoint.profile.genesisHash()),"independent node genesis differs from the approved network");
        var info=rpc(endpoint,"getblockchaininfo",List.of(),deadline);
        require(info.isObject() && info.path("chain").asText().equals(endpoint.profile.rpcChainName())
                && info.path("initialblockdownload").isBoolean() && !info.path("initialblockdownload").asBoolean(),"independent UTXO node is on another network or still synchronizing");
        String best=info.path("bestblockhash").asText();require(best.matches("[0-9a-f]{64}"),"independent node tip is missing");
        var outputs=new ArrayList<Output>();
        for(var outpoint:outpoints) {
            var output=rpc(endpoint,"gettxout",List.of(outpoint.txId(),outpoint.index(),true),deadline);
            require(output.isObject() && output.path("bestblock").asText().equals(best) && output.path("value").isNumber()
                    && output.path("confirmations").isIntegralNumber() && output.path("coinbase").isBoolean(),"independent input is missing, spent or observed across different chain tips");
            long confirmations=output.path("confirmations").bigIntegerValue().longValueExact();
            require(confirmations>=endpoint.profile.minimumConfirmations(),"independent input lacks required confirmations");
            long amount=output.path("value").decimalValue().movePointRight(8).toBigIntegerExact().longValueExact();
            String script=output.path("scriptPubKey").path("hex").asText();
            require(amount>0 && script.matches("(?:[0-9a-f]{2}){1,10000}"),"independent input amount or lock script is invalid");
            outputs.add(new Output(outpoint,amount,script,confirmations,output.path("coinbase").asBoolean()));
        }
        return new Observation(asset.chain(),asset.network(),endpoint.profile.genesisHash(),best,clock.instant(),outputs);
    }
    /** Local installed permissions only; this accessor performs no network I/O. */
    public RbfPolicy rbfPolicy(ApprovalAssetSnapshot asset) {
        var endpoint=endpoints.get(asset.chain()+":"+asset.network());
        require(endpoint!=null && asset.networkIdentity().equals("utxo:"+endpoint.profile.genesisHash()),"independent Bitcoin-like profile required");
        var p=endpoint.profile;return new RbfPolicy(p.maximumRbfVersions(),p.maximumRbfFeeRate(),p.maximumRbfFeeAtomic());
    }
    public record RbfPolicy(int maximumVersions,long maximumFeeRate,long maximumFeeAtomic) { public boolean enabled(){return maximumVersions>0;} }
    private JsonNode rpc(Endpoint endpoint,String method,List<?> parameters,long deadline) {
        long remaining=deadline-System.nanoTime();require(remaining>0,"independent UTXO observation timed out");
        String id=UUID.randomUUID().toString();var body=Map.of("jsonrpc","1.0","id",id,"method",method,"params",parameters);
        var request=HttpRequest.newBuilder(endpoint.profile.endpoint()).timeout(Duration.ofNanos(remaining))
                .header("Content-Type","application/json").header("Accept","application/json");
        if(endpoint.authorization!=null)request.header("Authorization",endpoint.authorization);
        var future=http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),2*1024*1024));
        try {
            var response=future.get(remaining,TimeUnit.NANOSECONDS);require(response.statusCode()==200,"independent UTXO node did not accept the read-only call");
            var result=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response.body());
            require(result!=null && result.isObject() && result.path("id").asText().equals(id) && (!result.has("error") || result.path("error").isNull()) && result.has("result"),
                    "independent UTXO node returned an invalid read-only response");
            return result.path("result");
        } catch(tools.jackson.core.JacksonException invalid) { throw new IllegalStateException("independent UTXO node returned invalid JSON"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException("independent UTXO observation interrupted"); }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new IllegalStateException("independent UTXO node unavailable"); }
        finally { if(!future.isDone())future.cancel(true); }
    }
    private static String credentials(java.nio.file.Path path) throws java.io.IOException {
        if(path==null)return null;
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.size(path)<=8192
                && Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")),"independent RPC credentials require an owner-only 0600 regular file");
        String value=Files.readString(path,StandardCharsets.UTF_8);
        if(value.endsWith("\r\n"))value=value.substring(0,value.length()-2);else if(value.endsWith("\n"))value=value.substring(0,value.length()-1);
        require(value.indexOf(':')>0 && value.chars().noneMatch(Character::isISOControl),"independent RPC credentials must contain one username:password line");
        return "Basic "+Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    @Override public void close() { http.close(); }
    private static final class Endpoint {
        private final ApprovalUtxoRpcProperties.Profile profile;private final String authorization;
        private Endpoint(ApprovalUtxoRpcProperties.Profile profile,String authorization) { this.profile=profile;this.authorization=authorization; }
    }
    public record Outpoint(String txId,int index) {
        public Outpoint { if(txId==null || !txId.matches("[0-9a-f]{64}") || index<0)throw new IllegalArgumentException("invalid independent outpoint"); }
    }
    public record Output(Outpoint outpoint,long amountAtomic,String scriptPubKeyHex,long confirmations,boolean coinbase) { }
    public record Observation(String chain,String network,String genesisHash,String bestBlock,Instant observedAt,List<Output> outputs) {
        public Observation { outputs=List.copyOf(outputs); }
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
}
