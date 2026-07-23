package com.surprising.wallet.job.withdraw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @author atomex
 */
@Component
@Slf4j
public class BtcUtxoBatchJob extends AbstractUtxoBatchJob {
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














