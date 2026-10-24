package com.surprising.wallet.common.chain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 链网络配置；运行时和配置管理共用 chain_profile 的类型模型。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountChainProfile {
    private Long id;
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
    private Long chainId;
    private String gasPolicy;
    private String feeModel;
    private Integer scanBatchSize;
    private Boolean scanEnabled;
    private Boolean withdrawEnabled;
    private Boolean collectionEnabled;
    private Boolean transferEnabled;
    private Long scanStartHeight;
    private Long scanMaxBlocksPerRun;
    private Instant createdAt;
    private Instant updatedAt;
}
