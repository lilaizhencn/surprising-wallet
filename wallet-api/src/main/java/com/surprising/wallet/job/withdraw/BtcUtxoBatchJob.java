package com.surprising.wallet.job.withdraw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @author atomex
 */
@Component
@Slf4j
public class BtcUtxoBatchJob extends UtxoBatchJob {
/**
 * BTC 批处理任务（提现 + 归集）。
 * <p>
 * 每 30 秒执行一次：从 DB 拉取 BTC 链所有待签名的 withdrawal_order，
 * 选取可用 UTXO 构建批量交易（P2WSH 多签），推送到 Redis 一次签名队列。
 *
 * @author atomex
 */
    @Override
    protected String chain() {
        return "BTC";
    }

    //    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "1 1/2 * * * ?")
    @Scheduled(scheduler = "withdrawTaskScheduler", cron = "0/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}














