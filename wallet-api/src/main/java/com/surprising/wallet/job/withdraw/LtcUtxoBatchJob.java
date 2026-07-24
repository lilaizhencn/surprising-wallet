package com.surprising.wallet.job.withdraw;

import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * LTC 批处理任务（提现 + 归集）。
 * <p>
 * 每 30 秒执行一次：拉取 LTC 链待签名订单，构建交易并推送到签名队列。
 */
@Component
@Slf4j
public class LtcUtxoBatchJob extends UtxoBatchJob {
    /**
     * 返回 LTC 链标识给基类路由。
     */
    @Override
    protected String chain() {
        return "LTC";
    }

    /**
     * 以 withdrawTaskScheduler 调度每 30 秒处理一次 LTC 批次。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "10/30 * * * * ?")
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void execute() {
        super.execute();
    }

    /**
     * LTC 使用链默认 litoshi/byte 费率，覆盖基类默认配置。
     */
    @Override
    protected int defaultFeeRate() {
        return (int) LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE;
    }
}
