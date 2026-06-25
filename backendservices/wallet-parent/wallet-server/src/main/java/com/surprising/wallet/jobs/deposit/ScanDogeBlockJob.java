package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.service.wallet.impl.DogeWallet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dogecoin block scanner, gated by wallet_system_config and chain_profile.
 */
@Component
public class ScanDogeBlockJob extends AbstractScanUtxoBlockJob {
    @Autowired
    public ScanDogeBlockJob(DogeWallet dogeWallet) {
        wallet = dogeWallet;
    }

    @Scheduled(cron = "7/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
