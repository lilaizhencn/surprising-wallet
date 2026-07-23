package com.surprising.wallet.job.deposit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dogecoin block scanner, gated by wallet_system_config and chain_profile.
 */
@Component
public class ScanDogeBlockJob extends ScanUtxoBlockJob {
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
