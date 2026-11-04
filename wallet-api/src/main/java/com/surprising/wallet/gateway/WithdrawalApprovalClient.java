package com.surprising.wallet.gateway;

import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.common.signing.MpcTls;
import com.surprising.wallet.config.WithdrawalApprovalProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.List;
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
    public AuthorityPage list(UUID tenant,String status,int limit,int offset) {
        var page=call("/approval/service/v1/intents?tenantId="+tenant+"&status="+status+"&limit="+limit+"&offset="+offset,null,AuthorityPage.class);
        require(page.items()!=null && page.items().stream().allMatch(item->item.intent().tenantId().equals(tenant)) && page.total()>=0,
                "authority returned a different request owner");
        return page;
    }
    public GrantResponse authorize(SigningCandidate candidate) { return call("/approval/service/v1/signing-grants",candidate,GrantResponse.class); }
    public AutomaticOperation plan(AutomaticParentPlan plan){return call("/approval/service/v1/automatic-plans",plan,AutomaticOperation.class);}
    public CollectionDestinationPolicy collectionDestination(UUID tenant,String chain,String network,String asset,UUID keyId) {
        var path="/approval/service/v1/automatic-plans/destinations?tenantId="+tenant+"&chain="+chain
                +"&network="+network+"&assetSymbol="+asset+"&destinationKeyId="+keyId;
        var policy=call(path,null,CollectionDestinationPolicy.class);
        require(policy.tenantId().equals(tenant) && policy.chain().equals(chain)
                && policy.network().equals(network) && policy.assetSymbol().equals(asset)
                && policy.destinationKeyId().equals(keyId),"independent collection destination policy differs");
        return policy;
    }
    public HotWalletInventoryObservation hotInventory(UUID tenant,String chain,String network,String asset,UUID keyId) {
        var path="/approval/service/v1/automatic-plans/inventory?tenantId="+tenant+"&chain="+chain
                +"&network="+network+"&assetSymbol="+asset+"&destinationKeyId="+keyId;
        var balance=call(path,null,HotWalletInventoryObservation.class);
        require(balance.tenantId().equals(tenant) && balance.keyId().equals(keyId)
                && balance.chain().equals(chain) && balance.network().equals(network)
                && balance.assetSymbol().equals(asset) && balance.availableAtomic().signum()>=0
                && balance.nativeAtomic().signum()>=0 && balance.observedAt()!=null,
                "independent hot inventory differs from requested key or asset");
        return balance;
    }
    public HotWalletIncidentInventoryObservation incidentInventory(UUID tenant,String chain,String network,
            String asset,UUID keyId) {
        var path="/approval/service/v1/automatic-plans/incident-inventory?tenantId="+tenant+"&chain="+chain
                +"&network="+network+"&assetSymbol="+asset+"&keyId="+keyId;
        var balance=call(path,null,HotWalletIncidentInventoryObservation.class);
        require(balance.tenantId().equals(tenant) && balance.keyId().equals(keyId)
                && balance.chain().equals(chain) && balance.network().equals(network)
                && balance.assetSymbol().equals(asset) && "DISABLED".equals(balance.keyState())
                && balance.quarantineHash()!=null && !balance.quarantineHash().isBlank()
                && balance.availableAtomic().signum()>=0 && balance.nativeAtomic().signum()>=0
                && balance.observedAt()!=null
                && (!com.surprising.wallet.common.chain.ChainType.valueOf(chain).isEvm()
                    || (java.util.Set.of("EMPTY","EIP7702_DELEGATED","UNRECOGNIZED_CODE")
                        .contains(balance.accountCodeState())
                        && balance.observedBlockHash()!=null
                        && balance.observedBlockHash().matches("0x[0-9a-f]{64}")
                        && ("EIP7702_DELEGATED".equals(balance.accountCodeState())
                            ? balance.delegatedTo()!=null && balance.delegatedTo().matches("0x[0-9a-f]{40}")
                            : balance.delegatedTo()==null))),
                "independent incident inventory differs from quarantined key or asset");
        return balance;
    }
    public HotWalletSettledSpendObservation settledDirectSpend(HotWalletSettledSpendRequest spend) {
        var observed=call("/approval/service/v1/automatic-plans/settled-direct-spend",
                spend,HotWalletSettledSpendObservation.class);
        require(observed!=null && spend.tenantId().equals(observed.tenantId())
                && spend.keyId().equals(observed.keyId()) && spend.chain().equals(observed.chain())
                && spend.network().equals(observed.network())
                && spend.assetSymbol().equals(observed.assetSymbol())
                && spend.txHash().equalsIgnoreCase(observed.txHash())
                && spend.recipient().equalsIgnoreCase(observed.recipient())
                && spend.amountAtomic().equals(observed.amountAtomic()),
                "independent finalized spend differs from requested withdrawal");
        return observed;
    }
    public HotWalletSettledCollectionObservation settledDirectCollection(HotWalletSettledCollectionRequest collection) {
        var observed=call("/approval/service/v1/automatic-plans/settled-direct-collection",
                collection,HotWalletSettledCollectionObservation.class);
        require(observed!=null && collection.tenantId().equals(observed.tenantId())
                && collection.sourceKeyId().equals(observed.sourceKeyId())
                && collection.destinationKeyId().equals(observed.destinationKeyId())
                && collection.chain().equals(observed.chain())
                && collection.network().equals(observed.network())
                && collection.assetSymbol().equals(observed.assetSymbol())
                && collection.txHash().equalsIgnoreCase(observed.txHash())
                && collection.sourceAddress().equalsIgnoreCase(observed.sourceAddress())
                && collection.destinationAddress().equalsIgnoreCase(observed.destinationAddress())
                && collection.amountAtomic().equals(observed.amountAtomic()),
                "independent finalized collection differs from original transfer");
        return observed;
    }
    public ApprovalMemberRevocation.Receipt revokeMember(ApprovalMemberRevocation revocation) {
        return call("/approval/service/v1/member-revocations",revocation,ApprovalMemberRevocation.Receipt.class);
    }
    public ApprovalKeyQuarantine.Receipt quarantineKey(ApprovalKeyQuarantine quarantine) {
        var receipt = call("/approval/service/v1/keys/quarantine", quarantine, ApprovalKeyQuarantine.Receipt.class);
        require(receipt.tenantId().equals(quarantine.tenantId()) && receipt.keyId().equals(quarantine.keyId())
                && receipt.descriptorHash().equals(quarantine.hash()), "independent key quarantine receipt differs");
        return receipt;
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
    public record AuthorityPage(List<ApprovalRequest> items,long total,int limit,int offset) { }
}
