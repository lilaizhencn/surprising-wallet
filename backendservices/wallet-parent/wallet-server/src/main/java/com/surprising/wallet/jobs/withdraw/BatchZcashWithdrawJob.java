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
public class BatchZcashWithdrawJob extends AbstractBatchWithdrawJob {
    @PostConstruct
    public void init() {
        currency = CurrencyEnum.ZEC;
    }

    //    @Scheduled(cron = "1 1/2 * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
