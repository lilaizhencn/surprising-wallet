package com.surprising.wallet.devfaucet.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 开发水龙头补币任务记录。
 *
 * <p>每条记录代表一个待发送或已发送的测试币补给请求，由 {@link com.surprising.wallet.devfaucet.repository.DevFaucetRepository}
 * 管理生命周期（discover → create → due → markSending → markSent → reconcileConfirmed）。
 *
 * @param id                唯一标识
 * @param tenantId          租户 ID
 * @param custodyAddressId  托管地址 ID
 * @param chain             链名称
 * @param network           网络名称
 * @param assetSymbol       资产符号
 * @param purpose           用途：TENANT_GAS 或 CUSTOMER_DEPOSIT
 * @param address           目标地址
 * @param contractAddress   代币合约地址（原生币为 null）
 * @param decimals          代币小数位
 * @param requestedAmount   请求补币金额
 * @param attempts          已尝试次数
 */
public record DevFaucetFunding(
        UUID id,
        UUID tenantId,
        UUID custodyAddressId,
        String chain,
        String network,
        String assetSymbol,
        String purpose,
        String address,
        String contractAddress,
        int decimals,
        BigDecimal requestedAmount,
        int attempts
) {
}
