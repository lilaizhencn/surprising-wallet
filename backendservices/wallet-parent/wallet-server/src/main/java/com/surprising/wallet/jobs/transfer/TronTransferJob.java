package com.surprising.wallet.jobs.transfer;

import com.surprising.wallet.service.wallet.impl.TronWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author atomex
 */
@Component
@Slf4j
public class TronTransferJob extends AbstractTransferJob {
    @Autowired
    TronWallet tronWallet;

    @PostConstruct
    public void init() {
        super.setWallet(tronWallet);
    }

    //    @Scheduled(cron = "1 30 1/1 * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
