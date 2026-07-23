package com.surprising.wallet.job.deposit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScanBchBlockJob extends AbstractScanUtxoBlockJob {
    @Override
    protected String chain() {
        return "BCH";
    }

    @Scheduled(cron = "9/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
