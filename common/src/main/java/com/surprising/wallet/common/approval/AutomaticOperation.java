package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.signing.MpcSignCommand;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Public receipt for an independently installed operating rule. These are never human approval decisions. */
public record AutomaticOperation(UUID id,UUID ruleId,String ruleFingerprint,String businessType,String businessReference,
        List<ApprovalRequest> payments,UUID parentOperationId,Instant admittedAt) {
    public AutomaticOperation {
        payments=List.copyOf(payments);
        if(id==null || ruleId==null || ruleFingerprint==null || !ruleFingerprint.matches("[0-9a-f]{64}")
                || businessType==null || MpcSignCommand.BusinessType.valueOf(businessType)==MpcSignCommand.BusinessType.WITHDRAWAL
                || businessReference==null || businessReference.isBlank() || payments.isEmpty() || payments.size()>100 || admittedAt==null)
            throw new IllegalArgumentException("invalid independent automatic-operation receipt");
        for(var payment:payments)if(!payment.intent().source().equals("SYSTEM") || !payment.intent().subject().equals("AUTOMATION:"+ruleFingerprint)
                || !payment.policyId().equals(ruleId) || payment.requiredReviewers()!=0 || !payment.status().equals("APPROVED")
                || !payment.reasons().equals(List.of("INDEPENDENT_OPERATING_RULE")))
            throw new IllegalArgumentException("automatic operations cannot impersonate a human review");
        for(var payment:payments)if(payment.intent().amount().signum()==0 && !(businessType.equals("ADDRESS_PREPARATION") && payment.intent().chain().equals("XRP") && payment.assetSnapshot().tokenIdentifier()!=null))
            throw new IllegalArgumentException("zero principal is reserved for explicit XRP address preparation");
    }
    public String businessHash(MpcSignCommand command,ObjectMapper json) {
        if(!command.businessType().name().equals(businessType) || !reference(command,json).equals(businessReference))
            throw new IllegalArgumentException("automatic operation differs from original signing business");
        for(var payment:payments)if(!payment.intentHash().equals(payment.assetSnapshot().bind(payment.intent(),json)))
            throw new IllegalArgumentException("automatic payment snapshot differs");
        return ApprovalTransactionHash.business(payments,command);
    }
    public static String reference(MpcSignCommand command,ObjectMapper json) {
        return command.chain().isBitcoinLike()?"utxo:"+com.surprising.wallet.common.signing.UtxoSigningPayload.decode(command,json).task().transactionId():command.businessReference();
    }
}
