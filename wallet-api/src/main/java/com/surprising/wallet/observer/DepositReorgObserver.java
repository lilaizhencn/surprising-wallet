package com.surprising.wallet.observer;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
public interface DepositReorgObserver {
    /**
     * 执行 {@code onDepositReorged} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    void onDepositReorged(ReorgedDeposit deposit);
    record ReorgedDeposit(
            long depositRecordId,
            UUID tenantId,
            String chain,
            String assetSymbol,
            String txHash,
            long logIndex,
            String accountId,
            String toAddress,
            BigDecimal amount,
            BigDecimal reversedAmount,
            BigDecimal deficitAmount,
            int creditGeneration,
            long blockHeight,
            String blockHash,
            String replacementBlockHash,
            String reason
    ) {
    }
}
