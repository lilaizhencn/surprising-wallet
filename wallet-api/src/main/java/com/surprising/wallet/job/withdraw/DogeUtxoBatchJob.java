package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DOGE 批处理任务（提现 + 归集）。
 * <p>
 * 每 30 秒执行一次：拉取 DOGE 链待签名订单，构建交易并推送到签名队列。
 */
@Component
public class DogeUtxoBatchJob extends UtxoBatchJob {
    /**
     * 返回 DOGE 链标识给基类路由。
     */
    @Override
    protected String chain() {
        return "DOGE";
    }

    /**
     * 以 withdrawTaskScheduler 调度每 30 秒处理一次 DOGE 批次。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "12/30 * * * * ?")
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        super.execute();
    }

    /**
     * DOGE 使用默认 koinu/byte 费率，覆盖基类默认配置。
     */
    @Override
    protected int defaultFeeRate() {
        return (int) DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE;
    }

    /**
     * 按 DOGE 的签名体积模型重算网络费（sat），用于批次构建前预估。
     */
    @Override
    protected long estimateNetworkFeeAtomic(int inputCount, int outputCount, int feeRate) {
        long bytes = P2shMultisigFeeCalculator.estimateBytes(inputCount, outputCount, 2, 3);
        return DogecoinFeePolicy.feeForBytes(bytes, feeRate);
    }
}
