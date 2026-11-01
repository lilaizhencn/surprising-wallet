package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import java.math.BigDecimal;
import java.util.UUID;

/** Stable owned collection before selecting short-lived wire/inputs or paying its preparation costs. No Wallet balance/result JSON. */
public record AutomaticParentPlan(UUID tenantId,UUID sourceKeyId,String chain,String network,String assetSymbol,
        String businessReference,String recipient,BigDecimal amount,MpcClient.Request moneroObservation) {
    public AutomaticParentPlan {
        if(tenantId==null || sourceKeyId==null || chain==null || network==null || !network.matches("[a-zA-Z0-9_.:-]{1,128}")
                || assetSymbol==null || !assetSymbol.matches("[A-Z][A-Z0-9_.-]{0,31}") || businessReference==null || businessReference.isBlank()
                || businessReference.length()>192 || recipient==null || recipient.isBlank() || recipient.length()>256)
            throw new IllegalArgumentException("fixed owned collection plan required");
        ChainType.valueOf(chain);amount=WithdrawalIntent.number(amount,true);
        if(moneroObservation!=null && (!chain.equals("XMR") || moneroObservation.operation()!=MpcClient.Operation.MONERO_OBSERVE
                || !moneroObservation.tenantId().equals(tenantId) || !moneroObservation.keyId().equals(sourceKeyId)))
            throw new IllegalArgumentException("original Monero observation belongs to another planned source");
    }
}
