package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
/**
 * 账户链配置档案，描述特定区块链网络的运行参数，包括 RPC 连接、费率、确认数等配置。
 *
 * <p>主要配置项：</p>
 * <ul>
 *   <li>{@code chain} / {@code network} / {@code family} - 链标识、网络环境和链家族</li>
 *   <li>{@code rpcUrl} - JSON-RPC 节点地址</li>
 *   <li>{@code explorerUrl} - 区块链浏览器地址</li>
 *   <li>{@code depositConfirmations} / {@code withdrawConfirmations} - 入金/出金确认数</li>
 *   <li>{@code defaultFee} / {@code dustThreshold} - 默认手续费和粉尘阈值</li>
 *   <li>{@code chainId} - 链 ID（EVM 兼容链使用）</li>
 *   <li>{@code gasPolicy} - Gas 费策略</li>
 *   <li>{@code scanBatchSize} / {@code scanMaxBlocksPerRun} - 扫描批次大小和最大区块范围</li>
 *   <li>{@code scanEnabled} / {@code withdrawEnabled} / {@code collectionEnabled} / {@code transferEnabled} - 功能开关</li>
 * </ul>
 */
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
    private Long chainId;
    private String gasPolicy;
    private Integer scanBatchSize;
    private Boolean scanEnabled;
    private Boolean withdrawEnabled;
    private Boolean collectionEnabled;
    private Boolean transferEnabled;
    private Long scanStartHeight;
    private Long scanMaxBlocksPerRun;
}
