package com.surprising.wallet.observer;

import com.surprising.wallet.common.chain.DepositEvent;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
@FunctionalInterface
public interface DepositCreditObserver {
    /**
     * 执行 {@code onDepositCredited} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    void onDepositCredited(DepositEvent event, long logIndex, String accountId);}
