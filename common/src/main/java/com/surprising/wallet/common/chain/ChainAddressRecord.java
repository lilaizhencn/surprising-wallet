package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
@Data
@Builder
@NoArgsConstructor
/**
 * 链上地址记录，保存每个用户在各个链上生成的地址信息及其派生路径。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} / {@code assetSymbol} - 链标识和资产符号</li>
 *   <li>{@code accountId} / {@code userId} - 账户 ID 和用户 ID</li>
 *   <li>{@code biz} - 业务类型标识</li>
 *   <li>{@code addressIndex} - 地址索引（BIP44 地址序号）</li>
 *   <li>{@code address} / {@code ownerAddress} - 链上地址及控制者地址</li>
 *   <li>{@code derivationPath} - BIP 派生路径</li>
 *   <li>{@code walletRole} - 钱包角色</li>
 * </ul>
 */
@AllArgsConstructor
public class ChainAddressRecord {
    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Long id;
    /**
     * 保存 {@code tenantId}，用于标识交易、区块或业务记录。
     */
    private UUID tenantId;
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
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private String address;
    /**
     * 保存 {@code ownerAddress}，表示链、网络、资产或代币配置。
     */
    private String ownerAddress;
    /**
     * 保存 {@code derivationPath}，用于承载当前对象的运行配置或业务数据。
     */
    private String derivationPath;
    /**
     * 保存 {@code walletRole}，用于承载当前对象的运行配置或业务数据。
     */
    private String walletRole;
    /**
     * 保存 {@code enabled}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean enabled;
}
