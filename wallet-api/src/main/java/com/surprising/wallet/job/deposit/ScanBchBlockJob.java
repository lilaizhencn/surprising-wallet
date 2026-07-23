package com.surprising.wallet.job.deposit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BCH（Bitcoin Cash）区块扫描任务。
 * <p>
 * 定时扫描 BCH 区块链上的交易，识别充值交易并写入数据库，
 * 更新 UTXO 确认数和账户余额。每 59 秒执行一次。
 *
 * @author atomex
 */
@Component
public class ScanBchBlockJob extends ScanBlockJob {
    @Override
    protected String chain() {
        return "BCH";
    }

    @Scheduled(scheduler = "depositTaskScheduler", cron = "9/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
