package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcSignCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalTransactionHashTest {
    private final UUID tenant=UUID.randomUUID();private final ObjectMapper json=new ObjectMapper();
    private ApprovalRequest review(String reference) {
        var intent=new WithdrawalIntent(UUID.randomUUID(),tenant,reference,"ETH","sepolia","ETH","0x"+"11".repeat(20),BigDecimal.ONE,new BigDecimal("0.01"),"API",UUID.randomUUID(),"customer","merchant","TENANT_POOL");
        var asset=new ApprovalAssetSnapshot("ETH","sepolia","ETH","ETH","eip155:11155111",null,18,18,new BigDecimal("0.01"));
        return new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("ALL_WITHDRAWALS"),2,"APPROVED",false,Instant.EPOCH,Instant.EPOCH.plusSeconds(3600));
    }
    private MpcSignCommand command(String payload,String slot,String digest) {
        return new MpcSignCommand(tenant,UUID.randomUUID(),1,ChainType.ETH,MpcSignCommand.BusinessType.WITHDRAWAL,"batch-42",slot,MpcSignCommand.Purpose.TRANSACTION,"EVM_TX_JSON",payload,digest);
    }
    @Test void everyInputUsesTheSameReviewedBatchHashRegardlessOfRequestOrderKeyOrMessageSlot() {
        var a=review("first");var b=review("second");
        var first=command("00","input/0","11".repeat(32));var second=command("00","input/1","22".repeat(32));
        assertEquals(ApprovalTransactionHash.business(List.of(a,b),first),ApprovalTransactionHash.business(List.of(b,a),second));
        assertNotEquals(ApprovalTransactionHash.business(List.of(a,b),first),ApprovalTransactionHash.business(List.of(a),first));
        assertNotEquals(ApprovalTransactionHash.business(List.of(a,b),first),ApprovalTransactionHash.business(List.of(a,b),command("01","input/0",first.preimageHex())));
    }
    @Test void duplicateOrCrossTenantReviewsCannotBecomeATransactionBinding() {
        var a=review("first");var command=command("00","transaction","11".repeat(32));
        assertThrows(IllegalArgumentException.class,()->ApprovalTransactionHash.business(List.of(a,a),command));
        var other=new MpcSignCommand(UUID.randomUUID(),command.keyId(),1,command.chain(),command.businessType(),command.businessReference(),command.signingSlot(),command.purpose(),command.payloadEncoding(),command.payloadHex(),command.preimageHex());
        assertThrows(IllegalArgumentException.class,()->ApprovalTransactionHash.business(List.of(a),other));
    }
}
