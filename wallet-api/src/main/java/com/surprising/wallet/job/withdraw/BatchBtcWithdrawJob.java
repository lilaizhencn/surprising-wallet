package com.surprising.wallet.job.withdraw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @author atomex
 */
@Component
@Slf4j
public class BatchBtcWithdrawJob extends AbstractBatchWithdrawJob {
    @Override
    protected String chain() {
        return "BTC";
    }

    //    @Scheduled(cron = "1 1/2 * * * ?")
    @Scheduled(cron = "0/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}














