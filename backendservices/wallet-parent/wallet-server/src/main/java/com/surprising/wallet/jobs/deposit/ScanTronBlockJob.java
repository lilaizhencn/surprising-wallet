package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.service.wallet.impl.TronWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author lilaizhen
 * @data 12/04/2018
 */
@Component
@Slf4j
public class ScanTronBlockJob extends AbstractScanAccountBlockJob {

    @Autowired
    public ScanTronBlockJob(TronWallet tronWallet) {
        wallet = tronWallet;
    }

    //    @Scheduled(cron = "1 1/2 * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
