package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Database-backed runtime configuration for Bitcoin-like chains.
 *
 * <p>The runtime currency id is legacy routing metadata. It is intentionally
 * separate from the BIP44 coin type used for HD derivation.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BitcoinLikeChainProfile {
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
    private Long defaultFeeRate;
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
