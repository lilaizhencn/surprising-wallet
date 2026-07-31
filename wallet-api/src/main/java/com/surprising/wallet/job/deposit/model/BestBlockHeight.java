package com.surprising.wallet.job.deposit.model;

import lombok.*;

import java.io.Serializable;
import java.util.Date;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BestBlockHeight implements Serializable {

    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Integer id;

    /**
     * 保存 {@code currency}，表示链、网络、资产或代币配置。
     */
    private Integer currency;

    /**
     * 保存 {@code height}，用于标识交易、区块或业务记录。
     */
    private Long height;
    /**
     * 区块更新间隔时间，默认5分钟
     */
    private Long intervalTime;

    /**
     * 保存 {@code createDate}，用于记录时间边界或审计时间。
     */
    private Date createDate;

    /**
     * 保存 {@code updateDate}，用于记录时间边界或审计时间。
     */
    private Date updateDate;
}
