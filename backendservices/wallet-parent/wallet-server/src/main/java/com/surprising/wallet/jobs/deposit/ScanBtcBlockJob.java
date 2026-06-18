package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.service.wallet.impl.BtcWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扫描打包在区块中的交易
 *
 * @author atomex
 */
@Component
@Slf4j
public class ScanBtcBlockJob extends AbstractScanUtxoBlockJob {

    @Autowired
    public ScanBtcBlockJob(BtcWallet btcWallet) {
        wallet = btcWallet;
    }

    //    @Scheduled(cron = "3 1/5 * * * ?")
    @Scheduled(cron = "0/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
