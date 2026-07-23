package com.surprising.wallet.job.deposit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LTC（Litecoin）区块扫描任务。
 * <p>
 * 定时扫描 LTC 区块链上的交易，识别充值交易并写入数据库，
 * 更新 UTXO 确认数和账户余额。每 59 秒执行一次。
 *
 * @author atomex
 */
@Component
@Slf4j
public class ScanLtcBlockJob extends ScanBlockJob {
    @Override
    protected String chain() {
        return "LTC";
    }

    @Scheduled(scheduler = "depositTaskScheduler", cron = "5/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
