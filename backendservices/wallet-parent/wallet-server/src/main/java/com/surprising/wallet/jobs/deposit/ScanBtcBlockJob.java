package com.surprising.wallet.jobs.deposit;

import jakarta.annotation.PostConstruct;
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

    @PostConstruct
    public void init() {
        currency = blockchainRuntimeService.runtimeAsset("BTC");
    }

    @Scheduled(cron = "0/59 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
