package com.surprising.wallet.jobs.withdraw;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@Slf4j
public class BatchLtcWithdrawJob extends AbstractBatchWithdrawJob {
    @PostConstruct
    public void init() {
        currency = CurrencyEnum.LTC;
    }

    @Scheduled(cron = "10/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }

    @Override
    protected int defaultFeeRate() {
        return (int) LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE;
    }
}
