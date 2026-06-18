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
public class BatchBchWithdrawJob extends AbstractBatchWithdrawJob {
    @PostConstruct
    public void init() {
        currency = CurrencyEnum.BCH;
    }

    //    @Scheduled(cron = "1 1/1 * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
