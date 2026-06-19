package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.service.wallet.impl.BchWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author lilaizhen
 */
@Component
@Slf4j
public class ScanBchBlockJob extends AbstractScanUtxoBlockJob {

    @Autowired
    public ScanBchBlockJob(BchWallet bchWallet) {
        wallet = bchWallet;
    }

    //    @Scheduled(cron = "1 1/5 * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
