package com.surprising.wallet.deposit.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Litecoin block scanner. Execution is gated by wallet_system_config and chain_profile.
 */
@Component
@Slf4j
public class ScanLtcBlockJob extends AbstractScanUtxoBlockJob {
    @Override
    protected String chain() {
        return "LTC";
    }

    @Scheduled(cron = "5/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
