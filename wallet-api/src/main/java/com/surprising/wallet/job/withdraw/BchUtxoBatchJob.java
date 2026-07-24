package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * BCH 批处理任务（提现 + 归集）。
 * <p>
 * 每 30 秒执行一次：拉取 BCH 链待签名订单，构建交易并推送到签名队列。
 */
@Component
public class BchUtxoBatchJob extends UtxoBatchJob {
    /**
     * 返回 BCH 链标识给基类路由。
     */
    @Override
    protected String chain() {
        return "BCH";
    }

    /**
     * 以 withdrawTaskScheduler 调度每 30 秒处理一次 BCH 批次。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "14/30 * * * * ?")
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        super.execute();
    }

    /**
     * BCH 使用默认 sat/byte 费率，覆盖基类默认配置。
     */
    @Override
    protected int defaultFeeRate() {
        return (int) BitcoinCashFeePolicy.DEFAULT_SAT_PER_BYTE;
    }

    /**
     * 使用 BCH 的 P2SH 费率计算公式计算网络矿工费（sat）。
     */
    @Override
    protected long estimateNetworkFeeAtomic(int inputs, int outputs, int feeRate) {
        return P2shMultisigFeeCalculator.estimateBytes(inputs, outputs, 2, 3) * feeRate;
    }
}
