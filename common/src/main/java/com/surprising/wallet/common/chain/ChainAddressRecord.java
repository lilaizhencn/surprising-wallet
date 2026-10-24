package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

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
    private Long id;
    private UUID tenantId;
    private String chain;
    private String assetSymbol;
    private String accountId;
    private Long userId;
    private Integer biz;
    private Long addressIndex;
    private String address;
    private String ownerAddress;
    private String derivationPath;
    private String walletRole;
    private Boolean enabled;
}
