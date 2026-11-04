package com.surprising.wallet.approval.gateway;

import com.surprising.wallet.approval.config.ApprovalChainRpcProperties;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;

/** Bounded read-only transport shared by explicit chain observers. Never accepts a URL, method or credentials from Wallet. */
@Component
public class ApprovalChainRpcClient implements AutoCloseable {
    private static final Set<String> METHODS=Set.of("getGenesisHash","isBlockhashValid","getFeeForMessage","getMinimumBalanceForRentExemption","getBlockHeight",
            "server_info","account_info","ledger","block","status","gas_price","query","EXPERIMENTAL_protocol_config",
            "getMasterchainInfo","getWalletInformation","getAddressInformation","runGetMethod","estimateFee","getAddressBalance","userRole",
            "eth_chainId","eth_getCode","eth_getStorageAt","eth_getTransactionCount","eth_call","eth_getBlockByNumber","eth_getBalance",
            "eth_getTransactionReceipt","eth_getTransactionByHash",
            "getBalance","getTokenAccountBalance","account_lines","get_info","get_block_header_by_height");
    private static final Set<String> PATHS=Set.of("/wallet/getblockbynum","/wallet/getnowblock","/wallet/getchainparameters","/wallet/getaccount","/info",
            "/v1/polkadot/network-info","/v1/polkadot/payment-info","/v1/polkadot/latest-finalized","/v1/polkadot/signing-facts",
            "/v1/polkadot/native-balance","/v1/polkadot/asset-balance","/wallet/triggerconstantcontract","/view");
    private final Map<String,Endpoint> endpoints=new HashMap<>();
    private final HttpClient http;private final ObjectMapper json;private final Clock clock;private final Duration timeout;
    public ApprovalChainRpcClient(ApprovalChainRpcProperties properties,ObjectMapper json,Clock clock) throws java.io.IOException {
        this.json=json;this.clock=clock;this.timeout=properties.timeout();
        for(var profile:properties.profiles())endpoints.put(profile.chain()+":"+profile.network()+":"+profile.role(),new Endpoint(profile,headers(profile.headersFile())));
        http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public Reads open(ApprovalAssetSnapshot asset,String role) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"independent account observation cannot run under database locks");
        var endpoint=endpoints.get(asset.chain()+":"+asset.network()+":"+role);
        require(endpoint!=null && endpoint.profile().networkIdentity().equals(asset.networkIdentity()),"missing independent account-chain RPC/network profile");
        return new Reads(endpoint);
    }
    public GrpcReads grpc(ApprovalAssetSnapshot asset) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"independent native chain observation cannot run under database locks");
        var endpoint=endpoints.get(asset.chain()+":"+asset.network()+":DEFAULT");
        require(endpoint!=null && endpoint.profile().networkIdentity().equals(asset.networkIdentity()),"missing independently installed native chain endpoint");
        return new GrpcReads(endpoint);
    }
    public final class GrpcReads implements AutoCloseable {
        private final io.grpc.ManagedChannel managed;private final io.grpc.Channel channel;
        private final io.grpc.Deadline deadline=io.grpc.Deadline.after(timeout.toNanos(),TimeUnit.NANOSECONDS);
        private GrpcReads(Endpoint endpoint) {
            var uri=endpoint.profile().endpoint();
            require(uri.getPath().isEmpty() || uri.getPath().equals("/"),"native gRPC endpoint cannot contain a path");
            var builder=io.grpc.ManagedChannelBuilder.forAddress(uri.getHost(),uri.getPort()>0?uri.getPort():uri.getScheme().equals("https")?443:80).maxInboundMessageSize(2*1024*1024);
            if(uri.getScheme().equals("https"))builder.useTransportSecurity();else builder.usePlaintext();
            managed=builder.build();var headers=new io.grpc.Metadata();
            endpoint.headers().forEach((name,value)->headers.put(io.grpc.Metadata.Key.of(name.toLowerCase(Locale.ROOT),io.grpc.Metadata.ASCII_STRING_MARSHALLER),value));
            channel=io.grpc.ClientInterceptors.intercept(managed,io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers));
        }
        public io.grpc.Channel channel(){require(!TransactionSynchronizationManager.isActualTransactionActive(),"native reads require no caller transaction");return channel;}
        public io.grpc.Deadline deadline(){return deadline;}
        @Override public void close(){managed.shutdownNow();}
    }
    public final class Reads {
        private final Endpoint endpoint;private final long deadline=System.nanoTime()+timeout.toNanos();
        private Reads(Endpoint endpoint){this.endpoint=endpoint;}
        public String parameter(String name) {var value=endpoint.profile().parameters().get(name);require(value!=null && !value.isBlank(),"independent RPC parameter is required: "+name);return value;}
        public String optionalParameter(String name) {var value=endpoint.profile().parameters().get(name);return value==null || value.isBlank()?null:value;}
        public JsonNode rpc(String method,Object parameters) {
            return rpc(method,parameters,false);
        }
        /** Account absence is meaningful only for XRP activation at one independently selected validated ledger. */
        public JsonNode xrpAccount(String account,String ledgerHash) {
            require(endpoint.profile().chain().equals("XRP") && ledgerHash.matches("[0-9A-Fa-f]{64}"),"fixed XRP validated ledger required");
            return rpc("account_info",List.of(Map.of("account",account,"ledger_hash",ledgerHash,"strict",true)),true);
        }
        private JsonNode rpc(String method,Object parameters,boolean accountAbsence) {
            require(METHODS.contains(method),"read-only RPC method is not allowed");
            String id=UUID.randomUUID().toString();var result=post("",Map.of("jsonrpc","2.0","id",id,"method",method,"params",parameters));
            require(result.isObject() && result.path("id").asText().equals(id) && (!result.has("error") || result.path("error").isNull()) && result.has("result"),"invalid independent read-only RPC response");
            var value=result.path("result");require(!value.has("error") || accountAbsence && value.path("error").asText().equals("actNotFound"),"independent account node rejected read-only RPC");return value;
        }
        public JsonNode rest(String path,Object body) {require(PATHS.contains(path),"read-only chain path is not allowed");return post(path,body);}
        public JsonNode ledger() {require(endpoint.profile().chain().equals("APTOS"),"ledger REST read is limited to the explicit Aptos observer");return exchange("",null);}
        public Instant observedAt(){return clock.instant();}
        private JsonNode post(String path,Object body) {
            return exchange(path,json.writeValueAsBytes(body));
        }
        private JsonNode exchange(String path,byte[] body) {
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"independent account observation cannot run under database locks");
            String base=endpoint.profile().endpoint().toString().replaceAll("/+$","");
            URI uri=URI.create(base+path);
            try {
                var response=send(uri,body,null);
                if(response.statusCode()==401 && endpoint.profile().chain().equals("XMR")) {
                    String credentials=endpoint.headers().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Authorization")).map(Map.Entry::getValue).findFirst().orElse("");
                    require(credentials.startsWith("Basic "),"independent daemon authentication requires installed credentials");
                    String decoded=new String(Base64.getDecoder().decode(credentials.substring(6)),java.nio.charset.StandardCharsets.UTF_8);int colon=decoded.indexOf(':');require(colon>0,"invalid installed daemon credentials");
                    String challenge=response.headers().allValues("WWW-Authenticate").stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith("digest ")).findFirst().orElseThrow(()->new IllegalStateException("independent daemon authentication failed"));
                    String authorization=com.surprising.wallet.sdk.signing.monero.MoneroDigestAuth.digestAuthorization(body==null?"GET":"POST",uri,decoded.substring(0,colon),decoded.substring(colon+1),challenge);
                    response=send(uri,body,authorization);
                }
                require(response.statusCode()==200,"independent chain read failed");
                var result=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response.body());
                require(result!=null && (result.isObject() || result.isArray()),"independent chain returned malformed facts");return result;
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("independent chain read interrupted");}
            catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | tools.jackson.core.JacksonException failure){throw new IllegalStateException("independent chain read unavailable");}
        }
        private HttpResponse<byte[]> send(URI uri,byte[] body,String authorization) throws InterruptedException,java.util.concurrent.ExecutionException,java.util.concurrent.TimeoutException {
            long remaining=deadline-System.nanoTime();require(remaining>0,"independent account observation timed out");
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining)).header("Content-Type","application/json").header("Accept","application/json");
            endpoint.headers().forEach((name,value)->{if(authorization==null || !name.equalsIgnoreCase("Authorization"))request.header(name,value);});
            if(authorization!=null)request.header("Authorization",authorization);
            if(body==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            var future=http.sendAsync(request.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),2*1024*1024));
            try{return future.get(remaining,TimeUnit.NANOSECONDS);}finally {if(!future.isDone())future.cancel(true);}
        }
    }
    private Map<String,String> headers(Path path) throws java.io.IOException {
        if(path==null)return Map.of();
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.size(path)<=8192
                && Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")),"independent node headers require a regular owner-only 0600 file");
        var values=json.readTree(Files.readAllBytes(path));require(values.isObject(),"invalid independent node header file");var result=new HashMap<String,String>();
        for(var field:values.properties()) {
            String name=field.getKey(),value=field.getValue().asText();
            require(Set.of("authorization","x-api-key","api-key","tron-pro-api-key").contains(name.toLowerCase(Locale.ROOT))
                    && field.getValue().isString() && !value.isBlank() && value.chars().noneMatch(Character::isISOControl),"invalid independent node auth header");
            result.put(name,value);
        }
        return Map.copyOf(result);
    }
    @Override public void close(){http.close();}
    private record Endpoint(ApprovalChainRpcProperties.Profile profile,Map<String,String> headers) { }
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
