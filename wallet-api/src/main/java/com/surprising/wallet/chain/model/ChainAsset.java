package com.surprising.wallet.chain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/** 链资产配置；金额门槛与账本一样使用资产主单位。 */
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
    /** 归集候选比较使用的主单位门槛，保留最多 18 位小数。 */
    private BigDecimal minTransfer;
    /** 主单位提现门槛，保留最多 18 位小数。 */
    private BigDecimal minWithdraw;
    private Instant createdAt;
    private Instant updatedAt;
}
