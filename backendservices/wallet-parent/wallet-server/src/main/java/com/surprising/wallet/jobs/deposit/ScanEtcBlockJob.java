package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.service.wallet.impl.EtcWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author lilaizhen
 */
@Component
@Slf4j
public class ScanEtcBlockJob extends AbstractScanAccountBlockJob {

    @Autowired
    public ScanEtcBlockJob(EtcWallet etcWallet) {
        wallet = etcWallet;
    }

    //    @Scheduled(cron = "0/50 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
