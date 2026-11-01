package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.require;
import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.RbfPolicy;
import com.surprising.wallet.approval.model.ApprovalSigningVersion;
import com.surprising.wallet.common.approval.SigningAuthorization;
import com.surprising.wallet.common.signing.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.*;

/** Same exact previous outputs make all admitted BTC versions mutually exclusive on the canonical chain. */
public final class UtxoReplacementVerifier {
    private UtxoReplacementVerifier() { }
    public static UtxoSigningPayload payload(ApprovalSigningVersion version,ObjectMapper json) {
        require(version.payloadEncoding().equals("UTXO_MPC_JSON_V1") && version.versionHash().equals(SigningAuthorization.hash((version.payloadEncoding()+":"+version.payloadHex()).getBytes(StandardCharsets.US_ASCII))),"original UTXO version receipt differs");
        return json.readerFor(UtxoSigningPayload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(HexFormat.of().parseHex(version.payloadHex()));
    }
    public static void verify(UtxoSigningPayload root,UtxoSigningPayload previous,UtxoSigningPayload next,RbfPolicy policy,int versions,boolean newVersion) {
        require(policy.enabled() && (!newVersion || versions<policy.maximumVersions()),"independent BTC replacement permission/version quota is exhausted");
        var original=root.task().request();var before=previous.task().request();var after=next.task().request();
        require(original.asset().chain().equals("BTC") && after.asset().equals(original.asset()) && before.asset().equals(original.asset())
                && root.task().transactionId()==next.task().transactionId() && previous.task().transactionId()==next.task().transactionId()
                && original.tenantId().equals(after.tenantId()) && original.operation()==after.operation() && Objects.equals(original.collectionNo(),after.collectionNo())
                && !original.requestId().equals(after.requestId()) && original.inputs().equals(after.inputs()) && root.unsigned().inputs().equals(next.unsigned().inputs())
                && root.keyIds().equals(next.keyIds()) && root.changeKeyId().equals(next.changeKeyId()) && original.changeAddress().equals(after.changeAddress())
                && original.dustThreshold()==after.dustThreshold(),"BTC replacement changes original ownership, inputs, operation, change or tenant");
        require(after.feeRate()>before.feeRate() && after.feeRate()<=policy.maximumFeeRate() && next.unsigned().feeAtomic()>previous.unsigned().feeAtomic()
                && next.unsigned().feeAtomic()<=policy.maximumFeeAtomic() && next.unsigned().feeAtomic()<=after.inputAtomic()/10,"BTC replacement exceeds independently installed absolute/relative rate or fee bounds");
        if(original.operation()==UtxoSigningRequest.Operation.WITHDRAWAL)require(original.payments().equals(after.payments()),"BTC replacement cannot change an approved withdrawal payment");
        else {
            require(original.payments().size()==1 && after.payments().size()==1,"BTC collection replacement requires one fixed treasury output");
            var first=original.payments().getFirst();var actual=after.payments().getFirst();
            require(first.reference().equals(actual.reference()) && first.userId()==actual.userId() && first.address().equals(actual.address())
                    && first.address().equals(original.changeAddress()) && first.customerFee().compareTo(actual.customerFee())==0
                    && original.asset().atomic(first.amount())==Math.subtractExact(original.inputAtomic(),root.unsigned().feeAtomic())
                    && after.asset().atomic(actual.amount())==Math.subtractExact(after.inputAtomic(),next.unsigned().feeAtomic()),"BTC collection replacement may only deduct its exact additional fee from the original treasury payment");
        }
    }
}
