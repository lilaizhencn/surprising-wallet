package com.surprising.wallet.gateway;

import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.common.signing.MpcTls;
import com.surprising.wallet.config.WithdrawalApprovalProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLParameters;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Direct end-to-end pinned mTLS; browser cookies and forwarded certificate headers are never used. */
@Component
public class WithdrawalApprovalClient implements AutoCloseable {
    private final WithdrawalApprovalProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    public WithdrawalApprovalClient(WithdrawalApprovalProperties properties,ObjectMapper json) {
        this.properties=properties;this.json=json;
        if(properties.origin()==null) { http=null;return; }
        var parameters=new SSLParameters();parameters.setProtocols(new String[]{"TLSv1.3"});parameters.setEndpointIdentificationAlgorithm("HTTPS");
        http=HttpClient.newBuilder().sslContext(MpcTls.context(properties.serverSpki(),properties.caFile(),properties.certificateFile(),properties.privateKeyFile()))
                .sslParameters(parameters).connectTimeout(properties.timeout()).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    public ApprovalRequest submit(WithdrawalIntent intent) {
        var receipt=call("/approval/service/v1/intents",intent,ApprovalRequest.class);
        require(receipt.intent().equals(intent),"authority returned a different withdrawal intent");return receipt;
    }
    public ApprovalRequest status(UUID tenant,UUID requestId) {
        var receipt=call("/approval/service/v1/intents/"+requestId+"?tenantId="+tenant,null,ApprovalRequest.class);
        require(receipt.intent().tenantId().equals(tenant) && receipt.intent().id().equals(requestId),"authority returned a different request owner");return receipt;
    }
    public GrantResponse authorize(SigningCandidate candidate) { return call("/approval/service/v1/signing-grants",candidate,GrantResponse.class); }
    public AutomaticOperation plan(AutomaticParentPlan plan){return call("/approval/service/v1/automatic-plans",plan,AutomaticOperation.class);}
    public ApprovalMemberRevocation.Receipt revokeMember(ApprovalMemberRevocation revocation) {
        return call("/approval/service/v1/member-revocations",revocation,ApprovalMemberRevocation.Receipt.class);
    }
    public GrantResponse authorizeMoneroRelay(MoneroRelayCandidate candidate) {return call("/approval/service/v1/signing-grants/monero-relay",candidate,GrantResponse.class);}
    public tools.jackson.databind.JsonNode register(String group,MpcClient.Request ceremony,String expectedPublicKey) {
        var receipt=call("/approval/service/v1/keys",Map.of("keyGroup",group,"ceremony",ceremony),tools.jackson.databind.JsonNode.class);
        require(receipt.path("tenantId").asText().equals(ceremony.tenantId().toString()) && receipt.path("keyId").asText().equals(ceremony.keyId().toString())
                && receipt.path("epoch").asInt()==ceremony.epoch() && receipt.path("keyGroup").asText().equals(group)
                && receipt.path("publicKeyHex").asText().equals(expectedPublicKey) && receipt.path("algorithm").asText().equals(ceremony.algorithm().name())
                && receipt.path("state").asText().equals("ACTIVE"),"independent public key registration differs");
        return receipt;
    }
    public String verificationKeyHex() { return properties.verificationKeyHex(); }
    private <T> T call(String path,Object body,Class<T> type) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"approval network calls require a committed Wallet transaction");
        if(http==null) throw new IllegalStateException("independent withdrawal approval service is not configured");
        try {
            var request=HttpRequest.newBuilder(properties.origin().resolve(path)).timeout(properties.timeout()).header("Accept","application/json");
            if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            var future=http.sendAsync(request.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),2*1024*1024));
            try {
                var response=future.get(properties.timeout().toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
                require(response.statusCode()==200,"independent approval service did not accept the request");
                return json.readValue(response.body(),type);
            } finally { if(!future.isDone())future.cancel(true); }
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException("approval call interrupted"); }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new IllegalStateException("independent approval service unavailable"); }
    }
    @Override public void close() { if(http!=null) http.close(); }
    private static void require(boolean value,String message) { if(!value) throw new IllegalArgumentException(message); }
    public record GrantResponse(String status,SigningGrant grant,AutomaticOperation automaticOperation) {
        public GrantResponse(String status,SigningGrant grant){this(status,grant,null);}
    }
}
