package com.surprising.wallet.jobs.withdraw;

import com.surprising.wallet.common.currency.CurrencyEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author atomex
 */
@Component
@Slf4j
public class BatchGasWithdrawJob extends AbstractBatchWithdrawJob {


    @PostConstruct
    public void init() {
        currency = CurrencyEnum.GAS;
    }

    //    @Scheduled(cron = "1 1/2 * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
















