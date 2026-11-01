package com.surprising.wallet.approval;

import com.surprising.wallet.approval.config.ApprovalAssetProperties;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import com.surprising.wallet.approval.service.ApprovalAssetRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalAssetRegistryTest {
    private ApprovalAssetSnapshot profile(String contract) {
        return new ApprovalAssetSnapshot("ETH","sepolia","USDC","ETH","eip155:11155111",contract,6,18,new BigDecimal("0.02"));
    }
    private WithdrawalIntent intent(BigDecimal amount,BigDecimal fee) {
        return new WithdrawalIntent(UUID.randomUUID(),UUID.randomUUID(),"CW-asset","ETH","sepolia","USDC","recipient",amount,fee,"API",UUID.randomUUID(),null, "external-test", "ADDRESS");
    }
    @Test void identityAndExactPrecisionAreIndependentAndUnknownDomainsFailClosed() {
        var asset=profile("0x3333333333333333333333333333333333333333");
        var registry=new ApprovalAssetRegistry(new ApprovalAssetProperties(List.of(asset)));
        assertEquals(asset,registry.require("ETH","sepolia","USDC"));
        assertThrows(IllegalStateException.class,()->registry.require("ETH","mainnet","USDC"));
        assertThrows(IllegalArgumentException.class,()->new ApprovalAssetRegistry(new ApprovalAssetProperties(List.of(asset,asset))));
        var json=new ObjectMapper();
        assertEquals(64,asset.bind(intent(new BigDecimal("1.123456"),new BigDecimal("0.01")),json).length());
        assertThrows(IllegalArgumentException.class,()->asset.bind(intent(new BigDecimal("1.1234567"),new BigDecimal("0.01")),json));
        assertThrows(IllegalArgumentException.class,()->asset.bind(intent(BigDecimal.ONE,new BigDecimal("0.03")),json));
        assertThrows(IllegalArgumentException.class,()->asset.bind(intent(BigDecimal.ONE,new BigDecimal("0.0000000000000000001")),json));
    }
    @Test void contractChangeCannotSilentlyReuseAnOldReviewedHashOrSnapshot() {
        var first=profile("0x3333333333333333333333333333333333333333");var changed=profile("0x4444444444444444444444444444444444444444");
        var json=new ObjectMapper();var intent=intent(BigDecimal.ONE,new BigDecimal("0.01"));
        assertNotEquals(first.bind(intent,json),changed.bind(intent,json));
        var current=new ApprovalAssetRegistry(new ApprovalAssetProperties(List.of(changed)));
        assertThrows(IllegalStateException.class,()->current.requireCurrent(first));
        assertDoesNotThrow(()->current.requireCurrent(changed));
        assertEquals(first,json.readValue(json.writeValueAsBytes(first),ApprovalAssetSnapshot.class));
    }
    @Test void nativeFeeUnitsCannotDifferAcrossAssetsAndAbsentOrWrongBudgetsFailClosed() {
        var asset=profile("0x3333333333333333333333333333333333333333");
        var wrongNative=new ApprovalAssetSnapshot("ETH","sepolia","TOKEN2","ETH","eip155:1","0x4444444444444444444444444444444444444444",6,18,new BigDecimal("0.02"));
        assertThrows(IllegalArgumentException.class,()->new ApprovalAssetRegistry(new ApprovalAssetProperties(List.of(asset,wrongNative))));
        UUID tenant=UUID.randomUUID();
        var absent=new com.surprising.wallet.approval.service.ApprovalFeeBudgets(new com.surprising.wallet.approval.config.ApprovalFeeProperties(null));
        assertThrows(IllegalStateException.class,()->absent.limit(tenant,asset));
        var budget=new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(tenant,"ETH","sepolia","eip155:11155111","ETH",18,new BigDecimal("0.01"));
        var properties=new com.surprising.wallet.approval.config.ApprovalFeeProperties(List.of(budget));
        var budgets=new com.surprising.wallet.approval.service.ApprovalFeeBudgets(properties);
        assertEquals(new java.math.BigInteger("10000000000000000"),budgets.limit(tenant,asset));
        assertThrows(IllegalStateException.class,()->budgets.limit(UUID.randomUUID(),asset));
        assertThrows(IllegalArgumentException.class,()->new com.surprising.wallet.approval.service.ApprovalFeeBudgets(new com.surprising.wallet.approval.config.ApprovalFeeProperties(List.of(budget,budget))));
    }
}
