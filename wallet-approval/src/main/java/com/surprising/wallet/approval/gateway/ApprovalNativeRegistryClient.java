package com.surprising.wallet.approval.gateway;

import com.surprising.wallet.approval.config.ApprovalNativeProperties;
import com.surprising.wallet.common.signing.MpcClient;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Queries the actual holders; no Wallet result JSON or Wallet database identity is accepted as evidence. */
@Component
public class ApprovalNativeRegistryClient implements AutoCloseable {
    private final List<MpcClient> clients;
    public ApprovalNativeRegistryClient(ApprovalNativeProperties properties) {
        var opened=new ArrayList<MpcClient>();
        try {
            for(var node:properties.nodes()) opened.add(MpcClient.connect(node,properties.caFile(),properties.certificateFile(),properties.privateKeyFile(),properties.callTimeout()));
            clients=List.copyOf(opened);
        } catch(RuntimeException failure) { opened.forEach(MpcClient::close);throw failure; }
    }
    public Ceremony observe(MpcClient.Request request) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("native registry observation requires no database transaction");
        if(clients.size()!=3) throw new IllegalStateException("independent native registry profile is not configured");
        if(request.operation()!=MpcClient.Operation.CREATE_KEY && request.operation()!=MpcClient.Operation.REFRESH)
            throw new IllegalArgumentException("registry requires a key ceremony result");
        if(!request.online().equals(clients.stream().map(c->c.node().partyId()).toList())) throw new IllegalArgumentException("key ceremony holders differ from independent registry profile");
        var observations=new ArrayList<Observation>();String publicKey=null,publicAddress=null;
        for(var client:clients) {
            var result=client.result(request);
            if(result.state()!=MpcClient.State.COMPLETED) throw new IllegalStateException("key ceremony has not completed at every independent holder");
            if(publicKey!=null && (!publicKey.equals(result.publicKeyHex()) || !java.util.Objects.equals(publicAddress,result.publicAddress())))
                throw new IllegalStateException("independent holders disagree on registered public identity");
            publicKey=result.publicKeyHex();publicAddress=result.publicAddress();
            observations.add(new Observation(client.node().partyId(),result.intentHash(),result.publicKeyHex(),result.publicAddress()));
        }
        return new Ceremony(request,publicKey,publicAddress,List.copyOf(observations));
    }
    /** Reads an original completed artifact from the holder that will relay it. No transaction-set bytes leave this method. */
    public MoneroPayment moneroPayment(MpcClient.Request original,String party) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("original payment observation requires no database transaction");
        if(original.operation()!=MpcClient.Operation.SIGN || original.algorithm()!=MpcClient.Algorithm.MONERO_NATIVE_2_OF_3 || !original.online().contains(party))
            throw new IllegalArgumentException("relay holder did not participate in the original native payment");
        var client=clients.stream().filter(c->c.node().partyId().equals(party)).findFirst().orElseThrow();var result=client.result(original);
        if(result.state()!=MpcClient.State.COMPLETED)throw new IllegalStateException("original native payment has not completed at its relay holder");
        com.surprising.wallet.common.signing.MoneroMpcResultVerifier.artifact(original,result.publicKeyHex(),result.signatureHex());
        var artifact=new tools.jackson.databind.ObjectMapper().readTree(java.util.HexFormat.of().parseHex(result.signatureHex()));
        return new MoneroPayment(result.publicKeyHex(),result.publicAddress(),result.intentHash(),artifact.path("transactionHash").asText());
    }
    public record MoneroPayment(String publicKeyHex,String publicAddress,String intentHash,String transactionHash) { }
    /** Original public observation descriptors may be supplied; both actual holder results are read independently. No protocol is submitted. */
    public MpcClient.Result moneroSnapshot(MpcClient.Request request) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("independent Monero balance reads require no transaction");
        if(request==null || request.operation()!=MpcClient.Operation.MONERO_OBSERVE || request.algorithm()!=MpcClient.Algorithm.MONERO_NATIVE_2_OF_3 || request.online().size()!=2
                || !request.online().equals(clients.stream().map(c->c.node().partyId()).filter(request.online()::contains).toList()))throw new IllegalArgumentException("original registered two-holder observation required");
        var results=new java.util.LinkedHashMap<String,MpcClient.Result>();
        for(String party:request.online())results.put(party,clients.stream().filter(c->c.node().partyId().equals(party)).findFirst().orElseThrow().result(request));
        if(!com.surprising.wallet.common.signing.MoneroMpcResultVerifier.complete(request,results))throw new IllegalStateException("original balance observation has not completed at both holders");
        var result=results.get(request.online().getFirst());com.surprising.wallet.common.signing.MoneroMpcResultVerifier.observation(request,result);return result;
    }
    @Override @PreDestroy public void close() { clients.forEach(MpcClient::close); }
    public record Observation(String partyId,String intentHash,String publicKeyHex,String publicAddress) { }
    public record Ceremony(MpcClient.Request request,String publicKeyHex,String publicAddress,List<Observation> observations) {
        public Ceremony {
            observations=List.copyOf(observations);
            String expectedIntent=observations.isEmpty()?null:observations.getFirst().intentHash();
            if(request==null || publicKeyHex==null || observations.size()!=3
                    || !observations.stream().map(Observation::partyId).toList().equals(request.online())
                    || observations.stream().anyMatch(o->!publicKeyHex.equals(o.publicKeyHex()) || !java.util.Objects.equals(publicAddress,o.publicAddress())
                        || o.intentHash()==null || !o.intentHash().matches("[0-9a-f]{64}") || !o.intentHash().equals(expectedIntent)))
                throw new IllegalArgumentException("incomplete or inconsistent independent key observations");
        }
    }
}
