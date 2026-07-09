package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Unified asset metadata for native coins and multi-chain tokens.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainAsset implements Serializable {
    private Long id;
    private String chain;
    private String symbol;
    private String assetKind;
    private String contractAddress;
    private Integer decimals;
    private Boolean nativeAsset;
    private Boolean active;
    private BigDecimal minTransfer;
    private BigDecimal minWithdraw;
    private Instant createdAt;
    private Instant updatedAt;
}
