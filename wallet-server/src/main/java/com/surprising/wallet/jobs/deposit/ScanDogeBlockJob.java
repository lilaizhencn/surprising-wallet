package com.surprising.wallet.jobs.deposit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dogecoin block scanner, gated by wallet_system_config and chain_profile.
 */
@Component
public class ScanDogeBlockJob extends AbstractScanUtxoBlockJob {
    @Override
    protected String chain() {
        return "DOGE";
    }

    @Scheduled(cron = "7/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
