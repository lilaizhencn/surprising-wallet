package com.surprising.wallet.job.deposit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BTC 区块扫描任务。
 * <p>
 * 定时扫描比特币区块链上的交易，识别充值交易并写入数据库，
 * 同时更新 UTXO 确认数和账户余额。每 59 秒执行一次。
 *
 * @author atomex
 */
@Component
@Slf4j
public class ScanBtcBlockJob extends ScanBlockJob {
    @Override
    protected String chain() {
        return "BTC";
    }

    @Scheduled(scheduler = "depositTaskScheduler", cron = "0/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
