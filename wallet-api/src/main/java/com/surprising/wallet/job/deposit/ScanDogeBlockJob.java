package com.surprising.wallet.job.deposit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DOGE（Dogecoin）区块扫描任务。
 * <p>
 * 定时扫描 Dogecoin 区块链上的交易，识别充值交易并写入数据库，
 * 更新 UTXO 确认数和账户余额。每 59 秒执行一次。
 *
 * @author atomex
 */
@Component
public class ScanDogeBlockJob extends ScanBlockJob {
    @Override
    protected String chain() {
        return "DOGE";
    }

    @Scheduled(scheduler = "depositTaskScheduler", cron = "7/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
