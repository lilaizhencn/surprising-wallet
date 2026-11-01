package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.signing.MpcSignCommand;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/** One canonical reviewed-batch/version binding shared by Wallet and the independent authority. */
public final class ApprovalTransactionHash {
    private ApprovalTransactionHash() { }
    public static String version(MpcSignCommand command) {
        if(command.payloadEncoding().equals(com.surprising.wallet.common.signing.Evm7702SigningPayload.ENCODING)) {
            var json=new tools.jackson.databind.ObjectMapper();var payload=com.surprising.wallet.common.signing.Evm7702SigningPayload.decode(command,json);
            return hash(command.payloadEncoding()+":"+java.util.HexFormat.of().formatHex(json.writeValueAsBytes(payload.plan())));
        }
        return hash(command.payloadEncoding()+":"+command.payloadHex());
    }
    public static String business(List<ApprovalRequest> reviewed,MpcSignCommand command) {
        return business(reviewed,command,version(command));
    }
    /** Reconstruct a permanent root binding using a version hash read from the independent store. */
    public static String business(List<ApprovalRequest> reviewed,MpcSignCommand command,String versionHash) {
        if(versionHash==null || !versionHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("fixed approved version hash required");
        if(reviewed.isEmpty() || reviewed.size()>100 || reviewed.stream().map(r->r.intent().id()).distinct().count()!=reviewed.size())
            throw new IllegalArgumentException("one distinct reviewed request is required per batch payment");
        var asset=reviewed.getFirst().assetSnapshot();
        for(var request:reviewed)
            if(!request.intent().tenantId().equals(command.tenantId()) || !request.intent().chain().equals(command.chain().name())
                    || !request.assetSnapshot().equals(asset) || request.intentHash()==null || !request.intentHash().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("reviewed batch owner, network or asset differs");
        String bindings=reviewed.stream().sorted(Comparator.comparing(r->r.intent().id())).map(r->r.intent().id()+":"+r.intentHash())
                .collect(java.util.stream.Collectors.joining("\n"));
        return hash("SW-APPROVED-TRANSACTION-V2:"+bindings+":"+versionHash);
    }
    private static String hash(String value) { return SigningAuthorization.hash(value.getBytes(StandardCharsets.US_ASCII)); }
}
