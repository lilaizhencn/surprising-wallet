package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionCandidateRecord {
    /**
     * 保存 {@code tenantId}，用于标识交易、区块或业务记录。
     */
    private UUID tenantId;
    /**
     * 保存 {@code custodyAddressId}，表示链、网络、资产或代币配置。
     */
    private UUID custodyAddressId;
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code assetSymbol}，表示链、网络、资产或代币配置。
     */
    private String assetSymbol;
    /**
     * 保存 {@code accountId}，用于标识交易、区块或业务记录。
     */
    private String accountId;
    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private String address;
    /**
     * 保存 {@code ownerAddress}，表示链、网络、资产或代币配置。
     */
    private String ownerAddress;
    /**
     * 保存 {@code userId}，用于标识交易、区块或业务记录。
     */
    private Long userId;
    /**
     * 保存 {@code biz}，用于承载当前对象的运行配置或业务数据。
     */
    private Integer biz;
    /**
     * 保存 {@code addressIndex}，表示链、网络、资产或代币配置。
     */
    private Long addressIndex;
    /**
     * 保存 {@code walletRole}，用于承载当前对象的运行配置或业务数据。
     */
    private String walletRole;
    /**
     * 保存 {@code amount}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal amount;
}
