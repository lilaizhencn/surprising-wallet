package com.surprising.wallet.approval.config;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcSignCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Host-installed operating permissions. Wallet and its tenant browser cannot add a treasury/Gas role or replenish quotas. */
@ConfigurationProperties("approval.automation")
public record ApprovalAutomationProperties(List<Rule> rules) {
    public ApprovalAutomationProperties {
        rules=rules==null?List.of():List.copyOf(rules);
        if(rules.stream().map(Rule::id).distinct().count()!=rules.size()
                || rules.stream().map(r->r.tenantId()+":"+r.chain()+":"+r.network()+":"+r.symbol()+":"+r.businessType()
                        + (Set.of("COLLECTION", "RECOVERY").contains(r.businessType()) ? ":"+r.destinationKeyId() : ""))
                        .distinct().count()!=rules.size())
            throw new IllegalArgumentException("ambiguous independent operating rules");
    }
    public record Rule(UUID id,UUID tenantId,String chain,String network,String symbol,String businessType,Set<UUID> sourceKeyIds,UUID destinationKeyId,
            BigDecimal minimumAmount,BigDecimal maximumAmount,BigDecimal maximum24hAmount,BigDecimal maximumLifetimeAmount,
            BigDecimal maximumNetworkFee,BigDecimal maximumLifetimeNetworkFee,int maximum24hOperations,int minimumIntervalSeconds,int maximumNativeFeeShareBps) {
        public Rule {
            if(id==null || tenantId==null || chain==null || network==null || !network.matches("[a-zA-Z0-9_.:-]{1,128}")
                    || symbol==null || !symbol.matches("[A-Z][A-Z0-9_.-]{0,31}"))throw new IllegalArgumentException("invalid independent operating domain");
            ChainType.valueOf(chain);var type=MpcSignCommand.BusinessType.valueOf(businessType);sourceKeyIds=sourceKeyIds==null?Set.of():Set.copyOf(sourceKeyIds);
            if(type==MpcSignCommand.BusinessType.WITHDRAWAL || maximum24hOperations<1 || maximum24hOperations>10000
                    || minimumIntervalSeconds<60 || minimumIntervalSeconds>86400 || maximumNativeFeeShareBps<1 || maximumNativeFeeShareBps>10000
                    || minimumAmount==null || minimumAmount.signum()<0 || (minimumAmount.signum()==0 && type!=MpcSignCommand.BusinessType.ADDRESS_PREPARATION) || maximumAmount==null || maximumAmount.compareTo(minimumAmount)<0
                    || maximum24hAmount==null || maximum24hAmount.compareTo(maximumAmount)<0
                    || maximumLifetimeAmount==null || maximumLifetimeAmount.compareTo(maximum24hAmount)<0
                    || maximumNetworkFee==null || maximumNetworkFee.signum()<=0 || maximumLifetimeNetworkFee==null || maximumLifetimeNetworkFee.compareTo(maximumNetworkFee)<0)
                throw new IllegalArgumentException("automatic operation requires explicit bounded principal/count/fee/interval permissions");
            if(type==MpcSignCommand.BusinessType.ADDRESS_PREPARATION && (!chain.equals("XRP") || minimumAmount.signum()!=0 || maximumAmount.signum()!=0 || destinationKeyId!=null))
                throw new IllegalArgumentException("address preparation is a zero-principal XRP-only permission");
            if((type==MpcSignCommand.BusinessType.COLLECTION || type==MpcSignCommand.BusinessType.RECOVERY) && destinationKeyId==null)
                throw new IllegalArgumentException("collection/recovery requires an independently installed treasury key");
            if(type==MpcSignCommand.BusinessType.RECOVERY && sourceKeyIds.isEmpty())throw new IllegalArgumentException("recovery requires an explicitly installed source role");
            if((type==MpcSignCommand.BusinessType.FUNDING || type==MpcSignCommand.BusinessType.DEV_FAUCET) && sourceKeyIds.size()!=1)
                throw new IllegalArgumentException("funding/faucet requires one explicitly installed source role");
            if(type==MpcSignCommand.BusinessType.DEV_FAUCET && !Set.of("regtest","localnet","devnet","testnet","sepolia","nile","preview").contains(network))
                throw new IllegalArgumentException("development faucet cannot use a production network policy");
        }
    }
    @Configuration @EnableConfigurationProperties(ApprovalAutomationProperties.class) public static class Binding { }
}
