package com.surprising.wallet.jobs.transfer;

import com.surprising.wallet.service.wallet.impl.RbtcWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author atomex
 */
@Component
@Slf4j
public class RbtcTransferJob extends AbstractTransferJob {
    @Autowired
    private RbtcWallet rbtcWallet;

    @PostConstruct
    public void init() {
        super.setWallet(rbtcWallet);
    }

    //    @Scheduled(cron = "1 30 1/1 * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
