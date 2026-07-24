package com.surprising.wallet.job.withdraw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BTC 批处理任务（提现 + 归集）。
 * <p>
 * 每 30 秒执行一次：从 DB 拉取 BTC 链所有待签名订单，
 * 构建批量 UTXO 多签交易并推送到签名队列。
 */
@Component
@Slf4j
public class BtcUtxoBatchJob extends UtxoBatchJob {

    /**
     * 返回 BTC 链标识给基类路由。
     */
    @Override
    protected String chain() {
        return "BTC";
    }

    /**
     * 以 withdrawTaskScheduler 调度每 30 秒批处理一次 BTC 签名交易。
     */
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "0/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
