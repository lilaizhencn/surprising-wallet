package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.service.wallet.impl.DogeWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 扫描打包在区块中的交易
 *
 * @author lilaizhen
 */
@Component
@Slf4j
public class ScanDogeBlockJob extends AbstractScanUtxoBlockJob {

    @Autowired
    public ScanDogeBlockJob(DogeWallet dogeWallet) {
        wallet = dogeWallet;
    }

    //    @Scheduled(cron = "1 1/1 * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
