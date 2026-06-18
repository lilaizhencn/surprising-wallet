package com.surprising.wallet.jobs.transfer;

import com.surprising.wallet.service.wallet.impl.EtcWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author atomex
 */
@Component
@Slf4j
public class EtcTransferJob extends AbstractTransferJob {
    @Autowired
    EtcWallet etcWallet;

    @PostConstruct
    public void init() {
        super.setWallet(etcWallet);
    }

    //    @Scheduled(cron = "1 20 1/1 * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
