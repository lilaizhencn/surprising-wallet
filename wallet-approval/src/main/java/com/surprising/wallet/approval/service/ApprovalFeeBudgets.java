package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.config.ApprovalFeeProperties;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ApprovalFeeBudgets {
    private final Map<Domain,ApprovalFeeProperties.Budget> budgets;
    public ApprovalFeeBudgets(ApprovalFeeProperties properties) {
        var map=new HashMap<Domain,ApprovalFeeProperties.Budget>();
        for(var budget:properties.budgets()) if(map.putIfAbsent(new Domain(budget.tenantId(),budget.chain(),budget.network()),budget)!=null)
            throw new IllegalArgumentException("duplicate independent fee budget");
        budgets=Map.copyOf(map);
    }
    public BigInteger limit(UUID tenant,ApprovalAssetSnapshot asset) {
        var budget=budgets.get(new Domain(tenant,asset.chain(),asset.network()));
        if(budget==null || !budget.networkIdentity().equals(asset.networkIdentity())
                || !budget.nativeSymbol().equals(asset.nativeSymbol()) || budget.nativeDecimals()!=asset.nativeDecimals())
            throw new IllegalStateException("independent native fee budget is not configured for this tenant/network");
        return budget.rolling24hLimit().movePointRight(asset.nativeDecimals()).toBigIntegerExact();
    }
    private record Domain(UUID tenant,String chain,String network) { }
}
