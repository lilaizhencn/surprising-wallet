package com.surprising.wallet.approval.config;

import com.surprising.wallet.common.approval.WithdrawalIntent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Independent operator ceiling; browser policies and Wallet callers cannot raise it. */
@ConfigurationProperties("approval.fees")
public record ApprovalFeeProperties(List<Budget> budgets) {
    public ApprovalFeeProperties { budgets=budgets==null?List.of():List.copyOf(budgets); }
    public record Budget(UUID tenantId,String chain,String network,String networkIdentity,String nativeSymbol,
            int nativeDecimals,BigDecimal rolling24hLimit) {
        public Budget {
            if(tenantId==null || chain==null || network==null || networkIdentity==null || nativeSymbol==null
                    || nativeDecimals<0 || nativeDecimals>36) throw new IllegalArgumentException("invalid independent fee budget domain");
            rolling24hLimit=WithdrawalIntent.number(rolling24hLimit,true);
            rolling24hLimit.movePointRight(nativeDecimals).toBigIntegerExact();
        }
    }
    @Configuration @EnableConfigurationProperties(ApprovalFeeProperties.class) public static class Binding { }
}
