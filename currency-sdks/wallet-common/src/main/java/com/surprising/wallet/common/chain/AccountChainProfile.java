package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountChainProfile {
    private String chain;
    private String network;
    private String family;
    private Integer runtimeCurrencyId;
    private Integer bip44CoinType;
    private String nativeSymbol;
    private String rpcUrl;
    private String explorerUrl;
    private Integer depositConfirmations;
    private Integer withdrawConfirmations;
    private Long defaultFee;
    private Long dustThreshold;
    private Boolean enabled;
}
