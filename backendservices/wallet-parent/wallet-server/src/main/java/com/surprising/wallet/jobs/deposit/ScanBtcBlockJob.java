package com.surprising.wallet.jobs.deposit;

import lombok.extern.slf4j.Slf4j;
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
    @Override
    protected String chain() {
        return "BTC";
    }

    @Scheduled(cron = "0/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
