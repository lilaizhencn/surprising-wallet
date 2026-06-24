package com.surprising.wallet.jobs.withdraw;

import com.surprising.wallet.common.chain.RuntimeAsset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author atomex
 */
@Component
@Slf4j
public class BatchBtcWithdrawJob extends AbstractBatchWithdrawJob {


    @PostConstruct
    public void init() {
        currency = RuntimeAsset.BTC;
    }

    //    @Scheduled(cron = "1 1/2 * * * ?")
    @Scheduled(cron = "0/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
















